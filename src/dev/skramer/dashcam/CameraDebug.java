package dev.skramer.dashcam;

import android.os.IBinder;
import android.os.ServiceManager;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.regex.Pattern;

/**
 * The recorder's side of the app's camera debug screen. The screen keeps a request file fresh
 * (Config.debugLease): which camera and size to run, the last log line it has, and a report it
 * wants. While it's fresh the guard runs a debug session instead of recording, and this writes
 * the session's state and the recorder's new log lines to "debug" in the state directory, which
 * the screen reads. A report collects what the recorder can see that the app can't: the camera
 * list, the rear camera service's dump, the kernel log and the system uid's logcat (the stock
 * camera app runs as system too, so its log is in it).
 */
final class CameraDebug {
    /** The screen rewrites its request every 500 ms; one older than this has lapsed. */
    private static final long LEASE_MS = 3000;
    private static final long COMMAND_MS = 15_000;
    private static final Pattern LOG_KEEP = Pattern.compile(
            "camera|cam_|v4l|tcc|dashcam|rvm|avm|svm|pgs|lvds|cvbs|tw88|tw99|max9|vcrm|viqe|vioc|wdma|direct|mediaserver|gear|reverse",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROP_KEEP = Pattern.compile(
            "cam|svm|avm|rvm|pgs|lvds|video|display|tcc|ro\\.build|ro\\.product|option|variant|spec|model|region",
            Pattern.CASE_INSENSITIVE);

    /** What the screen asks for. */
    static final class Request {
        /** A camera id, or -1 for the one the stock view would open. */
        int camera = -1;
        int width, height;
        /** The last of the recorder's log lines the screen has. */
        long seen = -1;
        /** The report it wants, by id; empty for none. */
        String report = "";

        /** True if the recorder is running this request's session. */
        boolean matches(Recorder r) {
            return (camera < 0 || camera == r.cameraId()) && (width + "x" + height).equals(r.size());
        }
    }

    private final Config config;
    private final File lease, status, report;
    private String cameras, lastReport = "";
    private int type = Integer.MIN_VALUE;
    private volatile String reportDone = "";

    CameraDebug(Config config) {
        this.config = config;
        lease = new File(config.debugLease);
        status = new File(config.stateDir, "debug");
        report = new File(config.stateDir, "debug-report.txt");
    }

    /** The screen's request, or null if it isn't open. */
    Request read() {
        long m = lease.lastModified();
        if (m == 0 || Math.abs(System.currentTimeMillis() - m) >= LEASE_MS) return null;
        Request q = new Request();
        q.width = config.width;
        q.height = config.height;
        try (BufferedReader in = new BufferedReader(new FileReader(lease))) {
            for (String line; (line = in.readLine()) != null; ) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq), v = line.substring(eq + 1).trim();
                try {
                    switch (k) {
                        case "camera":
                            int c = v.equals("auto") ? -1 : Integer.parseInt(v);
                            if (c == -1 || c == 0 || c == 3) q.camera = c;  // the direct-display cameras
                            break;
                        case "size":
                            String[] wh = v.split("x");
                            int w = Integer.parseInt(wh[0]), h = Integer.parseInt(wh[1]);
                            if (w >= 64 && h >= 64 && w <= 1920 && h <= 1080 && w % 2 == 0 && h % 2 == 0) {
                                q.width = w;
                                q.height = h;
                            }
                            break;
                        case "seen": q.seen = Long.parseLong(v); break;
                        case "report": q.report = v; break;
                        default: break;
                    }
                } catch (RuntimeException ignored) {
                    // keep the default
                }
            }
        } catch (IOException e) {
            return null;
        }
        return q;
    }

    /** The camera the request means. */
    static int camera(Request q) {
        return q.camera >= 0 ? q.camera : RearCam.stockId();
    }

    /** Writes the state for the screen, and starts the report it asks for. */
    void update(Request q, String state, int gear, Recorder recorder) {
        if (type == Integer.MIN_VALUE) {
            type = RearCam.type();
            cameras = RearCam.describeCameras();
            Log.i("camera type " + type + "; " + cameras + "; the stock view opens camera " + RearCam.stockId());
        }
        if (!q.report.isEmpty() && !q.report.equals(lastReport)) {
            lastReport = q.report;
            final String id = q.report;
            new Thread("debug report") {
                @Override
                public void run() {
                    writeReport(id);
                }
            }.start();
        }
        StringBuilder b = new StringBuilder();
        b.append("time=").append(System.currentTimeMillis()).append("\nstate=").append(state)
                .append("\ngear=").append(gear).append("\ntype=").append(type)
                .append("\ncameras=").append(cameras).append("\nreport=").append(reportDone).append('\n');
        if (recorder.isActive()) b.append(recorder.debugStatus());
        b.append(Log.since(q.seen));
        write(status, b.toString());
    }

    /** The screen closed: nothing for the next one to mistake for current. */
    void end() {
        status.delete();
    }

    private void writeReport(String id) {
        long t0 = SystemClock.uptimeMillis();
        StringBuilder b = new StringBuilder();
        b.append("report=").append(id).append('\n');
        section(b, "recorder", config + "\ncamera type " + type + "\n" + cameras + "\nstock view opens camera "
                + RearCam.stockId() + "\nkernel " + firstLine("/proc/version"));
        section(b, "/proc/pmap", read("/proc/pmap"));
        section(b, "rear camera service", rearCameraDump());
        section(b, "getprop (camera, display and build lines)", filter(run("getprop"), PROP_KEEP, 400));
        section(b, "dmesg (camera lines)", filter(run("dmesg"), LOG_KEEP, 400));
        section(b, "logcat (camera lines; the system uid's own, as the recorder can see)",
                filter(run("logcat", "-d", "-v", "time"), LOG_KEEP, 2000));
        section(b, "camera overlay helper log (last 100 lines)", lastLines("/data/nemesis/dashcam-overlay.log", 100));
        section(b, "recorder log", Log.since(-1).replaceAll("(?m)^log\\.\\d+=", ""));
        b.append("collected in ").append(SystemClock.uptimeMillis() - t0).append(" ms\n");
        write(report, b.toString());
        reportDone = id;
        Log.i("debug report " + id + " written");
    }

    private static void section(StringBuilder b, String title, String body) {
        b.append("\n===== ").append(title).append(" =====\n").append(body);
        if (!body.endsWith("\n")) b.append('\n');
    }

    /** The last max lines matching keep. */
    private static String filter(String text, Pattern keep, int max) {
        ArrayDeque<String> lines = new ArrayDeque<>();
        for (String line : text.split("\n")) {
            if (!keep.matcher(line).find()) continue;
            lines.add(line);
            if (lines.size() > max) lines.removeFirst();
        }
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        return b.length() > 0 ? b.toString() : "(nothing)\n";
    }

    /** A command's output (and errors), cut off after COMMAND_MS. */
    private static String run(String... command) {
        try {
            final Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread("debug report " + command[0]) {
                @Override
                public void run() {
                    byte[] buf = new byte[16384];
                    try (InputStream in = p.getInputStream()) {
                        for (int n; (n = in.read(buf)) > 0; ) {
                            synchronized (out) {
                                out.write(buf, 0, n);
                            }
                        }
                    } catch (IOException ignored) {
                        // done
                    }
                }
            };
            reader.start();
            reader.join(COMMAND_MS);
            p.destroy();
            synchronized (out) {
                return new String(out.toByteArray(), StandardCharsets.UTF_8)
                        + (reader.isAlive() ? "\n(" + command[0] + " still going after " + COMMAND_MS / 1000 + " s; cut off)\n" : "");
            }
        } catch (Exception e) {
            return "(" + command[0] + ": " + e + ")\n";
        }
    }

    private String rearCameraDump() {
        IBinder service = ServiceManager.getService("media.rearcamera");
        if (service == null) return "(no media.rearcamera service)";
        File dump = new File(config.stateDir, "rearcamera-debug.txt");
        try {
            try (FileOutputStream out = new FileOutputStream(dump)) {
                service.dump(out.getFD(), new String[0]);
            }
            return read(dump.getPath());
        } catch (Throwable t) {
            return "(" + t + ")";
        }
    }

    private static String read(String path) {
        StringBuilder b = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new FileReader(path))) {
            for (String line; (line = in.readLine()) != null && b.length() < 256 * 1024; ) b.append(line).append('\n');
        } catch (IOException e) {
            b.append("(").append(e).append(")\n");
        }
        return b.toString();
    }

    /** The last max lines of a file. */
    private static String lastLines(String path, int max) {
        ArrayDeque<String> lines = new ArrayDeque<>();
        try (BufferedReader in = new BufferedReader(new FileReader(path))) {
            for (String line; (line = in.readLine()) != null; ) {
                lines.add(line);
                if (lines.size() > max) lines.removeFirst();
            }
        } catch (IOException e) {
            return "(" + e + ")\n";
        }
        StringBuilder b = new StringBuilder();
        for (String l : lines) b.append(l).append('\n');
        return b.length() > 0 ? b.toString() : "(empty)\n";
    }

    private static String firstLine(String path) {
        String s = read(path);
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** Replaces the file whole, readable by the app. */
    private static void write(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e("can't write " + tmp, e);
            return;
        }
        tmp.setReadable(true, false);
        if (!tmp.renameTo(f)) Log.w("can't replace " + f);
    }
}
