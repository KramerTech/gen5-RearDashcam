package dev.skramer.dashcam.player;

import java.io.File;

/** One recorded segment file. Its name is the local time its first frame was recorded. */
final class Clip {
    final File file;
    /** Wall-clock time of the first frame, ms. */
    final long start;
    final boolean saved;
    final long bytes;
    /** Estimated until {@link #exact}: from the next clip's start or the file size. */
    long durationMs;
    boolean exact;

    Clip(File file, long start, boolean saved, long bytes) {
        this.file = file;
        this.start = start;
        this.saved = saved;
        this.bytes = bytes;
    }

    long end() {
        return start + durationMs;
    }

    boolean contains(long wall) {
        return wall >= start && wall < end();
    }
}
