package dev.skramer.dashcam;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * The per-pixel work in C with NEON (native/dashcam.c, libdashcam.so beside dashcam.jar): this
 * unit runs Dalvik, whose JIT is about ten times slower at it. If the library can't be loaded,
 * the recorder does the same in Java.
 */
final class Native {
    private Native() {}

    static final boolean loaded = load();

    private static boolean load() {
        String cp = System.getProperty("java.class.path", "");
        if (cp.isEmpty()) cp = System.getenv("CLASSPATH") == null ? "" : System.getenv("CLASSPATH");
        File lib = new File(new File(cp.split(":")[0]).getParentFile(), "libdashcam.so");
        try {
            System.load(lib.getPath());
            Log.i("native conversion: " + lib);
            return true;
        } catch (Throwable t) {
            Log.w("no native conversion (" + t + "); converting in Java");
            return false;
        }
    }

    /**
     * argb (width × height) to I420 in out, a direct buffer of at least width × height × 3/2
     * bytes: Y first, U at uOffset, V at vOffset; mirrored left to right if mirror.
     */
    static native void toI420(int[] argb, int width, int height, ByteBuffer out, int uOffset, int vOffset, boolean mirror);

    /** Hash one strip per ring buffer, batching the uncached reads and pixel loop in native code. */
    static native void hashStrips(long address, long frameBytes, int count, int[] hashes);

    /** From the frame at address (srcWidth pixels a row), the pixel at each of rows × cols, into dst. */
    static native void copyScaled(long address, int srcWidth, int[] rows, int[] cols, int[] dst);
}
