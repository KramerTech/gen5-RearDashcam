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
 * {@link RearCam}), whether for us or for the stock reverse view, the kernel driver has the
 * hardware write each frame into a ring of four buffers at the start of the reserved
 * "rearcamera" region: width × height BGRA pixels each, one after the other, rows packed. The
 * display shows them from there.
 * <p>
 * The region is mapped read-only through /dev/admem, whose open and mmap have no side effects
 * (unlike opening /dev/video0, which takes the camera driver's lock). Reading it never disturbs
 * the camera or the reverse view; while nothing runs the camera it just holds the last frames.
 * It's mapped once, whole, and stays mapped: nothing can read it after it's gone.
 */
final class CameraRam {
    static final int SLOTS = 4;
    private static final String PMAP = "/proc/pmap", REGION = "rearcamera", DEVICE = "/dev/admem";

    final long length;
    private final long address;

    private CameraRam(long address, long length) {
        this.address = address;
        this.length = length;
    }

    /** Maps the region; fails if it's missing. */
    static CameraRam map() throws Exception {
        long[] region = region();
        FileDescriptor fd = Libcore.os.open(DEVICE, OsConstants.O_RDONLY | OsConstants.O_SYNC, 0);
        try {
            long a = Libcore.os.mmap(0, region[1], OsConstants.PROT_READ, OsConstants.MAP_SHARED, fd, region[0]);
            Log.i(String.format(java.util.Locale.US, "camera RAM: %s at 0x%x, 0x%x bytes", REGION, region[0], region[1]));
            return new CameraRam(a, region[1]);
        } finally {
            Libcore.os.close(fd);
        }
    }

    /** True if a ring of four width × height frames fits. */
    boolean fits(int width, int height) {
        return (long) width * height * 4 * SLOTS <= length;
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

    /** The mapped address of byte offset, for native code, if count bytes from there are in the region. */
    long address(long offset, long count) {
        if (offset < 0 || offset + count > length) throw new IndexOutOfBoundsException("past the end of " + REGION);
        return address + offset;
    }

    /** Copies count pixels (as little-endian ints, so 0xAARRGGBB) from byte offset into dst at dstOffset. */
    void read(long offset, int[] dst, int dstOffset, int count) {
        if (offset < 0 || offset + count * 4L > length) throw new IndexOutOfBoundsException("past the end of " + REGION);
        Memory.peekIntArray(address + offset, dst, dstOffset, count, false);
    }
}
