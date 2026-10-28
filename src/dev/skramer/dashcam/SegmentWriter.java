package dev.skramer.dashcam;

import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Writes encoded frames to segment files on its own thread, so a slow or stalled USB drive
 * never holds up the camera thread (which must be free to release the camera for reverse).
 * <p>
 * Saving the last few minutes (the steering wheel button) cuts the segment being written at the
 * next keyframe, which loses nothing, then moves every segment last written to within that time
 * into the Saved folder, where the oldest-first cleanup never looks.
 */
final class SegmentWriter extends Thread {
    /** Calls back on this thread when writing fails (drive removed or full). */
    interface Failure {
        void onWriteFailed();
    }

    private static final class Frame {
        byte[] data, config;
        int size;
        long ptsUs;
        boolean key;
    }

    /** Closes the current segment. */
    private static final Frame END = new Frame();

    private final Config config;
    private final Storage storage;
    private final Failure failure;
    private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(300);  // about 10 s at 30 fps
    private final BlockingQueue<Frame> free = new ArrayBlockingQueue<>(32);
    private TsWriter segment;
    private String segmentName;
    private long segmentStartUs, lastSync;
    private int dropped;
    /** Wall time of a pending save request, or 0. */
    private volatile long saveRequestedMs;

    SegmentWriter(Config config, Storage storage, Failure failure) {
        super("segment-writer");
        this.config = config;
        this.storage = storage;
        this.failure = failure;
        setDaemon(true);
    }

    /** Queues a frame from an encoder output buffer; drops it if the writer is more than about 10 s behind. */
    void frame(ByteBuffer src, int size, long ptsUs, boolean key, byte[] config) {
        Frame f = free.poll();
        if (f == null) f = new Frame();
        if (f.data == null || f.data.length < size) {
            f.data = new byte[Math.max(size, 16 * 1024)];
        }
        src.get(f.data, 0, size);
        f.size = size;
        f.ptsUs = ptsUs;
        f.key = key;
        f.config = config;
        if (!queue.offer(f)) {
            free.offer(f);
            if (dropped++ % 30 == 0) Log.w("writer is behind; dropped " + dropped + " frames so far");
        }
    }

    /** Queues a frame; drops it if the writer is more than about 10 s behind. */
    void frame(byte[] data, long ptsUs, boolean key, byte[] config) {
        frame(ByteBuffer.wrap(data), data.length, ptsUs, key, config);
    }

    /** Saves the last Config.saveSeconds: at the next keyframe, or right away if nothing is recording. */
    void saveRecent() {
        saveRequestedMs = System.currentTimeMillis();
    }

    /** Ends the current segment once the frames before this call are written. */
    void end() {
        queue.clear();  // stopping: don't wait on a slow drive for the backlog
        queue.offer(END);
    }

    @Override
    public void run() {
        while (true) {
            Frame f;
            try {
                f = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            try {
                if (f == null) {
                    if (segment == null && saveRequestedMs != 0) save();  // not recording right now
                    continue;
                }
                if (f == END) {
                    closeSegment();
                    if (saveRequestedMs != 0) save();
                    continue;
                }
                try {
                    boolean cut = f.ptsUs - segmentStartUs >= config.segmentSeconds * 1_000_000L || saveRequestedMs != 0;
                    if (segment == null || (f.key && cut)) {
                        if (!f.key) continue;  // a segment has to start on a keyframe
                        closeSegment();
                        if (saveRequestedMs != 0) save();
                        openSegment(f.ptsUs);
                    }
                    segment.writeFrame(f.data, f.size, f.ptsUs - segmentStartUs, f.key, f.config);
                    long now = SystemClock.uptimeMillis();
                    if (now - lastSync >= 1000) {
                        segment.sync();
                        lastSync = now;
                    }
                } finally {
                    f.config = null;
                    free.offer(f);
                }
            } catch (IOException | RuntimeException e) {
                // RuntimeException too: StatFs and friends throw unchecked ones when the drive goes away.
                Log.e("writing " + segmentName + " failed", e);
                abandonSegment();
                queue.clear();
                failure.onWriteFailed();
            }
        }
    }

    private void openSegment(long ptsUs) throws IOException {
        storage.makeRoom();
        File file = storage.newSegment();
        segment = new TsWriter(file);
        segmentName = file.getName();
        segmentStartUs = ptsUs;
        lastSync = SystemClock.uptimeMillis();
        Log.i("recording " + segmentName);
    }

    /**
     * Moves the closed segments last written to in the Config.saveSeconds before the request into
     * the Saved folder. The one that was being written ends at the keyframe after the request.
     */
    private void save() {
        long requested = saveRequestedMs;
        saveRequestedMs = 0;
        File dir = new File(config.outDir), saved = new File(dir, "Saved");
        File[] segments = dir.listFiles();
        int moved = 0, failed = 0;
        String first = null;
        if (segments != null) {
            java.util.Arrays.sort(segments);
            for (File f : segments) {
                if (!f.getName().endsWith(".ts") || f.lastModified() < requested - config.saveSeconds * 1000L) continue;
                if ((saved.isDirectory() || saved.mkdirs()) && f.renameTo(new File(saved, f.getName()))) {
                    if (first == null) first = f.getName();
                    moved++;
                } else {
                    failed++;
                }
            }
        }
        Log.i("saved " + moved + " segments" + (failed > 0 ? "; couldn't move " + failed : ""));
        if (first != null) name(saved, first, "Saved from steering wheel");
        int minutes = (config.saveSeconds + 59) / 60;
        Notify.toast(failed > 0 ? "Dashcam: couldn't save the last " + minutes + " minutes"
                : moved == 0 ? "Dashcam: nothing recorded in the last " + minutes + " minutes"
                : "Dashcam: saved the last " + minutes + " minutes");
    }

    /**
     * Names a saved event in the player app's names file (Library.names: "first clip's file
     * name<TAB>name" lines), which shows it in place of the date, with the date underneath.
     */
    private static void name(File saved, String clip, String name) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(saved, "names.txt"), true)) {
            out.write((clip + "\t" + name + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e("can't name the saved clips", e);
        }
    }

    private void closeSegment() throws IOException {
        if (segment == null) return;
        TsWriter s = segment;
        segment = null;
        s.close();
    }

    private void abandonSegment() {
        try {
            closeSegment();
        } catch (IOException ignored) {
            // The drive is gone; the frames already written are what's left.
        }
    }
}
