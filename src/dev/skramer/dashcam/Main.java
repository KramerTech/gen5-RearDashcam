package dev.skramer.dashcam;

import android.os.Handler;
import android.os.Looper;

/**
 * Rear dashcam for Gen5W head units. Runs headless under app_process as the system uid
 * (the NEMESIS service starts it): records the backup camera to the USB drive and hands the
 * camera to the stock reverse view whenever it wants it.
 */
public final class Main {
    private Main() {}

    /** The system uid: may use the camera (as package "android") and dump services. */
    private static final int SYSTEM_UID = 1000;
    /** sdcard_r: files on the USB drive's FUSE mount are root:sdcard_r 0770. */
    private static final int SDCARD_R_GID = 1028;

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        final Recorder[] recorder = new Recorder[1];
        SaveButton saveButton = SaveButton.open(config, new Runnable() {  // needs root; see SaveButton
            @Override
            public void run() {
                if (recorder[0] != null) recorder[0].saveRecent();
            }
        });
        dropRoot();
        config = config.withAppSettings();
        try {
            android.os.Process.class.getMethod("setArgV0", String.class).invoke(null, "dashcam");
        } catch (Throwable t) {
            Log.w("can't set the process name: " + t);
        }
        Looper.prepareMainLooper();
        Log.i("starting: " + config);
        Log.i("car: " + Car.describe());
        recorder[0] = new Recorder(config, new Handler());
        new Guard(config, recorder[0]).start();
        if (saveButton != null) saveButton.start();
        Looper.loop();
    }

    /**
     * NEMESIS starts services as root with no supplementary groups, so the recorder switches
     * itself: group sdcard_r to write to the drive, then the system uid, which drops root's
     * capabilities. (A recorder started from an ADB shell already runs as system with the
     * shell's groups and is left as it is.)
     */
    private static void dropRoot() throws Exception {
        Object os = Class.forName("libcore.io.Libcore").getField("os").get(null);
        if ((Integer) os.getClass().getMethod("getuid").invoke(os) != 0) return;
        os.getClass().getMethod("setgid", int.class).invoke(os, SDCARD_R_GID);
        os.getClass().getMethod("setuid", int.class).invoke(os, SYSTEM_UID);
    }
}
