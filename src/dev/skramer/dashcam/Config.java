package dev.skramer.dashcam;

/**
 * Settings: from --key=value arguments (the service script passes them), then the player app's
 * settings file, which only the app can write.
 */
final class Config implements Cloneable {
    private static final String APP_FILES = "/data/data/dev.skramer.dashcam.player/files/";

    /** Where segments go. Recording waits until this is on a mounted drive. */
    String outDir = "/storage/usb0/Dashcam";
    /** The mount point that must appear in /proc/mounts before recording starts. */
    String mount = "/storage/usb0";
    /** Writable by the recorder: heartbeat file and scratch files. */
    String stateDir = "/data/nemesis/dashcam";
    /** Recording size; the camera hardware scales its picture to it. */
    int width = 720;
    int height = 480;
    /** The camera delivers 60 fps; frames are dropped down to this rate. */
    int fps = 15;
    int bitrate = 1_500_000;
    int segmentSeconds = 60;
    /** Oldest segments are deleted to keep at least this share of the drive free. */
    int minFreePercent = 5;
    /** Set if recordings come out with red and blue swapped. */
    boolean swapUv = false;
    /** Debugging: run the camera without encoding, or the encoder on blank frames without the camera. */
    boolean debugNoEncode, debugNoCamera;
    /**
     * Other apps that play video: recording pauses while one of them is in front. With the
     * camera running, video playback on this unit shows corrupt frames (a Telechips driver
     * problem). The dashcam's own player pauses recording with its lease instead, so its other
     * screens can show the recorder at work.
     */
    String[] pauseFor = {"org.smarttube.stable"};
    /**
     * "Input device name:key code" of the button that saves the last saveSeconds when pressed
     * four times quickly (the steering wheel's mute); empty turns it off.
     */
    String saveButton = "daudio_wp_daudioplus_neev23_us:37";
    int saveSeconds = 120;
    /** Recording turned on in the app. */
    boolean enabled = true;
    /**
     * The app's settings (key=value lines: enabled, fps, bitrate, segment, minfree). The recorder
     * keeps a copy in stateDir, used while the app's file is missing: updating the package
     * reinstalls the app, which wipes its files.
     */
    String appConf = APP_FILES + "recorder.conf";
    /** Touched every couple of seconds while the player is open; recording pauses meanwhile. */
    String viewingLease = APP_FILES + "viewing";

    static Config parse(String[] args) {
        Config c = new Config();
        for (String a : args) {
            int eq = a.indexOf('=');
            if (!a.startsWith("--") || eq < 0) throw new IllegalArgumentException("bad argument: " + a);
            String k = a.substring(2, eq), v = a.substring(eq + 1);
            switch (k) {
                case "out": c.outDir = v; break;
                case "mount": c.mount = v; break;
                case "state": c.stateDir = v; break;
                case "width": c.width = Integer.parseInt(v); break;
                case "height": c.height = Integer.parseInt(v); break;
                case "fps": c.fps = Integer.parseInt(v); break;
                case "bitrate": c.bitrate = Integer.parseInt(v); break;
                case "segment": c.segmentSeconds = Integer.parseInt(v); break;
                case "min-free-percent": c.minFreePercent = Integer.parseInt(v); break;
                case "swap-uv": c.swapUv = Boolean.parseBoolean(v); break;
                case "debug-no-encode": c.debugNoEncode = Boolean.parseBoolean(v); break;
                case "debug-no-camera": c.debugNoCamera = Boolean.parseBoolean(v); break;
                case "save-button": c.saveButton = v; break;
                case "save-seconds": c.saveSeconds = Integer.parseInt(v); break;
                case "pause-for": c.pauseFor = v.isEmpty() ? new String[0] : v.split(","); break;
                case "app-conf": c.appConf = v; break;
                case "viewing-lease": c.viewingLease = v; break;
                default: throw new IllegalArgumentException("unknown setting: " + k);
            }
        }
        if (c.width < 64 || c.height < 64 || c.width % 2 != 0 || c.height % 2 != 0) {
            throw new IllegalArgumentException("bad size: " + c.width + "x" + c.height);
        }
        return c;
    }

    /**
     * Overrides with the app's settings file, if there is one. Out-of-range values are ignored:
     * the file is the app's, but a bad value shouldn't stop the dashcam.
     */
    Config withAppSettings() {
        Config c = copy();
        java.io.File f = new java.io.File(appConf);
        if (!f.isFile()) f = savedSettings();
        if (!f.isFile()) return c;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                try {
                    switch (k) {
                        case "enabled": c.enabled = Boolean.parseBoolean(v); break;
                        case "fps": c.fps = range(Integer.parseInt(v), 1, 30, c.fps); break;
                        case "bitrate": c.bitrate = range(Integer.parseInt(v), 250_000, 8_000_000, c.bitrate); break;
                        case "segment": c.segmentSeconds = range(Integer.parseInt(v), 10, 600, c.segmentSeconds); break;
                        case "minfree": c.minFreePercent = range(Integer.parseInt(v), 1, 50, c.minFreePercent); break;
                        default: break;
                    }
                } catch (NumberFormatException e) {
                    Log.w("ignoring " + k + "=" + v + " in " + appConf);
                }
            }
        } catch (java.io.IOException e) {
            Log.w("can't read " + appConf + ": " + e);
        }
        return c;
    }

    /** The recorder's copy of the app's settings. */
    java.io.File savedSettings() {
        return new java.io.File(stateDir, "settings");
    }

    private static int range(int v, int min, int max, int fallback) {
        return v >= min && v <= max ? v : fallback;
    }

    /** The settings a running recorder can't change: changing them means a restart. */
    String recordingSettings() {
        return fps + "/" + bitrate + "/" + segmentSeconds + "/" + minFreePercent;
    }

    Config copy() {
        try {
            return (Config) clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public String toString() {
        return width + "x" + height + "@" + fps + " " + bitrate / 1000 + " kbit/s, " + segmentSeconds
                + " s segments in " + outDir + " (keep " + minFreePercent + "% free)"
                + (swapUv ? ", swap-uv" : "")
                + (enabled ? "" : ", turned off");
    }
}
