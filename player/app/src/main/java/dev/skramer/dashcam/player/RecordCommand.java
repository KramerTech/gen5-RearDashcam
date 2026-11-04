package dev.skramer.dashcam.player;

/** Keeps stale status polls from repainting a recording command before it is acknowledged. */
final class RecordCommand {
    final boolean enabled;
    private final long startedAt;

    RecordCommand(boolean enabled, long startedAt) {
        this.enabled = enabled;
        this.startedAt = startedAt;
    }

    boolean waiting(Boolean configuredEnabled, String state, long now) {
        if (now - startedAt >= 10_000) return false;
        if (state == null || "starting".equals(state)) return true;
        boolean acknowledged = configuredEnabled != null ? configuredEnabled == enabled
                : enabled != "off".equals(state);
        if (!enabled && ("recording".equals(state) || "reversing".equals(state)))
            acknowledged = false;
        return !acknowledged;
    }
}
