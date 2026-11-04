package dev.skramer.dashcam;

/** OEM Mode presses already include a hardware long-press indication (flag 0x80). */
final class ModeGesture {
    interface Actions { void shortPress(); void longPress(); }
    private final Actions actions;
    private boolean down, held;

    ModeGesture(Actions actions) { this.actions = actions; }

    void reset() { down = held = false; }

    void key(int action, int flags, int device, int key) {
        if (device != 5 || key != 254) return;
        if ((flags & 0x20) != 0) { reset(); return; }
        if (action == 0) {
            if (!down) { down = true; held = false; }
            if ((flags & 0x80) != 0 && !held) {
                held = true;
                actions.longPress();
            }
        } else if (action == 1) {
            boolean shortPress = down && !held;
            reset();
            if (shortPress) actions.shortPress();
        }
    }
}
