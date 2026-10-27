package dev.skramer.dashcam.player;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads a segment's exact length from the first and last video timestamps in the MPEG-TS file,
 * which takes two small reads instead of parsing the whole file.
 */
final class TsProbe {
    private TsProbe() {}

    private static final int PACKET = 188;
    private static final int HEAD = 64 * PACKET;
    private static final int TAIL = 1024 * PACKET;
    /** One frame at 29.97 fps: the last frame lasts this long past its timestamp. */
    private static final long FRAME_MS = 33;

    /** Per path: {file length, duration ms}. A growing (live) file is probed again. */
    private static final Map<String, long[]> cache = new HashMap<>();

    /** Duration in ms, or -1 if the file has no readable timestamps. */
    static long durationMs(File file) {
        long length = file.length();
        synchronized (cache) {
            long[] hit = cache.get(file.getPath());
            if (hit != null && hit[0] == length) return hit[1];
        }
        long d = -1;
        try (RandomAccessFile r = new RandomAccessFile(file, "r")) {
            long first = scan(r, 0, (int) Math.min(length, HEAD), true);
            long tailStart = Math.max(0, (length - TAIL) / PACKET * PACKET);
            long last = scan(r, tailStart, (int) (length - tailStart), false);
            if (first >= 0 && last >= first) d = (last - first) / 90 + FRAME_MS;
        } catch (IOException e) {
            return -1;
        }
        synchronized (cache) {
            cache.put(file.getPath(), new long[] {length, d});
        }
        return d;
    }

    /** The first (or last) video PES timestamp in [offset, offset + n), in 90 kHz units; -1 if none. */
    private static long scan(RandomAccessFile r, long offset, int n, boolean first) throws IOException {
        byte[] b = new byte[n];
        r.seek(offset);
        r.readFully(b);
        long found = -1;
        for (int p = 0; p + PACKET <= n; p += PACKET) {
            if (b[p] != 0x47) {
                // Out of step (shouldn't happen: the recorder writes whole packets); find the next sync.
                int q = p + 1;
                while (q + PACKET < n && !(b[q] == 0x47 && b[q + PACKET] == 0x47)) q++;
                if (q + PACKET >= n) break;
                p = q - PACKET;
                continue;
            }
            if ((b[p + 1] & 0x40) == 0) continue;  // not the start of a PES packet
            int afc = (b[p + 3] >> 4) & 3;
            int h = p + 4;
            if ((afc & 2) != 0) h += 1 + (b[h] & 0xFF);
            if ((afc & 1) == 0 || h + 14 > p + PACKET) continue;
            if (b[h] != 0 || b[h + 1] != 0 || b[h + 2] != 1 || (b[h + 3] & 0xF0) != 0xE0) continue;
            if ((b[h + 7] & 0x80) == 0) continue;  // no PTS
            long pts = ((long) (b[h + 9] & 0x0E) << 29) | ((b[h + 10] & 0xFF) << 22)
                    | ((b[h + 11] & 0xFE) << 14) | ((b[h + 12] & 0xFF) << 7) | ((b[h + 13] & 0xFE) >> 1);
            if (first) return pts;
            found = Math.max(found, pts);
        }
        return found;
    }
}
