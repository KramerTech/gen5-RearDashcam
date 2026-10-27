package dev.skramer.dashcam.player;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The recordings on the drive: segments in the recording folder, plus saved ones in its Saved
 * folder, which the recorder never deletes to make room. Sorted by time.
 */
final class Library {
    static final String SAVED = "Saved";
    static final String SNAPSHOTS = "Snapshots";
    /** Names given to saved events: "clip file name<TAB>name" lines, kept on the drive with the clips. */
    private static final String NAMES = "names.txt";
    static final long HOUR = 3_600_000L;
    private static final Pattern NAME = Pattern.compile("(\\d{8}_\\d{6})(_\\d+)?\\.ts");

    final File dir;
    final List<Clip> clips;
    final long footageBytes, savedBytes;

    private Library(File dir, List<Clip> clips) {
        this.dir = dir;
        this.clips = clips;
        long all = 0, saved = 0;
        for (Clip c : clips) {
            all += c.bytes;
            if (c.saved) saved += c.bytes;
        }
        footageBytes = all;
        savedBytes = saved;
    }

    static Library scan(RecorderLink rec) {
        File dir = new File(rec.out);
        List<Clip> clips = new ArrayList<>();
        SimpleDateFormat names = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        add(clips, dir, false, names);
        add(clips, new File(dir, SAVED), true, names);
        Collections.sort(clips, new Comparator<Clip>() {
            @Override
            public int compare(Clip a, Clip b) {
                return a.start < b.start ? -1 : a.start > b.start ? 1 : a.file.getName().compareTo(b.file.getName());
            }
        });
        // A segment runs until the next one starts, unless there's a gap (reverse, power off)
        // after it; those, and the newest one, are probed. (Sizes say little: a still scene
        // takes far fewer bytes per minute.) The player probes the rest as it goes.
        long maxMs = rec.segment * 1000L + 5000;
        for (int i = 0; i < clips.size(); i++) {
            Clip c = clips.get(i);
            long toNext = i + 1 < clips.size() ? clips.get(i + 1).start - c.start : Long.MAX_VALUE;
            if (toNext <= maxMs) {
                c.durationMs = toNext;
            } else {
                long d = TsProbe.durationMs(c.file);
                c.durationMs = d >= 0 ? Math.min(d, toNext) : 0;
                c.exact = d >= 0;
            }
        }
        return new Library(dir, clips);
    }

    private static void add(List<Clip> out, File dir, boolean saved, SimpleDateFormat names) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            Matcher m = NAME.matcher(f.getName());
            if (!m.matches()) continue;
            try {
                out.add(new Clip(f, names.parse(m.group(1)).getTime(), saved, f.length()));
            } catch (ParseException ignored) {
                // not one of ours
            }
        }
    }

    boolean isEmpty() {
        return clips.isEmpty();
    }

    Clip last() {
        return clips.isEmpty() ? null : clips.get(clips.size() - 1);
    }

    /** Start of the local day containing t. */
    static long dayOf(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static long nextDay(long day) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(day);
        c.add(Calendar.DAY_OF_MONTH, 1);
        return dayOf(c.getTimeInMillis());
    }

    /** Start of the local hour containing t. */
    static long hourOf(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Days with footage, newest first. */
    List<Long> days() {
        List<Long> days = new ArrayList<>();
        for (int i = clips.size() - 1; i >= 0; i--) {
            long d = dayOf(clips.get(i).start);
            if (days.isEmpty() || days.get(days.size() - 1) != d) days.add(d);
        }
        return days;
    }

    /** Clips that start on the given day, oldest first. */
    List<Clip> day(long day) {
        long end = nextDay(day);
        List<Clip> out = new ArrayList<>();
        for (Clip c : clips) if (c.start >= day && c.start < end) out.add(c);
        return out;
    }

    /** Recorded milliseconds in [from, to). */
    long recordedMs(long from, long to) {
        long sum = 0;
        for (Clip c : clips) sum += Math.max(0, Math.min(c.end(), to) - Math.max(c.start, from));
        return sum;
    }

    /** The last clip that starts before t, or null. */
    Clip lastBefore(long t) {
        Clip found = null;
        for (Clip c : clips) {
            if (c.start >= t) break;
            found = c;
        }
        return found;
    }

    /** The first clip that ends after t, or null. */
    Clip firstEndingAfter(long t) {
        for (Clip c : clips) if (c.end() > t) return c;
        return null;
    }

    /** Clips overlapping [from, to). */
    List<Clip> between(long from, long to) {
        List<Clip> out = new ArrayList<>();
        for (Clip c : clips) if (c.end() > from && c.start < to) out.add(c);
        return out;
    }

    /**
     * Saved clips grouped into events: runs of saved clips with no unsaved clip between them and
     * less than a minute between one's end and the next's start (a pause for reverse doesn't
     * split an event; a stretch of unsaved footage does).
     */
    List<List<Clip>> savedEvents() {
        List<List<Clip>> events = new ArrayList<>();
        List<Clip> cur = null;
        long end = 0;
        for (Clip c : clips) {
            if (!c.saved) {
                cur = null;
                continue;
            }
            if (cur == null || c.start - end > 60_000) {
                cur = new ArrayList<>();
                events.add(cur);
            }
            cur.add(c);
            end = c.end();
        }
        Collections.reverse(events);
        return events;
    }

    /** The names given to saved events, by the file name of a clip in the event. */
    Map<String, String> names() {
        Map<String, String> names = new HashMap<>();
        File f = new File(new File(dir, SAVED), NAMES);
        if (!f.isFile()) return names;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                int tab = line.indexOf('\t');
                if (tab > 0 && tab < line.length() - 1) names.put(line.substring(0, tab), line.substring(tab + 1));
            }
        } catch (IOException ignored) {
            // Unnamed, then.
        }
        return names;
    }

    /** The event's name, or null. */
    static String nameOf(List<Clip> event, Map<String, String> names) {
        for (Clip c : event) {
            String n = names.get(c.file.getName());
            if (n != null) return n;
        }
        return null;
    }

    /**
     * Names a saved event (null or blank clears it). The name goes on its first clip and is
     * removed from the others, so events that later merge or shrink keep one name.
     */
    boolean setName(List<Clip> event, String name) {
        Map<String, String> names = names();
        for (Clip c : event) names.remove(c.file.getName());
        if (name != null && !name.trim().isEmpty()) {
            names.put(event.get(0).file.getName(), name.trim().replaceAll("[\\t\\r\\n]+", " "));
        }
        File saved = new File(dir, SAVED), f = new File(saved, NAMES), tmp = new File(saved, NAMES + ".tmp");
        if (!saved.isDirectory()) return false;
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> e : names.entrySet()) {
            if (new File(saved, e.getKey()).isFile()) text.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        f.delete();  // FAT/exFAT through FUSE won't rename over a file
        return tmp.renameTo(f);
    }

    /** Moves a clip into or out of the Saved folder. Returns false if the rename failed. */
    boolean setSaved(Clip c, boolean saved) {
        if (c.saved == saved) return true;
        File to = saved ? new File(new File(dir, SAVED), c.file.getName()) : new File(dir, c.file.getName());
        File parent = to.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) return false;
        return !to.exists() && c.file.renameTo(to);
    }
}
