package dev.skramer.dashcam;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ServiceManager;

/**
 * The car, through Hyundai's head-unit services (automotive.jar). Their interfaces aren't in the
 * SDK, so they're called by transaction number, as read from the firmware's IXxx$Stub classes.
 */
final class Car {
    private Car() {}

    /** RvmConstants.GdLnSta: the rear camera's own parking guide lines. */
    static final int GUIDE_LINES_OFF = 1, GUIDE_LINES_ON = 2;

    private static final String BCM = "com.hkmc.micom.bcm", BCM_IFACE = "com.hkmc.bcm.IBCMService";
    private static final int BCM_GET_IGN_STATUS = 0x11;
    private static final String PGS = "com.hkmc.micom.pgs", PGS_IFACE = "com.hkmc.pgs.IPgsService";
    private static final int PGS_REQUEST_GUIDE_LINE_STATE = 0x21, PGS_GET_RVM_GUIDE_LINE_STATE = 0x3b;
    private static final String POWER = "com.hkmc.micom.power", POWER_IFACE = "com.hkmc.power.IMicomPowerService";
    private static final int POWER_GET_POWER_STATUS = 0x1;
    private static final String SYSTEM = "com.hkmc.micom.system", SYSTEM_IFACE = "com.hkmc.micom.ISystemService";
    private static final int SYSTEM_GET_MICOM_BOOT_REASON = 0x27, SYSTEM_GET_MICOM_BOOT_MODE = 0x53;

    private static volatile IBinder bcmBinder;

    /** IBCMService.getIGNStatus(): the ignition is on. Null if the service can't say. */
    static Boolean ignitionOn() {
        Integer v = call(BCM, BCM_IFACE, BCM_GET_IGN_STATUS, null);
        return v == null ? null : v != 0;
    }

    /** IPgsService.getRvmGuideLineState(): GUIDE_LINES_ON or _OFF, or null if unknown. */
    static Integer guideLines() {
        return call(PGS, PGS_IFACE, PGS_GET_RVM_GUIDE_LINE_STATE, null);
    }

    /** IPgsService.requestGuideLineState(), the camera settings' parking guidance switch. */
    static boolean setGuideLines(int state) {
        return call(PGS, PGS_IFACE, PGS_REQUEST_GUIDE_LINE_STATE, state) != null;
    }

    /** For the log: why the head unit is up. */
    static String describe() {
        return "ignition " + ignitionOn() + ", power status " + call(POWER, POWER_IFACE, POWER_GET_POWER_STATUS, null)
                + ", boot mode " + call(SYSTEM, SYSTEM_IFACE, SYSTEM_GET_MICOM_BOOT_MODE, null)
                + ", boot reason " + call(SYSTEM, SYSTEM_IFACE, SYSTEM_GET_MICOM_BOOT_REASON, null);
    }

    /**
     * Calls a method that takes no argument or one int and returns an int or nothing. Returns
     * the result (0 for void), or null if the service is missing or the call failed.
     */
    private static Integer call(String service, String iface, int code, Integer arg) {
        IBinder b = BCM.equals(service) ? bcmBinder : null;
        if (b == null || !b.isBinderAlive()) {
            b = ServiceManager.getService(service);
            if (b == null) return null;
            if (BCM.equals(service)) bcmBinder = b;
        }
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            if (arg != null) data.writeInt(arg);
            if (!b.transact(code, data, reply, 0)) {
                if (BCM.equals(service)) bcmBinder = null;
                return null;
            }
            reply.readException();
            return reply.dataAvail() >= 4 ? reply.readInt() : 0;
        } catch (Throwable t) {
            if (BCM.equals(service)) bcmBinder = null;
            Log.w(service + " call " + code + " failed: " + t);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
