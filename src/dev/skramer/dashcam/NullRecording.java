package dev.skramer.dashcam;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** App-owned test request. A USB insertion cancels its token even while the app is closed. */
final class NullRecording {
    private final File request, cancelled;
    private String blocked;

    NullRecording(Config config) {
        request = new File(new File(config.appConf).getParentFile(), "null-recording");
        cancelled = new File(config.stateDir, "null-recording-cancelled");
        blocked = token(cancelled);
    }

    boolean requested(boolean driveMounted) {
        String value = token(request);
        if (value.isEmpty()) return false;
        if (driveMounted) {
            if (!value.equals(blocked)) {
                blocked = value;
                File tmp = new File(cancelled.getPath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(value.getBytes(StandardCharsets.US_ASCII));
                    tmp.setReadable(true, false);
                    if (!tmp.renameTo(cancelled)) throw new IOException("can't cancel null recording");
                } catch (IOException e) {
                    Log.e("cancelling null recording", e);
                }
                Log.i("USB drive detected; null recording disabled");
            }
            return false;
        }
        return !value.equals(blocked);
    }

    private static String token(File file) {
        if (!file.isFile()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[64];
            int n = in.read(bytes);
            return n == 36 ? new String(bytes, 0, n, StandardCharsets.US_ASCII) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
