package dev.skramer.dashcam;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Minimal MPEG-TS muxer for one H.264 stream. TS rather than MP4 because a TS file that is
 * cut off (power loss, crash) still plays up to its last packet; Android's MP4 muxer only
 * writes a playable file once it's finalized.
 */
final class TsWriter implements Closeable {
    private static final int PACKET = 188;
    private static final int PMT_PID = 0x1000;
    private static final int VIDEO_PID = 0x100;
    private static final byte[] AUD = {0, 0, 0, 1, 9, (byte) 0xF0};
    /** PTS runs this far ahead of the PCR, so players have the frame before it's due. */
    private static final long PTS_DELAY = 45_000;

    private static final int[] CRC_TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int c = i << 24;
            for (int k = 0; k < 8; k++) c = (c & 0x80000000) != 0 ? (c << 1) ^ 0x04C11DB7 : c << 1;
            CRC_TABLE[i] = c;
        }
    }

    private static final byte[] PAT_SECTION = section(0x00, 0x0001, new byte[] {
        0, 1, (byte) (0xE0 | PMT_PID >> 8), (byte) PMT_PID,
    });
    private static final byte[] PMT_SECTION = section(0x02, 0x0001, new byte[] {
        (byte) (0xE0 | VIDEO_PID >> 8), (byte) VIDEO_PID, (byte) 0xF0, 0,  // PCR PID, no program info
        0x1B, (byte) (0xE0 | VIDEO_PID >> 8), (byte) VIDEO_PID, (byte) 0xF0, 0,  // H.264, no ES info
    });

    private final FileOutputStream file;
    private final BufferedOutputStream out;
    private final byte[] packet = new byte[PACKET];
    private byte[] header = new byte[128];
    private int patCc, pmtCc, videoCc;

    TsWriter(File path) throws IOException {
        file = new FileOutputStream(path);
        out = new BufferedOutputStream(file, PACKET * 1024);
    }

    /**
     * Writes one encoded frame (Annex-B or 4-byte length-prefixed NAL units). Keyframes get
     * the PAT/PMT and, if the frame doesn't carry them, the SPS/PPS from {@code config}, so a
     * player can start at any keyframe.
     */
    void writeFrame(byte[] frame, long ptsUs, boolean key, byte[] config) throws IOException {
        writeFrame(frame, frame.length, ptsUs, key, config);
    }

    void writeFrame(byte[] frame, int frameLen, long ptsUs, boolean key, byte[] config) throws IOException {
        if (key) {
            writePat();
            writePmt();
        }
        long pcr = ptsUs * 9 / 100;
        long pts = pcr + PTS_DELAY;
        header[0] = 0;
        header[1] = 0;
        header[2] = 1;
        header[3] = (byte) 0xE0;
        header[4] = 0;
        header[5] = 0;
        header[6] = (byte) 0x80;
        header[7] = (byte) 0x80;
        header[8] = 5;
        header[9] = (byte) (0x21 | ((pts >> 29) & 0x0E));
        header[10] = (byte) (pts >> 22);
        header[11] = (byte) (((pts >> 14) & 0xFE) | 1);
        header[12] = (byte) (pts >> 7);
        header[13] = (byte) (((pts << 1) & 0xFE) | 1);
        System.arraycopy(AUD, 0, header, 14, AUD.length);
        int headLen = 14 + AUD.length;

        byte[] nals;
        int nalsLen;
        if (isAnnexB(frame, frameLen)) {
            nals = frame;
            nalsLen = frameLen;
        } else {
            nals = annexB(frame, frameLen);
            nalsLen = nals.length;
        }
        if (key && config != null && !hasNal(nals, nalsLen, 7)) {
            byte[] cfg = annexB(config);
            if (headLen + cfg.length > header.length) {
                header = Arrays.copyOf(header, headLen + cfg.length);
            }
            System.arraycopy(cfg, 0, header, headLen, cfg.length);
            headLen += cfg.length;
        }
        packetize(VIDEO_PID, header, headLen, nals, nalsLen, pcr, key);
    }

    /** Pushes buffered data to the drive, so a power cut loses at most about a second. */
    void sync() throws IOException {
        out.flush();
        file.getFD().sync();
    }

    @Override
    public void close() throws IOException {
        try {
            sync();
        } finally {
            out.close();
        }
    }

    private void writePat() throws IOException {
        writeSection(0, PAT_SECTION, patCc);
        patCc = (patCc + 1) & 15;
    }

    private void writePmt() throws IOException {
        writeSection(PMT_PID, PMT_SECTION, pmtCc);
        pmtCc = (pmtCc + 1) & 15;
    }

    /** A PSI section with the standard 5-byte header and CRC. */
    private static byte[] section(int tableId, int idExt, byte[] body) {
        int len = 5 + body.length + 4;
        byte[] s = new byte[3 + len];
        s[0] = (byte) tableId;
        s[1] = (byte) (0xB0 | len >> 8);
        s[2] = (byte) len;
        s[3] = (byte) (idExt >> 8);
        s[4] = (byte) idExt;
        s[5] = (byte) 0xC1;  // version 0, current
        System.arraycopy(body, 0, s, 8, body.length);
        int crc = crc32(s, s.length - 4);
        for (int i = 0; i < 4; i++) s[s.length - 4 + i] = (byte) (crc >> (24 - 8 * i));
        return s;
    }

    private void writeSection(int pid, byte[] section, int cc) throws IOException {
        Arrays.fill(packet, (byte) 0xFF);
        packet[0] = 0x47;
        packet[1] = (byte) (0x40 | pid >> 8);
        packet[2] = (byte) pid;
        packet[3] = (byte) (0x10 | cc);
        packet[4] = 0;  // pointer field
        System.arraycopy(section, 0, packet, 5, section.length);
        out.write(packet);
    }

    /** Splits a PES packet (head + body) into TS packets; the first carries the PCR (and the keyframe flag). */
    private void packetize(int pid, byte[] head, int headLen, byte[] body, int bodyLen, long pcr, boolean key)
            throws IOException {
        int total = headLen + bodyLen;
        int pos = 0;
        boolean first = true;
        while (pos < total) {
            int minAf = first ? 8 : 0;  // length byte, flags, 6-byte PCR
            int chunk = Math.min(184 - minAf, total - pos);
            int af = 184 - chunk;  // adaptation field bytes, including its length byte
            packet[0] = 0x47;
            packet[1] = (byte) ((first ? 0x40 : 0) | pid >> 8);
            packet[2] = (byte) pid;
            packet[3] = (byte) ((af > 0 ? 0x30 : 0x10) | videoCc);
            videoCc = (videoCc + 1) & 15;
            int p = 4;
            if (af > 0) {
                packet[p++] = (byte) (af - 1);
                if (af > 1) {
                    packet[p++] = (byte) ((first ? 0x10 : 0) | (first && key ? 0x40 : 0));
                    if (first) {
                        packet[p++] = (byte) (pcr >> 25);
                        packet[p++] = (byte) (pcr >> 17);
                        packet[p++] = (byte) (pcr >> 9);
                        packet[p++] = (byte) (pcr >> 1);
                        packet[p++] = (byte) (((pcr & 1) << 7) | 0x7E);
                        packet[p++] = 0;
                    }
                    while (p < 4 + af) packet[p++] = (byte) 0xFF;
                }
            }
            int rem = chunk;
            if (pos < headLen) {
                int n = Math.min(rem, headLen - pos);
                System.arraycopy(head, pos, packet, p, n);
                p += n;
                pos += n;
                rem -= n;
            }
            if (rem > 0) {
                System.arraycopy(body, pos - headLen, packet, p, rem);
                pos += rem;
            }
            out.write(packet);
            first = false;
        }
    }

    private static boolean isAnnexB(byte[] b, int len) {
        return len >= 4 && b[0] == 0 && b[1] == 0 && (b[2] == 1 || (b[2] == 0 && b[3] == 1));
    }

    /** Returns NAL units in Annex-B form, converting from 4-byte length prefixes if needed. */
    static byte[] annexB(byte[] b) {
        if (isAnnexB(b, b.length)) return b;
        return annexB(b, b.length);
    }

    private static byte[] annexB(byte[] b, int len) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(len + 16);
        int i = 0;
        while (i + 4 <= len) {
            int n = (b[i] & 0xFF) << 24 | (b[i + 1] & 0xFF) << 16 | (b[i + 2] & 0xFF) << 8 | (b[i + 3] & 0xFF);
            i += 4;
            if (n <= 0 || i + n > len) break;
            o.write(0);
            o.write(0);
            o.write(0);
            o.write(1);
            o.write(b, i, n);
            i += n;
        }
        return o.toByteArray();
    }

    /** True if the Annex-B data contains a NAL unit of this type (7 = SPS). */
    static boolean hasNal(byte[] b, int type) {
        return hasNal(b, b.length, type);
    }

    static boolean hasNal(byte[] b, int len, int type) {
        for (int i = 0; i + 3 < len; i++) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) {
                int nal = b[i + 3] & 0x1F;
                if (nal == type) return true;
                if (nal == 1 || nal == 5) return false;  // parameter sets always precede coded slices
            }
        }
        return false;
    }

    /** CRC-32/MPEG-2, as PSI sections use. */
    static int crc32(byte[] b, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < len; i++) crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ b[i]) & 0xFF];
        return crc;
    }
}
