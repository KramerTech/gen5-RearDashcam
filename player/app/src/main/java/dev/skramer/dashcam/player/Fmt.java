package dev.skramer.dashcam.player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Display formats. */
final class Fmt {
    private Fmt() {}

    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static final SimpleDateFormat HM = new SimpleDateFormat("HH:mm", Locale.US);
    private static final SimpleDateFormat DAY = new SimpleDateFormat("EEE, MMM d", Locale.US);
    private static final SimpleDateFormat LONG_DAY = new SimpleDateFormat("EEEE, MMMM d", Locale.US);
    private static final SimpleDateFormat FILE = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    static String time(long t) {
        return TIME.format(new Date(t));
    }

    static String hm(long t) {
        return HM.format(new Date(t));
    }

    static String file(long t) {
        return FILE.format(new Date(t));
    }

    /** "Today", "Yesterday" or "Sat, Oct 24". */
    static String day(long day) {
        long today = Library.dayOf(System.currentTimeMillis());
        if (day == today) return "Today";
        if (Library.nextDay(day) == today) return "Yesterday";
        return DAY.format(new Date(day));
    }

    static String longDay(long day) {
        String d = LONG_DAY.format(new Date(day));
        String rel = day(day);
        return rel.equals("Today") || rel.equals("Yesterday") ? rel + ", " + d : d;
    }

    /** "6 h 12 min", "47 min", "35 s". */
    static String duration(long ms) {
        long min = ms / 60_000;
        if (min == 0) return ms / 1000 + " s";
        if (min < 60) return min + " min";
        return min / 60 + " h" + (min % 60 == 0 ? "" : " " + min % 60 + " min");
    }

    static String size(long bytes) {
        double gb = bytes / 1e9;
        if (gb >= 10) return String.format(Locale.US, "%.0f GB", gb);
        if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb);
        return String.format(Locale.US, "%.0f MB", bytes / 1e6);
    }
}
