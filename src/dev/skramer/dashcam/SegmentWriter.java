package dev.skramer.dashcam;

import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Writes encoded frames to segment files on its own thread, so a slow or stalled USB drive
 * never holds up the camera thread (which must be free to release the camera for reverse).
 */
final class SegmentWriter extends Thread {
    /** Calls back on this thread when writing fails (drive removed or full). */
    interface Failure {
        void onWriteFailed();
    }

    private static final class Frame {
        final byte[] data, config;
        final long ptsUs;
        final boolean key;

        Frame(byte[] data, long ptsUs, boolean key, byte[] config) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.key = key;
            this.config = config;
        }
    }

    /** Closes the current segment. */
    private static final Frame END = new Frame(null, 0, false, null);

    private final Config config;
    private final Storage storage;
    private final Failure failure;
    private final BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(300);  // about 10 s at 30 fps
    private TsWriter segment;
    private String segmentName;
    private long segmentStartUs, lastSync;
    private int dropped;

    SegmentWriter(Config config, Storage storage, Failure failure) {
        super("segment-writer");
        this.config = config;
        this.storage = storage;
        this.failure = failure;
        setDaemon(true);
    }

    /** Queues a frame; drops it if the writer is more than about 10 s behind. */
    void frame(byte[] data, long ptsUs, boolean key, byte[] config) {
        if (!queue.offer(new Frame(data, ptsUs, key, config)) && dropped++ % 30 == 0) {
            Log.w("writer is behind; dropped " + dropped + " frames so far");
        }
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
                f = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                if (f == END) {
                    closeSegment();
                    continue;
                }
                if (segment == null || (f.key && f.ptsUs - segmentStartUs >= config.segmentSeconds * 1_000_000L)) {
                    if (!f.key) continue;  // a segment has to start on a keyframe
                    closeSegment();
                    openSegment(f.ptsUs);
                }
                segment.writeFrame(f.data, f.ptsUs - segmentStartUs, f.key, f.config);
                long now = SystemClock.uptimeMillis();
                if (now - lastSync >= 1000) {
                    segment.sync();
                    lastSync = now;
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
