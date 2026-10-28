package dev.skramer.dashcam.player;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.Parcel;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Playback is for when the car is parked. Gen5W's com.hkmc.regulation service says when the car
 * is out of park, but leaves it to each app to obey. Every screen checks it when created and
 * resumed: if the car is regulated, Hyundai's own "not available while driving" popup is shown,
 * every screen is finished and the process exits. A receiver does the same when the car leaves
 * park (popup only if the app was in use).
 */
final class DrivingGuard extends BroadcastReceiver {
    private static final String SERVICE = "com.hkmc.regulation";
    private static final String DESCRIPTOR = "com.hkmc.regulation.IRegulationManager";
    private static final int TX_GET_STATUS = 1;
    private static final int TX_SHOW_POPUP = 8;
    private static final String ENTER = "android.mobis.regulation.ACTION_ENTER_REGULATION";

    private static final ArrayList<WeakReference<Activity>> activities = new ArrayList<>();
    private static DrivingGuard receiver;

    static void onCreate(Activity activity) {
        activities.add(new WeakReference<>(activity));
        if (receiver == null && service() != null) {
            receiver = new DrivingGuard();
            activity.getApplicationContext().registerReceiver(receiver, new IntentFilter(ENTER));
        }
        check(activity);
    }

    /** Closes the app if the car is out of park. Returns true if it did. */
    static boolean check(Activity activity) {
        if (status() == 0) return false;
        stop(activity.getString(R.string.driving), activity.getPackageName(), true);
        return true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        ActivityManager.RunningAppProcessInfo me = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(me);
        boolean inUse = me.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND;
        stop(context.getString(R.string.driving), context.getPackageName(), inUse);
    }

    private static void stop(String message, String pkg, boolean popup) {
        if (popup) {
            try {
                call(TX_SHOW_POPUP, message, pkg);
            } catch (Throwable ignored) {
                // closing is what matters
            }
        }
        for (int i = activities.size() - 1; i >= 0; i--) {
            Activity a = activities.get(i).get();
            if (a != null) a.finishAffinity();
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private static IBinder service() {
        try {
            return (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** IRegulationManager.getStatus(): 0 while parked. 0 too where there's no such service. */
    private static int status() {
        try {
            return call(TX_GET_STATUS);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int call(int code, String... args) throws Exception {
        IBinder b = service();
        if (b == null) return 0;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            for (String s : args) data.writeString(s);
            b.transact(code, data, reply, 0);
            reply.readException();
            return code == TX_GET_STATUS ? reply.readInt() : 0;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
