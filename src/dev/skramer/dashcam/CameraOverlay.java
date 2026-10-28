package dev.skramer.dashcam;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.IOException;

/** One camera ownership lease. The native helper restores alpha on release or process death. */
final class CameraOverlay {
    private LocalSocket socket;

    void hide(int width, int height) {
        if (socket != null) return;
        LocalSocket candidate = new LocalSocket();
        try {
            candidate.connect(new LocalSocketAddress("dev.skramer.dashcam.overlay"));
            candidate.setSoTimeout(500);
            candidate.getOutputStream().write(new byte[] {'H', (byte)(width >> 8), (byte)width,
                    (byte)(height >> 8), (byte)height});
            if (candidate.getInputStream().read() != 'H') throw new IOException("unsupported camera layout");
            socket = candidate;
            Log.i("camera display layer transparent");
        } catch (IOException e) {
            try { candidate.close(); } catch (IOException ignored) { }
            Log.w("camera overlay unavailable (reboot after install/update): " + e);
        }
    }

    void restore() {
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
