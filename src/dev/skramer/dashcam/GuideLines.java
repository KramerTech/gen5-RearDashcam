package dev.skramer.dashcam;

import android.os.SystemClock;
import java.io.File;
import java.io.IOException;

/**
 * Keeps the rear camera's parking guide lines out of the recordings. The camera draws them into
 * its video itself, so they're switched off while recording (the camera settings' parking
 * guidance switch) and back on when the camera is handed to the reverse view.
 * <p>
 * The car's parking service sometimes drops a request that comes soon after another (on, then
 * off, on a quick restart), and still reports success. So nothing here trusts a request, or a
 * reading taken right after one: the marker file says the driver has the lines on and we're the
 * ones turning them off. It's made when we first find them on, and removed only once they've
 * been seen back on, which is checked, and asked again, in the background. It outlives a crash or
 * a kill, so the next recorder (Main) and the service scripts (common.sh) put them back.
 */
final class GuideLines {
    /** After turning them back on: check every CHECK_MS, asking again, up to TRIES times. */
    private static final long CHECK_MS = 500;
    private static final int TRIES = 6;

    private final File marker;
    /** A session wants them off. */
    private volatile boolean hidden;
    /** Bumped by each hide and restore, which cancels the check of the one before. Guarded by this. */
    private int generation;

    GuideLines(Config config) {
        marker = new File(config.stateDir, "guidelines-hidden");
    }

    /** Turns the lines off if the driver has them on. True if it did (the camera takes a few frames to follow). */
    synchronized boolean hide() {
        generation++;
        if (!marker.isFile()) {
            Integer state = Car.guideLines();
            if (state == null || state != Car.GUIDE_LINES_ON) {
                Log.i("guide lines " + (state == null ? "unknown" : "already off") + "; leaving them");
                return false;  // off in the car's settings, or the car can't say
            }
            try {
                if (!marker.createNewFile() && !marker.isFile()) throw new IOException("can't create " + marker);
            } catch (IOException e) {
                Log.e("not hiding the guide lines", e);  // they couldn't be put back after a crash
                return false;
            }
        }
        hidden = true;
        if (Car.setGuideLines(Car.GUIDE_LINES_OFF)) {
            Log.i("guide lines off");
            return true;
        }
        Log.w("can't turn the guide lines off");
        hidden = false;
        return false;  // the marker stays: restore checks they're on
    }

    /** While hidden: if they read on again (a dropped request), asks again. True if it asked. */
    boolean keepOff() {
        if (!hidden || !marker.isFile()) return false;
        Integer state = Car.guideLines();
        if (state == null || state != Car.GUIDE_LINES_ON) return false;
        boolean ok = Car.setGuideLines(Car.GUIDE_LINES_OFF);
        Log.i(ok ? "guide lines read on again; turned them off again" : "guide lines read on again; can't turn them off");
        return true;
    }

    /**
     * Turns them back on at once if we turned them off, then, in the background, checks that
     * they are, asking again if not; the marker goes once they read on.
     */
    void restore() {
        final int gen;
        synchronized (this) {
            hidden = false;
            if (!marker.isFile()) return;
            gen = ++generation;
            boolean sent = Car.setGuideLines(Car.GUIDE_LINES_ON);
            Log.i(sent ? "guide lines back on" : "can't turn the guide lines back on yet");
        }
        Thread check = new Thread("guide lines") {
            @Override
            public void run() {
                for (int i = 0; i < TRIES; i++) {
                    SystemClock.sleep(CHECK_MS);
                    Integer state = Car.guideLines();
                    synchronized (GuideLines.this) {
                        if (generation != gen) return;  // hidden again since
                        if (state != null && state == Car.GUIDE_LINES_ON) {
                            marker.delete();
                            if (i > 0) Log.i("guide lines confirmed back on");
                            return;
                        }
                        Log.i("guide lines still read off; turning them on again");
                        Car.setGuideLines(Car.GUIDE_LINES_ON);
                    }
                }
                Log.w("can't confirm the guide lines are back on; trying again next time");
            }
        };
        check.setDaemon(true);
        check.start();
    }
}
