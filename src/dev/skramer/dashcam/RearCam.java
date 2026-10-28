package dev.skramer.dashcam;

import android.os.Looper;
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
        call(t, c, "native_setup", new Class<?>[] {Object.class, int.class, String.class},
                new WeakReference<>(c), id, "android");
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
        call(type, camera, "native_setParameters", new Class<?>[] {String.class},
                String.format(java.util.Locale.US, PARAMETERS, width, height));
        previewing = true;
        call(type, camera, "startPreview", new Class<?>[0]);
    }

    /** Stops the camera and lets go of it, for the reverse view. */
    void release() {
        if (previewing) {
            previewing = false;
            try {
                call(type, camera, "stopPreview", new Class<?>[0]);
            } catch (Throwable t) {
                Log.e("camera stopPreview failed", t);  // released below either way
            }
        }
        try {
            call(type, camera, "release", new Class<?>[0]);
        } catch (Throwable t) {
            Log.e("camera release failed", t);
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
