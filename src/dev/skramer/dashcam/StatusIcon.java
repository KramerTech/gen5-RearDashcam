package dev.skramer.dashcam;

import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.IBinder;
import android.os.ServiceManager;

/**
 * The red dot among the status bar's icons (Bluetooth, signal, Wi-Fi) while recording. Hyundai's
 * SystemUI shows icons set through the standard IStatusBarService, but only in the slots of
 * framework-res config_statusBarIcons; "tmc" is the European navigation's traffic-message icon,
 * which SystemUI sets only on EUNAVI_TMC_TPEG broadcasts, never sent on US cars. The image is
 * the player app's drawable status_recording (51x36 px like the stock icons), loaded by
 * SystemUI from that package. Setting icons takes STATUS_BAR, which the system uid has.
 */
final class StatusIcon {
    private static final String SLOT = "tmc";
    private static final String PACKAGE = "dev.skramer.dashcam.player";
    private static final String DRAWABLE = "status_recording";

    private Object bar;
    private boolean set, failed;
    /** What the status bar was last told, or null before the first call. */
    private Boolean shown;

    /**
     * Shows or hides the dot; calls the status bar only on a change. The first call always goes
     * through, so a dot left by a recorder that died is taken down.
     */
    void show(boolean on) {
        if (shown != null && shown == on) return;
        try {
            if (bar == null) {
                bar = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, ServiceManager.getService("statusbar"));
                if (bar == null) return;  // SystemUI isn't up yet; try again on the next call
            }
            if (on && !set) {
                int id = iconId();
                if (id == 0) throw new IllegalStateException(PACKAGE + " has no drawable " + DRAWABLE);
                bar.getClass().getMethod("setIcon", String.class, String.class, int.class, int.class, String.class)
                        .invoke(bar, SLOT, PACKAGE, id, 0, "Dashcam recording");
                set = true;
            }
            bar.getClass().getMethod("setIconVisibility", String.class, boolean.class).invoke(bar, SLOT, on);
            shown = on;
            failed = false;
        } catch (Throwable t) {
            if (!failed) Log.w("can't " + (on ? "show" : "hide") + " the status-bar dot: " + t);
            failed = true;
            bar = null;
            set = false;
        }
    }

    /** The drawable's resource id in the installed player app, or 0. */
    private static int iconId() throws Exception {
        Object pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
        ApplicationInfo app = (ApplicationInfo) pm.getClass()
                .getMethod("getApplicationInfo", String.class, int.class, int.class).invoke(pm, PACKAGE, 0, 0);
        if (app == null) return 0;
        AssetManager assets = AssetManager.class.getConstructor().newInstance();
        try {
            AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, app.sourceDir);
            return new Resources(assets, null, null).getIdentifier(DRAWABLE, "drawable", PACKAGE);
        } finally {
            assets.close();
        }
    }
}
