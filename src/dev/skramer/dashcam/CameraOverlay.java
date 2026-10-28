package dev.skramer.dashcam;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import java.io.IOException;

/**
 * One camera ownership lease. The native helper restores alpha on release or process death.
 * <p>
 * On the first camera start after boot, startPreview returns before the kernel turns the
 * camera's display layer on, and the helper refuses a layer that's off. So a refused lease is
 * asked for again in the background until it's granted, the camera is released, or RETRY_MS
 * runs out (a unit the helper doesn't support, or the helper missing until a reboot).
 */
final class CameraOverlay {
    private static final long RETRY_EVERY_MS = 250, RETRY_MS = 60000;

    /** Guards socket and wanted; held across the helper's short replies so restore can't interleave. */
    private final Object lock = new Object();
    private LocalSocket socket;
    /** Between hide and restore: the camera is ours and its layer should be transparent. */
    private boolean wanted;

    void hide(final int width, final int height) {
        synchronized (lock) {
            if (wanted) return;
            wanted = true;
            if (lease(width, height, true)) return;
        }
        new Thread("camera overlay") {
            @Override
            public void run() {
                long t0 = SystemClock.uptimeMillis();
                while (SystemClock.uptimeMillis() - t0 < RETRY_MS) {
                    SystemClock.sleep(RETRY_EVERY_MS);
                    synchronized (lock) {
                        if (!wanted) return;
                        if (lease(width, height, false)) {
                            Log.i("camera display layer transparent after " + (SystemClock.uptimeMillis() - t0) + " ms");
                            return;
                        }
                    }
                }
                Log.w("camera overlay unavailable; projected video will show the camera (reboot after install/update)");
            }
        }.start();
    }

    /** Asks the helper once, holding lock. True if the layer is now transparent. */
    private boolean lease(int width, int height, boolean first) {
        LocalSocket candidate = new LocalSocket();
        try {
            candidate.connect(new LocalSocketAddress("dev.skramer.dashcam.overlay"));
            candidate.setSoTimeout(500);
            candidate.getOutputStream().write(new byte[] {'H', (byte)(width >> 8), (byte)width,
                    (byte)(height >> 8), (byte)height});
            if (candidate.getInputStream().read() != 'H') throw new IOException("refused (layer not on yet, or unsupported)");
            socket = candidate;
            if (first) Log.i("camera display layer transparent");
            return true;
        } catch (IOException e) {
            try { candidate.close(); } catch (IOException ignored) { }
            if (first) Log.i("camera overlay: " + e + "; retrying");
            return false;
        }
    }

    void restore() {
        synchronized (lock) {
            wanted = false;
            LocalSocket current = socket;
            socket = null;
            if (current == null) return;
            try {
                current.getOutputStream().write('S');
                if (current.getInputStream().read() != 'S') throw new IOException("no restore acknowledgement");
                Log.i("camera display layer restored");
            } catch (IOException e) {
                Log.w("camera overlay restore: " + e);
            } finally {
                try { current.close(); } catch (IOException ignored) { }
            }
        }
    }
}
