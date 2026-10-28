package dev.skramer.dashcam;

import java.io.File;
import java.io.IOException;

/**
 * Keeps the rear camera's parking guide lines out of the recordings. The camera draws them into
 * its video itself, so they're switched off while recording (the camera settings' parking
 * guidance switch) and back on when the camera is handed to the reverse view.
 * <p>
 * The marker file says the lines are off because of us. It outlives a crash or a kill, so the
 * next recorder (Main) and the service scripts (common.sh) can put them back.
 */
final class GuideLines {
    private final File marker;

    GuideLines(Config config) {
        marker = new File(config.stateDir, "guidelines-hidden");
    }

    /** Turns the lines off if they're on. True if it did (the camera takes a few frames to follow). */
    boolean hide() {
        Integer state = Car.guideLines();
        if (state == null || state != Car.GUIDE_LINES_ON) {
            Log.i("guide lines " + (state == null ? "unknown" : "already off") + "; leaving them");
            return false;  // already off, or the car can't say
        }
        try {
            if (!marker.createNewFile() && !marker.isFile()) throw new IOException("can't create " + marker);
        } catch (IOException e) {
            Log.e("not hiding the guide lines", e);  // they couldn't be put back after a crash
            return false;
        }
        if (Car.setGuideLines(Car.GUIDE_LINES_OFF)) {
            Log.i("guide lines off");
            return true;
        }
        Log.w("can't turn the guide lines off");
        marker.delete();
        return false;
    }

    /**
     * If we turned them off but they read on again, asks again. The car's parking service drops
     * an "off" that comes right after an "on" (a quick restart: restore, then hide), and the lines
     * then stay in the video. True if it asked.
     */
    boolean keepOff() {
        if (!marker.isFile()) return false;
        Integer state = Car.guideLines();
        if (state == null || state != Car.GUIDE_LINES_ON) return false;
        boolean ok = Car.setGuideLines(Car.GUIDE_LINES_OFF);
        Log.i(ok ? "guide lines read on again; turned them off again" : "guide lines read on again; can't turn them off");
        return true;
    }

    void restore() {
        if (!marker.isFile()) return;
        if (Car.setGuideLines(Car.GUIDE_LINES_ON)) {
            Log.i("guide lines back on");
            marker.delete();
        } else {
            Log.w("can't turn the guide lines back on; trying again next time");
        }
    }
}
