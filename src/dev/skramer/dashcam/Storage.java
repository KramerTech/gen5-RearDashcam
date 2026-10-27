package dev.skramer.dashcam;

import android.os.StatFs;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/** The recording folder on the USB drive: mount check, segment names, deleting old segments. */
final class Storage {
    private final Config config;
    private final File dir;
    private final SimpleDateFormat names = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    Storage(Config config) {
        this.config = config;
        dir = new File(config.outDir);
    }

    private void ensureDir() throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
    }

    /** True if the drive is mounted. Without this check, recording would fill internal storage. */
    boolean mounted() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/mounts"))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split(" ");
                if (f.length > 1 && f[1].equals(config.mount)) return true;
            }
        } catch (IOException e) {
            Log.e("can't read /proc/mounts", e);
        }
        return false;
    }

    /** A new segment file, named for the current time. */
    File newSegment() throws IOException {
        ensureDir();
        String base = names.format(new Date());
        File f = new File(dir, base + ".ts");
        for (int i = 1; f.exists(); i++) f = new File(dir, base + "_" + i + ".ts");
        return f;
    }

    /** Deletes the oldest segments until the configured share of the drive is free. */
    void makeRoom() throws IOException {
        ensureDir();
        StatFs fs = new StatFs(dir.getPath());
        long want = fs.getTotalBytes() / 100 * config.minFreePercent;
        while (fs.getAvailableBytes() < want) {
            File[] segments = dir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File d, String name) {
                    return name.endsWith(".ts");
                }
            });
            if (segments == null || segments.length == 0) {
                Log.w("drive is full and there are no segments left to delete");
                return;
            }
            Arrays.sort(segments);
            File oldest = segments[0];
            if (oldest.delete()) Log.i("deleted " + oldest.getName() + " to make room");
            else {
                Log.w("can't delete " + oldest);
                return;
            }
            fs.restat(dir.getPath());
        }
    }
}
