package dev.skramer.dashcam;

import android.os.SystemClock;
import java.io.FileDescriptor;
import libcore.io.Libcore;
import libcore.io.OsConstants;
import libcore.util.MutableInt;

/**
 * The car's early camera: while Android starts, the SoC's Cortex-M shows the backup camera itself,
 * on the same display path the camera HAL uses. The stock reverse app ends it, either by taking it
 * over (when the head unit starts in reverse) or by stopping it, which it does some 10 s after the
 * head unit is up, and not at all for a while after a wake with the car off (unlocking the door).
 * Opened before that, the camera sets up the display path under the running early camera (the
 * kernel logs "RDMA1 was recovered" every frame), and reverse then gets the head unit hard-reset
 * by the car. So before opening the camera, the recorder stops it the way the stock app does
 * (RearMode.setEarlyCameraStop): clears the handover property, stops the early camera (up to three
 * tries), then unloads the Cortex-M.
 * <p>
 * The kernel's knock tells whether the Cortex-M is still loaded (1); once the stock app has stopped
 * or taken over the early camera it's unloaded (0) and there's nothing to do. Nothing is sent to
 * an unloaded Cortex-M. RearCamera.knockEarlyCamera() can't be used for this: it returns the
 * argument it passed (0), never the ioctl's return value, which is where the kernel answers.
 * <p>
 * When the head unit starts in reverse, the stock view takes the early camera over. If it finds
 * our camera open there instead, it starts the handover and then neither stops nor exits the
 * early camera, so stopping it ourselves matters after every hand-back too.
 */
final class EarlyCamera {
    private EarlyCamera() {}

    private static final String CLASS = "android.hardware.RearCamera";
    private static final String DEVICE = "/dev/tcc_cm_ctrl";
    /** tcc_cm_ctrl's ioctl that returns 1 while the Cortex-M is loaded and 0 once it isn't; it changes nothing. */
    private static final int IOCTL_KNOCK = 4;
    private static final String HANDOVER_PROPERTY = "persist.sys.earlycam.handover";
    private static final int STOP_TRIES = 3;
    /** After unloading it, before the camera is opened: the stock app opens it a moment later too. */
    private static final long SETTLE_MS = 500;

    /** For checking on a unit: prints the knock's answer (1: the early camera is still up). Changes nothing. */
    public static void main(String[] args) throws Exception {
        System.out.println(knock());
    }

    /** Stops the early camera if it's still up, as the stock app does. Throws if it can't be stopped. */
    static void stop() throws Exception {
        int knock = knock();
        if (knock != 1) {
            if (knock != 0) Log.w("early camera: the knock returned " + knock + "; leaving it");
            return;
        }
        Log.i("early camera still up; stopping it as the stock app does");
        Class<?> t = Class.forName(CLASS);
        Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class)
                .invoke(null, HANDOVER_PROPERTY, "");
        int r = -1;
        for (int i = 0; i < STOP_TRIES && r == -1; i++) r = (Integer) t.getMethod("stopEarlyCamera").invoke(null);
        if (r == -1) throw new IllegalStateException("stopEarlyCamera failed");
        int off = (Integer) t.getMethod("disableCMControl").invoke(null);
        Log.i("early camera stopped (last gear " + r + "), Cortex-M unloaded (" + off + ")");
        SystemClock.sleep(SETTLE_MS);
    }

    /** The kernel's knock: 1 while the Cortex-M is loaded, 0 once it's unloaded. */
    private static int knock() throws Exception {
        FileDescriptor fd = Libcore.os.open(DEVICE, OsConstants.O_RDWR, 0);
        try {
            return Libcore.os.ioctlInt(fd, IOCTL_KNOCK, new MutableInt(0));
        } finally {
            Libcore.os.close(fd);
        }
    }
}
