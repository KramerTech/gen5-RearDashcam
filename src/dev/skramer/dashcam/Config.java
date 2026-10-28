package dev.skramer.dashcam;

/**
 * Settings: from --key=value arguments (dashcam.conf; the service script passes them), then
 * the player app's settings file, which only the app can write and which only turns recording on
 * and off. The defaults are what's been tested: change them only to experiment.
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
    /**
     * The camera delivers 60 fps; frames are dropped down to this rate. Each frame kept costs a
     * copy out of uncached memory (about 23 ms at 720x480), so this is what sets the CPU load.
     */
    int fps = 20;
    /** About 1.35 GB an hour. */
    int bitrate = 3_000_000;
    int segmentSeconds = 60;
    /** Oldest segments are deleted to keep at least this share of the drive free. */
    int minFreePercent = 5;
    /** Flip the picture left to right: the backup camera sends a mirror image. */
    boolean mirror = true;
    /** Set if recordings come out with red and blue swapped. */
    boolean swapUv = false;
    /** Debugging: run the camera without encoding, or the encoder on blank frames without the camera. */
    boolean debugNoEncode, debugNoCamera;
    /** Turn off the camera's parking guide lines while recording (the camera draws them into its frames). */
    boolean hideGuideLines = true;
    /**
     * "Input device name:key code" of the button that saves the last saveSeconds when pressed
     * four times quickly (the steering wheel's mute); empty turns it off.
     */
    String saveButton = "daudio_wp_daudioplus_neev23_us:37";
    int saveSeconds = 120;
    /** Recording turned on in the app. */
    boolean enabled = true;
    /**
     * The app's settings (key=value lines; only enabled is read). The recorder
     * keeps a copy in stateDir, used while the app's file is missing: updating the package
     * reinstalls the app, which wipes its files.
     */
    String appConf = APP_FILES + "recorder.conf";
    /**
     * Rewritten every half second while the app's camera debug screen is open: what it asks for
     * (camera, size, report). Meanwhile the recorder runs a debug session instead of recording.
     */
    String debugLease = APP_FILES + "debug";

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
                case "mirror": c.mirror = Boolean.parseBoolean(v); break;
                case "swap-uv": c.swapUv = Boolean.parseBoolean(v); break;
                case "debug-no-encode": c.debugNoEncode = Boolean.parseBoolean(v); break;
                case "debug-no-camera": c.debugNoCamera = Boolean.parseBoolean(v); break;
                case "hide-guide-lines": c.hideGuideLines = Boolean.parseBoolean(v); break;
                case "save-button": c.saveButton = v; break;
                case "save-seconds": c.saveSeconds = Integer.parseInt(v); break;
                case "app-conf": c.appConf = v; break;
                case "debug-lease": c.debugLease = v; break;
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
            // Older apps also saved fps, bitrate, segment and minfree; those are fixed now.
            for (String line; (line = r.readLine()) != null; ) {
                if (line.trim().startsWith("enabled=")) c.enabled = Boolean.parseBoolean(line.trim().substring(8).trim());
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
                + " s segments in " + outDir + " (keep " + minFreePercent + "% free)" + (mirror ? ", mirrored" : "")
                + (swapUv ? ", swap-uv" : "")
                + (enabled ? "" : ", turned off");
    }
}
