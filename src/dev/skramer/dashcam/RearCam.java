package dev.skramer.dashcam;

import android.os.Looper;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * The backup camera through the vendor's hidden android.hardware.RearCamera, the same camera
 * service and HAL the stock reverse view uses. Started with no preview surface or callbacks, the
 * HAL runs its direct-display path (startPreviewDD): the hardware deinterlaces and scales the
 * picture to the preview size and writes it to {@link CameraRam}. It also puts it on a display
 * plane, under the home screen, where it doesn't show.
 * <p>
 * Never give it a preview surface: setPreviewTexture then startPreview hangs this HAL, and the
 * camera service then turns the reverse view away until the head unit restarts.
 * <p>
 * RearCamera.open() would pass ActivityThread's package name, null in a process that isn't an
 * app, and the camera service's AppOps check needs a package of the caller's uid; so it's built
 * by hand, as open() does, with "android", the system uid's package. Its callbacks arrive on the
 * calling thread's looper.
 */
final class RearCam {
    private static final String CLASS = "android.hardware.RearCamera";
    /** The stock reverse view's parameters, with our size. A zero crop makes the HAL log an error it ignores. */
    private static final String PARAMETERS = "crop-size=0x0;preview-overlay=140x0;preview-size=%dx%d;crop-position=0,0";

    private final Class<?> type;
    private final Object camera;
    private boolean previewing;

    private RearCam(Class<?> type, Object camera) {
        this.type = type;
        this.camera = camera;
    }

    /** Opens the camera; onError runs (on this thread's looper) if the camera service reports an error or dies. */
    static RearCam open(int id, final Runnable onError) throws Exception {
        Class<?> t = Class.forName(CLASS);
        Constructor<?> k = t.getDeclaredConstructor();
        k.setAccessible(true);
        Object c = k.newInstance();
        set(t, c, "mCameraId", id);
        Constructor<?> handler = Class.forName(CLASS + "$EventHandler_").getDeclaredConstructor(t, t, Looper.class);
        handler.setAccessible(true);
        set(t, c, "mEventHandler", handler.newInstance(c, c, Looper.myLooper()));
        long t0 = SystemClock.uptimeMillis();
        try {
            call(t, c, "native_setup", new Class<?>[] {Object.class, int.class, String.class},
                    new WeakReference<>(c), id, "android");
        } catch (Exception e) {
            Log.i("camera " + id + ": native_setup failed after " + (SystemClock.uptimeMillis() - t0) + " ms: " + e);
            throw e;
        }
        Log.i("camera " + id + ": native_setup " + (SystemClock.uptimeMillis() - t0) + " ms");
        set(t, c, "initialized", true);
        RearCam cam = new RearCam(t, c);
        try {
            Class<?> callback = Class.forName(CLASS + "$ErrorCallback");
            Object proxy = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[] {callback},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object self, Method m, Object[] args) {
                            if (m.getName().equals("onError")) {
                                Log.w("camera error " + args[0]);
                                onError.run();
                                return null;
                            }
                            if (m.getName().equals("equals")) return self == args[0];
                            if (m.getName().equals("hashCode")) return System.identityHashCode(self);
                            return m.getName().equals("toString") ? "camera error callback" : null;
                        }
                    });
            call(t, c, "setErrorCallback", new Class<?>[] {callback}, proxy);
        } catch (Throwable e) {
            Log.w("no camera error callback: " + e);
        }
        return cam;
    }

    /**
     * The camera RearCamera.open() would pick, as the stock reverse view opens it: the first
     * facing back. Only the direct-display cameras (0 and 3) can run without a preview surface;
     * anything else, or no answer, gives 0.
     */
    static int stockId() {
        int id = backFacing();
        if (id == 0 || id == 3) return id;
        if (id > 0) Log.w("the back camera is " + id + ", which has no direct display; using 0");
        return 0;
    }

    /** The first camera facing back, or -1. */
    private static int backFacing() {
        try {
            for (int id = 0, n = cameras(); id < n; id++) {
                Object info = info(id);
                if (info != null && info.getClass().getField("facing").getInt(info) == 0) return id;
            }
        } catch (Throwable t) {
            Log.w("can't list the cameras: " + t);
        }
        return -1;
    }

    private static int cameras() throws Exception {
        return (Integer) call(Class.forName(CLASS), null, "getNumberOfCameras", new Class<?>[0]);
    }

    /** RearCamera.getCameraInfo(id), or null if it fails. */
    private static Object info(int id) throws Exception {
        Class<?> t = Class.forName(CLASS + "$RearCameraInfo");
        Object info = t.newInstance();
        int r = (Integer) call(Class.forName(CLASS), null, "getCameraInfo", new Class<?>[] {int.class, t}, id, info);
        return r == 0 ? info : null;
    }

    /** "id: facing F, orientation O" for each camera, for the debug report. */
    static String describeCameras() {
        StringBuilder b = new StringBuilder();
        try {
            int n = cameras();
            b.append(n).append(" cameras");
            for (int id = 0; id < n; id++) {
                Object info = info(id);
                b.append("; ").append(id).append(": ");
                if (info == null) {
                    b.append("no info");
                } else {
                    b.append("facing ").append(info.getClass().getField("facing").getInt(info))
                            .append(", orientation ").append(info.getClass().getField("orientation").getInt(info));
                }
            }
        } catch (Throwable t) {
            b.append(" (").append(t).append(')');
        }
        return b.append(" (facing 0 back, 1 front, 2 aux video, 3 DVRS)").toString();
    }

    /** RearCamera.getCameraType(): how this car's camera is connected (0 analog), or -1. */
    static int type() {
        try {
            return (Integer) call(Class.forName(CLASS), null, "getCameraType", new Class<?>[0]);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Sets the parameters, without reading them first, and starts the direct-display path. */
    void start(int width, int height) throws Exception {
        String p = String.format(java.util.Locale.US, PARAMETERS, width, height);
        timed("native_setParameters(" + p + ")", "native_setParameters", new Class<?>[] {String.class}, p);
        previewing = true;
        timed("startPreview", "startPreview", new Class<?>[0]);
    }

    /** Calls a method of the camera, logging how long it took and what it returned or threw. */
    private Object timed(String what, String name, Class<?>[] types, Object... args) throws Exception {
        long t0 = SystemClock.uptimeMillis();
        try {
            Object r = call(type, camera, name, types, args);
            Log.i("camera: " + what + " " + (SystemClock.uptimeMillis() - t0) + " ms" + (r != null ? ", returned " + r : ""));
            return r;
        } catch (Exception e) {
            Log.i("camera: " + what + " failed after " + (SystemClock.uptimeMillis() - t0) + " ms: " + e);
            throw e;
        }
    }

    /** The parameters as the HAL has them, for the debug report. */
    String parameters() throws Exception {
        return (String) call(type, camera, "native_getParameters", new Class<?>[0]);
    }

    /** RearCamera.checkVideoSignal(), which the stock view calls once a second. */
    int signal() throws Exception {
        return (Integer) call(type, camera, "checkVideoSignal", new Class<?>[0]);
    }

    /** Stops the camera and lets go of it, for the reverse view. */
    void release() {
        if (previewing) {
            previewing = false;
            try {
                timed("stopPreview", "stopPreview", new Class<?>[0]);
            } catch (Throwable t) {
                // logged; released below either way
            }
        }
        try {
            timed("release", "release", new Class<?>[0]);
        } catch (Throwable t) {
            // logged
        }
    }

    private static void set(Class<?> t, Object o, String name, Object v) throws Exception {
        Field f = t.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    private static Object call(Class<?> t, Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = t.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(o, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }
}
