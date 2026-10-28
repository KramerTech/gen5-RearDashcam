package dev.skramer.dashcam;

import android.os.ServiceManager;
import android.os.SystemClock;

/**
 * A drive in at boot brings its status-bar notices ("Reading USB", "No Supported Files") while
 * Android is still starting, seconds before the recorder, which waits for boot. recorder.sh runs
 * this first, in the background: as soon as the notification service exists, it takes the
 * notices down for a minute, then exits. Mounts after that are the recorder's (Guard).
 */
public final class UsbNotices {
    private UsbNotices() {}

    private static final long WAIT_MS = 120000, HIDE_MS = 60000, EVERY_MS = 250;

    public static void main(String[] args) throws Exception {
        Main.dropRoot();
        long t0 = SystemClock.uptimeMillis();
        while (ServiceManager.getService("notification") == null) {
            if (SystemClock.uptimeMillis() - t0 > WAIT_MS) return;
            SystemClock.sleep(EVERY_MS);
        }
        long t1 = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - t1 < HIDE_MS) {
            if (!Car.hideStorageNotification()) {
                Log.w("boot: can't take down the USB status-bar notices");
                return;
            }
            SystemClock.sleep(EVERY_MS);
        }
    }
}
