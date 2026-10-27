package dev.skramer.dashcam;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

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

    private final FileOutputStream file;
    private final BufferedOutputStream out;
    private final byte[] packet = new byte[PACKET];
    private final ByteArrayOutputStream pes = new ByteArrayOutputStream(64 * 1024);
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
        if (key) {
            writePat();
            writePmt();
        }
        long pcr = ptsUs * 9 / 100;
        long pts = pcr + PTS_DELAY;
        pes.reset();
        pes.write(new byte[] {0, 0, 1, (byte) 0xE0, 0, 0, (byte) 0x80, (byte) 0x80, 5});
        pes.write(new byte[] {
            (byte) (0x21 | ((pts >> 29) & 0x0E)), (byte) (pts >> 22),
            (byte) (((pts >> 14) & 0xFE) | 1), (byte) (pts >> 7), (byte) (((pts << 1) & 0xFE) | 1),
        });
        pes.write(AUD);
        byte[] nals = annexB(frame);
        if (key && config != null && !hasNal(nals, 7)) pes.write(annexB(config));
        pes.write(nals);
        packetize(VIDEO_PID, pes.toByteArray(), pcr, key);
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
        byte[] s = section(0x00, 0x0001, new byte[] {0, 1, (byte) (0xE0 | PMT_PID >> 8), (byte) PMT_PID});
        writeSection(0, s, patCc);
        patCc = (patCc + 1) & 15;
    }

    private void writePmt() throws IOException {
        byte[] s = section(0x02, 0x0001, new byte[] {
            (byte) (0xE0 | VIDEO_PID >> 8), (byte) VIDEO_PID, (byte) 0xF0, 0,  // PCR PID, no program info
            0x1B, (byte) (0xE0 | VIDEO_PID >> 8), (byte) VIDEO_PID, (byte) 0xF0, 0,  // H.264, no ES info
        });
        writeSection(PMT_PID, s, pmtCc);
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
        java.util.Arrays.fill(packet, (byte) 0xFF);
        packet[0] = 0x47;
        packet[1] = (byte) (0x40 | pid >> 8);
        packet[2] = (byte) pid;
        packet[3] = (byte) (0x10 | cc);
        packet[4] = 0;  // pointer field
        System.arraycopy(section, 0, packet, 5, section.length);
        out.write(packet);
    }

    /** Splits a PES packet into TS packets; the first carries the PCR (and the keyframe flag). */
    private void packetize(int pid, byte[] data, long pcr, boolean key) throws IOException {
        int pos = 0;
        boolean first = true;
        while (pos < data.length) {
            int minAf = first ? 8 : 0;  // length byte, flags, 6-byte PCR
            int chunk = Math.min(184 - minAf, data.length - pos);
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
            System.arraycopy(data, pos, packet, p, chunk);
            out.write(packet);
            pos += chunk;
            first = false;
        }
    }

    /** Returns NAL units in Annex-B form, converting from 4-byte length prefixes if needed. */
    static byte[] annexB(byte[] b) {
        if (b.length >= 4 && b[0] == 0 && b[1] == 0 && (b[2] == 1 || (b[2] == 0 && b[3] == 1))) return b;
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length + 16);
        int i = 0;
        while (i + 4 <= b.length) {
            int n = (b[i] & 0xFF) << 24 | (b[i + 1] & 0xFF) << 16 | (b[i + 2] & 0xFF) << 8 | (b[i + 3] & 0xFF);
            i += 4;
            if (n <= 0 || i + n > b.length) break;
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
        for (int i = 0; i + 3 < b.length; i++) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1 && (b[i + 3] & 0x1F) == type) return true;
        }
        return false;
    }

    private static final int[] CRC_TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int c = i << 24;
            for (int k = 0; k < 8; k++) c = (c & 0x80000000) != 0 ? (c << 1) ^ 0x04C11DB7 : c << 1;
            CRC_TABLE[i] = c;
        }
    }

    /** CRC-32/MPEG-2, as PSI sections use. */
    static int crc32(byte[] b, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < len; i++) crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ b[i]) & 0xFF];
        return crc;
    }
}
