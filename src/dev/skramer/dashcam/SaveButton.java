package dev.skramer.dashcam;

import android.os.Build;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;

/**
 * The steering wheel's mute button, pressed four times in quick succession, saves the last few
 * minutes of recording. The key device is read passively, so the button still mutes (four presses
 * leave the sound as it was). It's opened while the process is still root: /dev/input is
 * root:input 0660, and the system uid isn't in group input.
 */
final class SaveButton extends Thread {
    private static final int PRESSES = 4;
    /** Quick presses on this car come about 200 ms apart, deliberate single ones over a second. */
    private static final long MAX_GAP_MS = 700;
    /** struct input_event on 32-bit ARM: timeval (2 x 4 bytes), u16 type, u16 code, s32 value. */
    private static final int EVENT_SIZE = 16;
    private static final int EV_KEY = 1;
    /** The head unit's key device: "daudio_" + its model. */
    private static final String KEYS = "daudio_";

    private final DataInputStream in;
    private final int keyCode;
    private final Runnable onSave;
    private int presses;
    private long lastPressMs;

    private SaveButton(FileInputStream in, int keyCode, Runnable onSave) {
        super("save-button");
        this.in = new DataInputStream(in);
        this.keyCode = keyCode;
        this.onSave = onSave;
        setDaemon(true);
    }

    /** Opens the key device named in Config.saveButton ("device name:key code"), or returns null. */
    static SaveButton open(Config config, Runnable onSave) {
        String spec = config.saveButton;
        int colon = spec.lastIndexOf(':');
        if (colon <= 0) return null;
        String name = spec.substring(0, colon);
        boolean auto = name.equals("auto");
        if (auto) name = KEYS + Build.DEVICE;
        try {
            String path = findDevice(name, false);
            // Another firmware might name it differently; the head unit has one daudio_ key device.
            if (path == null && auto) path = findDevice(KEYS, true);
            if (path == null) throw new IOException("no input device named " + name);
            return new SaveButton(new FileInputStream(path), Integer.parseInt(spec.substring(colon + 1)), onSave);
        } catch (IOException | RuntimeException e) {
            Log.e("the steering wheel button won't save clips", e);
            return null;
        }
    }

    /**
     * The /dev/input node for a device name, from /proc/bus/input/devices. With prefix, for the one
     * device whose name starts with it; null if there are none, or several.
     */
    private static String findDevice(String name, boolean prefix) throws IOException {
        String wanted = "N: Name=\"" + name, found = null;
        boolean match = false;
        int matches = 0;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/bus/input/devices"))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("N: ")) {
                    match = prefix ? line.startsWith(wanted) : line.equals(wanted + "\"");
                } else if (match && line.startsWith("H: Handlers=")) {
                    for (String h : line.substring(12).trim().split("\\s+")) {
                        if (!h.startsWith("event")) continue;
                        if (!prefix) return "/dev/input/" + h;
                        found = "/dev/input/" + h;
                        matches++;
                        break;
                    }
                }
            }
        }
        return matches == 1 ? found : null;
    }

    @Override
    public void run() {
        byte[] ev = new byte[EVENT_SIZE];
        try {
            while (true) {
                in.readFully(ev);
                int type = u16(ev, 8), code = u16(ev, 10), value = ev[12];
                if (type == EV_KEY && code == keyCode && value == 1) onPress(android.os.SystemClock.uptimeMillis());
            }
        } catch (IOException e) {
            Log.e("stopped reading the steering wheel button", e);
        }
    }

    private void onPress(long now) {
        presses = now - lastPressMs <= MAX_GAP_MS ? presses + 1 : 1;
        lastPressMs = now;
        if (presses < PRESSES) return;
        presses = 0;
        Log.i("save button pressed " + PRESSES + " times");
        onSave.run();
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8;
    }
}
