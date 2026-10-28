package dev.skramer.dashcam;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.IOException;
import libcore.io.Libcore;
import libcore.io.Memory;
import libcore.io.OsConstants;

/**
 * The backup camera's picture in RAM. While the camera's direct-display path runs (see
 * {@link RearCam}), the kernel driver has the hardware write each frame into a ring of four
 * buffers at the start of the reserved "rearcamera" region: width × height BGRA pixels each, one
 * after the other, rows packed. The display shows them from there.
 * <p>
 * The region is mapped read-only through /dev/admem, whose open and mmap have no side effects
 * (unlike opening /dev/video0, which takes the camera driver's lock). Reading it never disturbs
 * the camera or the reverse view; while nothing runs the camera it just holds the last frames.
 */
final class CameraRam {
    static final int SLOTS = 4;
    private static final String PMAP = "/proc/pmap", REGION = "rearcamera", DEVICE = "/dev/admem";

    private final long address, length, frameBytes;

    private CameraRam(long address, long length, long frameBytes) {
        this.address = address;
        this.length = length;
        this.frameBytes = frameBytes;
    }

    /** Maps the ring for frames of this size; fails if the reserved region is missing or too small. */
    static CameraRam map(int width, int height) throws Exception {
        long[] region = region();
        long frameBytes = (long) width * height * 4, length = frameBytes * SLOTS;
        if (length > region[1]) {
            throw new IOException(String.format(java.util.Locale.US, "%s is 0x%x bytes; %d %dx%d frames need 0x%x",
                    REGION, region[1], SLOTS, width, height, length));
        }
        FileDescriptor fd = Libcore.os.open(DEVICE, OsConstants.O_RDONLY | OsConstants.O_SYNC, 0);
        try {
            long a = Libcore.os.mmap(0, length, OsConstants.PROT_READ, OsConstants.MAP_SHARED, fd, region[0]);
            Log.i(String.format(java.util.Locale.US, "camera RAM: %s at 0x%x, 0x%x bytes; mapped 0x%x",
                    REGION, region[0], region[1], length));
            return new CameraRam(a, length, frameBytes);
        } finally {
            Libcore.os.close(fd);
        }
    }

    /** Physical address and size of the reserved region, from the kernel's list ("base size name" lines). */
    private static long[] region() throws IOException {
        try (BufferedReader r = new BufferedReader(new FileReader(PMAP))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.trim().split("\\s+");
                if (f.length == 3 && f[2].equals(REGION) && f[0].startsWith("0x") && f[1].startsWith("0x")) {
                    return new long[] {Long.parseLong(f[0].substring(2), 16), Long.parseLong(f[1].substring(2), 16)};
                }
            }
        }
        throw new IOException("no " + REGION + " region in " + PMAP);
    }

    /** Copies count pixels (as little-endian ints, so 0xAARRGGBB) from slot, starting at pixel offset. */
    void read(int slot, int offset, int[] dst, int count) {
        Memory.peekIntArray(address + slot * frameBytes + offset * 4L, dst, 0, count, false);
    }

    /** Only once no thread can read any more: reading unmapped memory crashes the process. */
    void unmap() {
        try {
            Libcore.os.munmap(address, length);
        } catch (Exception e) {
            Log.w("can't unmap the camera RAM: " + e);
        }
    }
}
