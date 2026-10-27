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
        if (state == null || state != Car.GUIDE_LINES_ON) return false;  // already off, or the car can't say
        try {
            if (!marker.createNewFile() && !marker.isFile()) throw new IOException("can't create " + marker);
        } catch (IOException e) {
            Log.e("not hiding the guide lines", e);  // they couldn't be put back after a crash
            return false;
        }
        if (Car.setGuideLines(Car.GUIDE_LINES_OFF)) return true;
        marker.delete();
        return false;
    }

    void restore() {
        if (!marker.isFile()) return;
        if (Car.setGuideLines(Car.GUIDE_LINES_ON)) {
            marker.delete();
        } else {
            Log.w("can't turn the guide lines back on; trying again next time");
        }
    }
}
