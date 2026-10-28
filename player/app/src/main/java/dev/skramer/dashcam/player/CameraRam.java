package dev.skramer.dashcam.player;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * A reserved physical memory region (from /proc/pmap) mapped read-only through /dev/admem, as the
 * recorder maps the camera's frames (see the recorder's CameraRam). Mapping and reading it have
 * no effect on the camera; any app may do it. Uses libcore's hidden Os and Memory through
 * reflection: there's no public API for either.
 */
final class CameraRam {
    private static final String PMAP = "/proc/pmap", DEVICE = "/dev/admem";

    final String name;
    final long physical, length;
    private final long address;
    private final Object os;
    private final Method peekInts, peekBytes, munmap;
    private volatile boolean mapped = true;

    private CameraRam(String name, long physical, long length, long address, Object os, Method peekInts, Method peekBytes,
            Method munmap) {
        this.name = name;
        this.physical = physical;
        this.length = length;
        this.address = address;
        this.os = os;
        this.peekInts = peekInts;
        this.peekBytes = peekBytes;
        this.munmap = munmap;
    }

    /** /proc/pmap, one "base size name" region per line. */
    static ArrayList<String[]> regions() throws IOException {
        ArrayList<String[]> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(PMAP))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.trim().split("\\s+");
                if (f.length == 3 && f[0].startsWith("0x") && f[1].startsWith("0x")) out.add(f);
            }
        }
        return out;
    }

    /** Maps the whole named region. */
    static CameraRam map(String name) throws Exception {
        for (String[] f : regions()) {
            if (!f[2].equals(name)) continue;
            long physical = Long.parseLong(f[0].substring(2), 16), length = Long.parseLong(f[1].substring(2), 16);
            if (length <= 0) throw new IOException(name + " is empty");
            Object os = Class.forName("libcore.io.Libcore").getField("os").get(null);
            Class<?> c = Class.forName("libcore.io.OsConstants"), o = Class.forName("libcore.io.Os");
            int rdonly = c.getField("O_RDONLY").getInt(null), sync = c.getField("O_SYNC").getInt(null);
            int prot = c.getField("PROT_READ").getInt(null), shared = c.getField("MAP_SHARED").getInt(null);
            FileDescriptor fd = (FileDescriptor) o.getMethod("open", String.class, int.class, int.class).invoke(os, DEVICE, rdonly | sync, 0);
            try {
                long a = (Long) o.getMethod("mmap", long.class, long.class, int.class, int.class, FileDescriptor.class, long.class)
                        .invoke(os, 0L, length, prot, shared, fd, physical);
                Class<?> memory = Class.forName("libcore.io.Memory");
                return new CameraRam(name, physical, length, a, os,
                        memory.getMethod("peekIntArray", long.class, int[].class, int.class, int.class, boolean.class),
                        memory.getMethod("peekByteArray", long.class, byte[].class, int.class, int.class),
                        o.getMethod("munmap", long.class, long.class));
            } finally {
                o.getMethod("close", FileDescriptor.class).invoke(os, fd);
            }
        }
        throw new IOException("no " + name + " region in " + PMAP);
    }

    /** Copies count pixels (little-endian ints, so BGRA bytes come out as 0xAARRGGBB) from byte offset. */
    void read(long offset, int[] dst, int dstOffset, int count) throws Exception {
        if (!mapped) throw new IOException(name + " is unmapped");
        if (offset < 0 || offset + count * 4L > length) throw new IOException("read past the end of " + name);
        peekInts.invoke(null, address + offset, dst, dstOffset, count, false);
    }

    /** Copies count bytes from byte offset. */
    void readBytes(long offset, byte[] dst, int count) throws Exception {
        if (!mapped) throw new IOException(name + " is unmapped");
        if (offset < 0 || offset + count > length) throw new IOException("read past the end of " + name);
        peekBytes.invoke(null, address + offset, dst, 0, count);
    }

    /** Only once no thread can read any more: reading unmapped memory crashes the app. */
    void unmap() {
        if (!mapped) return;
        mapped = false;
        try {
            munmap.invoke(os, address, length);
        } catch (Exception ignored) {
            // The app is closing the screen; the mapping goes with the process anyway.
        }
    }
}
