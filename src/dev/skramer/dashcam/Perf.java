package dev.skramer.dashcam;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CPU use since the last call, from /proc: this process and its busiest threads, mediaserver
 * (which runs the camera service and the H.264 encoder) and the whole system. Process and thread
 * figures are percent of one core, as top shows them; the system's is percent of all cores.
 * The GPU isn't measured: this kernel exposes no load counters for it, and recording doesn't use
 * it (direct display uses the display hardware, encoding the video codec's).
 */
final class Perf {
    /** USER_HZ: /proc counts CPU time in hundredths of a second. */
    private static final double TICKS_PER_SEC = 100;

    private final int cores = Runtime.getRuntime().availableProcessors();
    private long lastWallNs, lastSelf, lastMedia, lastSysBusy, lastSysTotal;
    private final Map<String, Long> lastThreads = new HashMap<>();
    private int mediaPid = -1;

    /** A line like "recorder 38% (camera copy 12%, ...), mediaserver 11%, system 52% of 4 cores"; null on the first call. */
    String sample() {
        long now = System.nanoTime();
        long self = ticks("/proc/self/stat");
        if (mediaPid < 0) mediaPid = findPid("/system/bin/mediaserver");
        long media = mediaPid > 0 ? ticks("/proc/" + mediaPid + "/stat") : -1;
        long[] sys = system();
        Map<String, Long> threads = threads();
        String line = null;
        if (lastWallNs != 0) {
            double secs = (now - lastWallNs) / 1e9;
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.US, "recorder %.0f%%", pct(self - lastSelf, secs)));
            // The threads that used the most, by name (threads of the same name added up).
            Map<String, Long> used = new HashMap<>();
            for (Map.Entry<String, Long> e : threads.entrySet()) {
                Long before = lastThreads.get(e.getKey());  // none: a thread started since
                String name = e.getKey().substring(e.getKey().indexOf(' ') + 1);
                Long sum = used.get(name);
                used.put(name, (sum == null ? 0 : sum) + e.getValue() - (before == null ? 0 : before));
            }
            StringBuilder t = new StringBuilder();
            for (int i = 0; i < 4 && !used.isEmpty(); i++) {
                String top = null;
                for (Map.Entry<String, Long> e : used.entrySet()) if (top == null || e.getValue() > used.get(top)) top = e.getKey();
                double p = pct(used.remove(top), secs);
                if (p < 1) break;
                t.append(t.length() == 0 ? "" : ", ").append(String.format(Locale.US, "%s %.0f%%", top, p));
            }
            if (t.length() > 0) b.append(" (").append(t).append(')');
            if (media >= 0 && lastMedia >= 0) b.append(String.format(Locale.US, ", mediaserver %.0f%%", pct(media - lastMedia, secs)));
            if (sys != null && lastSysTotal != 0 && sys[1] > lastSysTotal) {
                b.append(String.format(Locale.US, ", system %.0f%% of %d cores",
                        100.0 * (sys[0] - lastSysBusy) / (sys[1] - lastSysTotal), cores));
            }
            String load = read("/proc/loadavg");
            if (load != null) b.append(", load ").append(load.split(" ")[0]);
            line = b.toString();
        }
        lastWallNs = now;
        lastSelf = self;
        lastMedia = media;
        if (sys != null) {
            lastSysBusy = sys[0];
            lastSysTotal = sys[1];
        }
        lastThreads.clear();
        lastThreads.putAll(threads);
        return line;
    }

    private static double pct(long ticks, double secs) {
        return 100.0 * ticks / TICKS_PER_SEC / secs;
    }

    /** utime + stime of a /proc/.../stat file, or -1. */
    private static long ticks(String path) {
        String s = read(path);
        if (s == null) return -1;
        String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");  // after "pid (name) "
        try {
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);  // fields 14 and 15
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** "tid name" to CPU ticks, for each of this process's threads. */
    private static Map<String, Long> threads() {
        Map<String, Long> out = new HashMap<>();
        String[] tids = new File("/proc/self/task").list();
        if (tids == null) return out;
        for (String tid : tids) {
            String s = read("/proc/self/task/" + tid + "/stat");
            if (s == null) continue;
            String name = s.substring(s.indexOf('(') + 1, s.lastIndexOf(')'));
            long t = ticks("/proc/self/task/" + tid + "/stat");
            if (t >= 0) out.put(tid + " " + name, t);
        }
        return out;
    }

    /** Busy and total jiffies of all CPUs, from /proc/stat's first line, or null. */
    private static long[] system() {
        String s = read("/proc/stat");
        if (s == null || !s.startsWith("cpu ")) return null;
        String[] f = s.substring(4, s.indexOf('\n')).trim().split("\\s+");
        long total = 0, idle = 0;
        for (int i = 0; i < f.length; i++) {
            long v = Long.parseLong(f[i]);
            total += v;
            if (i == 3 || i == 4) idle += v;  // idle, iowait
        }
        return new long[] {total - idle, total};
    }

    private static int findPid(String cmd) {
        String[] pids = new File("/proc").list();
        if (pids == null) return -1;
        for (String p : pids) {
            if (p.isEmpty() || !Character.isDigit(p.charAt(0))) continue;
            String c = read("/proc/" + p + "/cmdline");
            if (c != null && c.startsWith(cmd)) return Integer.parseInt(p);
        }
        return -1;
    }

    private static String read(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] b = new byte[4096];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.US_ASCII) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
