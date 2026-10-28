package dev.skramer.dashcam;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Logs to logcat (tag "Dashcam") and to stdout, which the service script appends to its log file.
 * The last lines are also kept, numbered and timed to the millisecond, for the app's camera debug
 * screen.
 */
final class Log {
    private static final String TAG = "Dashcam";
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat TIME_MS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final String[] recent = new String[200];
    private static long count;

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
        Date now = new Date();
        System.out.println(TIME.format(now) + " " + level + " " + msg);
        recent[(int) (count++ % recent.length)] = TIME_MS.format(now) + " " + level + " " + msg.replace('\n', ' ');
    }

    /** The kept lines numbered above after, oldest first, as "log.N=line" lines. */
    static synchronized String since(long after) {
        if (after >= count) after = -1;  // the asker saw a recorder from before a restart
        StringBuilder b = new StringBuilder();
        for (long n = Math.max(after + 1, count - recent.length); n < count; n++) {
            b.append("log.").append(n).append('=').append(recent[(int) (n % recent.length)]).append('\n');
        }
        return b.toString();
    }
}
