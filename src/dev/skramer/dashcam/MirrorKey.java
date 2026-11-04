package dev.skramer.dashcam;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import java.io.File;

/** Observes OEM long Mode; the firmware retains responsibility for normal short presses. */
final class MirrorKey extends Binder implements Runnable {
    private static final String LISTENER = "com.hkmc.mode.IModeKeyListener";
    private static final String MODE = "com.hkmc.mode.IModeManager";
    private final Handler main;
    private final File ready;
    private IBinder service;
    private final ModeGesture gesture;

    MirrorKey(Handler main, File state, final MirrorFeed mirror) {
        this.main = main;
        ready = new File(state, "mirror-key-ready");
        attachInterface(null, LISTENER);
        gesture = new ModeGesture(new ModeGesture.Actions() {
            public void shortPress() { /* The OEM dispatcher cycles media on the short release. */ }
            public void longPress() {
                if (mirror.closeViewer()) return;
                // A higher-priority OEM key owner (voice, valet, update, emergency) wins.
                if (!new File(ready.getParentFile(), "mirror-key-filtered").exists()
                        || RearCam.gear() == RearCam.GEAR_REVERSE || Boolean.FALSE.equals(Car.ignitionOn())) return;
                IBinder policy = ServiceManager.getService("com.hkmc.inputpolicy");
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    if (policy == null) return;
                    data.writeInterfaceToken("com.hkmc.server.IHkmcInputPolicyService");
                    if (!policy.transact(5, data, reply, 0)) return;
                    reply.readException();
                    if (reply.readInt() > 0 || MirrorFeed.appUid() < 0) return;
                    // Never launch while holding an OEM Binder dispatch thread or its key lock.
                    new Thread("open mirror") {
                        @Override public void run() {
                            try {
                                Process p = new ProcessBuilder("am", "start", "--user", "0", "-a",
                                        MirrorFeed.PACKAGE + ".TOGGLE", "-n",
                                        MirrorFeed.PACKAGE + "/.MainActivity").redirectErrorStream(true).start();
                                try (java.io.InputStream in = p.getInputStream()) {
                                    byte[] discard = new byte[256]; while (in.read(discard) >= 0) { }
                                }
                                p.waitFor();
                            } catch (Exception e) { Log.e("can't open mirror", e); }
                        }
                    }.start();
                } catch (Exception e) { Log.e("mirror key", e); }
                finally { data.recycle(); reply.recycle(); }
            }
        });
    }

    @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(LISTENER); return true; }
        if (code < 1 || code > 7) return super.onTransact(code, data, reply, flags);
        data.enforceInterface(LISTENER);
        if (code == 3) { // onAllKeyForService; onKeyForService would deliver only selected keys.
            final int action = data.readInt(), keyFlags = data.readInt(), device = data.readInt(), key = data.readInt();
            if (device == 5 && key == 254) main.post(new Runnable() {
                public void run() { gesture.key(action, keyFlags, device, key); }
            });
        }
        if (reply != null) reply.writeNoException();
        return true;
    }

    @Override public void run() {
        try {
            if (service == null || !service.pingBinder()) {
                ready.delete(); gesture.reset();
                IBinder found = ServiceManager.getService("com.hkmc.micom.mode");
                if (found == null) throw new IllegalStateException("no Mode service");
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(MODE); data.writeStrongBinder(this);
                    data.writeIntArray(new int[]{0});
                    data.writeString(getClass().getName()); data.writeString("android");
                    if (!found.transact(76, data, reply, 0)) throw new IllegalStateException("no key listener API");
                    reply.readException(); service = found;
                } finally { data.recycle(); reply.recycle(); }
            }
            // The root watchdog installs the scoped broadcast filter only while this is fresh.
            if (MirrorFeed.appUid() < 0) throw new IllegalStateException("mirror app not installed");
            boolean created = !ready.exists();
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(ready)) {
                out.write(Long.toString(android.os.SystemClock.uptimeMillis()).getBytes("US-ASCII"));
            }
            if (created) ready.setReadable(true, false);
        } catch (Exception e) { ready.delete(); service = null; Log.w("mirror Mode trigger unavailable: " + e); }
        main.postDelayed(this, 1000);
    }
}
