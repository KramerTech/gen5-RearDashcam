package dev.skramer.dashcam;

import android.hardware.Camera;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Camera.open() for a process that isn't an app. It passes ActivityThread.currentPackageName()
 * to native_setup, which is null here; and the camera service's AppOps check revokes access
 * unless the package belongs to the caller's uid. So build the Camera by hand, as Camera(int)
 * does, with "android", the system uid's package. Callbacks arrive on the calling thread's looper.
 */
@SuppressWarnings("deprecation")
final class CameraOpener {
    private CameraOpener() {}

    static Camera open(int id) throws Exception {
        Constructor<Camera> k = Camera.class.getDeclaredConstructor();
        k.setAccessible(true);
        Camera c = k.newInstance();
        Constructor<?> handler = Class.forName("android.hardware.Camera$EventHandler")
                .getDeclaredConstructor(Camera.class, Camera.class, Looper.class);
        handler.setAccessible(true);
        Field f = Camera.class.getDeclaredField("mEventHandler");
        f.setAccessible(true);
        f.set(c, handler.newInstance(c, c, Looper.myLooper()));
        Method setup = Camera.class.getDeclaredMethod("native_setup", Object.class, int.class, String.class);
        setup.setAccessible(true);
        setup.invoke(c, new WeakReference<>(c), id, "android");
        return c;
    }
}
