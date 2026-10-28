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
    int fps = 20, bitrate = 3_000_000, segment = 60, minFreePercent = 5;

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

    /** What the app asks of the recorder: recording on or off. Everything else is fixed. */
    static final class Settings {
        boolean enabled = true;

        /**
         * The saved setting; without a file (first run, or after a package update reinstalled
         * the app) whatever the recorder is doing.
         */
        static Settings read(Context c) {
            Settings s = new Settings();
            File f = new File(c.getFilesDir(), CONF);
            if (!f.isFile()) {
                RecorderLink r = RecorderLink.read();
                if (r.state != null) s.enabled = r.enabled();
                return s;
            }
            try (BufferedReader in = new BufferedReader(new FileReader(f))) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (line.startsWith("enabled=")) s.enabled = Boolean.parseBoolean(line.substring(8).trim());
                }
            } catch (IOException e) {
                // nothing saved yet
            }
            return s;
        }

        /** Saves for the recorder, which picks it up within a second. */
        void write(Context c) throws IOException {
            StringBuilder b = new StringBuilder("enabled=").append(enabled).append('\n');
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
