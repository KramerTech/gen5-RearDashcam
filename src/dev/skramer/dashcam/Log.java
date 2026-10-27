package dev.skramer.dashcam;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Logs to logcat (tag "Dashcam") and to stdout, which the service script appends to its log file. */
final class Log {
    private static final String TAG = "Dashcam";
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private Log() {}

    static void i(String msg) {
        android.util.Log.i(TAG, msg);
        print("I", msg);
    }

    static void w(String msg) {
        android.util.Log.w(TAG, msg);
        print("W", msg);
    }

    static void e(String msg, Throwable t) {
        android.util.Log.e(TAG, msg, t);
        print("E", t == null ? msg : msg + ": " + t);
    }

    private static synchronized void print(String level, String msg) {
        System.out.println(TIME.format(new Date()) + " " + level + " " + msg);
    }
}
