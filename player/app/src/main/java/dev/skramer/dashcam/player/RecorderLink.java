package dev.skramer.dashcam.player;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The recorder service (the dashcam NEMESIS package). What it's doing comes from its status
 * file. What it should do goes in files in this app's own files directory, which only this app
 * can write and the recorder reads: recorder.conf (settings) and debug (the camera debug
 * screen's request).
 */
final class RecorderLink {
    private static final File STATE = new File("/data/nemesis/dashcam");
    static final String CONF = "recorder.conf";
    /** The camera debug screen's request; see CameraDebugActivity. */
    static final String DEBUG = "debug";
    /** The heartbeat is rewritten every second while the recorder runs. */
    private static final long HEARTBEAT_STALE_MS = 10_000;

    /** recording, reversing, debug, reverse, caroff, off, nodrive, starting; or null if there's no status. */
    String state;
    long since;
    boolean running;
    String out = "/storage/usb0/Dashcam";
    String mount = "/storage/usb0";
    int fps = 15, bitrate = 1_500_000, segment = 60, minFreePercent = 5;

    static RecorderLink read() {
        RecorderLink r = new RecorderLink();
        File heartbeat = new File(STATE, "heartbeat");
        r.running = Math.abs(System.currentTimeMillis() - heartbeat.lastModified()) < HEARTBEAT_STALE_MS;
        try (BufferedReader in = new BufferedReader(new FileReader(new File(STATE, "status")))) {
            for (String line; (line = in.readLine()) != null; ) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq), v = line.substring(eq + 1);
                try {
                    switch (k) {
                        case "state": r.state = v; break;
                        case "since": r.since = Long.parseLong(v); break;
                        case "out": r.out = v; break;
                        case "mount": r.mount = v; break;
                        case "fps": r.fps = Integer.parseInt(v); break;
                        case "bitrate": r.bitrate = Integer.parseInt(v); break;
                        case "segment": r.segment = Integer.parseInt(v); break;
                        case "minfree": r.minFreePercent = Integer.parseInt(v); break;
                        default: break;
                    }
                } catch (NumberFormatException ignored) {
                    // keep the default
                }
            }
        } catch (IOException e) {
            // Recorder not installed, or an older one without a status file.
        }
        return r;
    }

    boolean enabled() {
        return !"off".equals(state);
    }

    /** What the app asks of the recorder. Unset fields keep the recorder's defaults. */
    static final class Settings {
        boolean enabled = true;
        int fps, bitrate, segment, minFree;

        /**
         * The saved settings; without a file (first run, or after a package update reinstalled
         * the app) whatever the recorder is using, so saving one change keeps the rest.
         */
        static Settings read(Context c) {
            Settings s = new Settings();
            File f = new File(c.getFilesDir(), CONF);
            if (!f.isFile()) {
                RecorderLink r = RecorderLink.read();
                if (r.state != null) {
                    s.enabled = r.enabled();
                    s.fps = r.fps;
                    s.bitrate = r.bitrate;
                    s.segment = r.segment;
                    s.minFree = r.minFreePercent;
                }
                return s;
            }
            try (BufferedReader in = new BufferedReader(new FileReader(f))) {
                for (String line; (line = in.readLine()) != null; ) {
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String k = line.substring(0, eq), v = line.substring(eq + 1);
                    try {
                        switch (k) {
                            case "enabled": s.enabled = Boolean.parseBoolean(v); break;
                            case "fps": s.fps = Integer.parseInt(v); break;
                            case "bitrate": s.bitrate = Integer.parseInt(v); break;
                            case "segment": s.segment = Integer.parseInt(v); break;
                            case "minfree": s.minFree = Integer.parseInt(v); break;
                            default: break;
                        }
                    } catch (NumberFormatException ignored) {
                        // keep unset
                    }
                }
            } catch (IOException e) {
                // nothing saved yet
            }
            return s;
        }

        /** Saves for the recorder, which picks it up within a second (and restarts for anything but on/off). */
        void write(Context c) throws IOException {
            StringBuilder b = new StringBuilder("enabled=").append(enabled).append('\n');
            if (fps > 0) b.append("fps=").append(fps).append('\n');
            if (bitrate > 0) b.append("bitrate=").append(bitrate).append('\n');
            if (segment > 0) b.append("segment=").append(segment).append('\n');
            if (minFree > 0) b.append("minfree=").append(minFree).append('\n');
            File dir = c.getFilesDir();
            File tmp = new File(dir, CONF + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(b.toString().getBytes(StandardCharsets.US_ASCII));
            }
            readableByRecorder(dir, tmp);
            if (!tmp.renameTo(new File(dir, CONF))) throw new IOException("can't replace " + CONF);
        }
    }

    /** The recorder runs as the system uid: it needs to pass through the files directory and read the file. */
    private static void readableByRecorder(File dir, File f) {
        dir.setExecutable(true, false);
        f.setReadable(true, false);
    }
}
