package dev.skramer.dashcam;

/** Pop-up messages on the head unit's screen, through Hyundai's toast broadcast. */
final class Notify {
    private Notify() {}

    /** Shows a toast. Runs `am` on its own thread, as it takes a second or so to start. */
    static void toast(final String message) {
        Thread t = new Thread("toast") {
            @Override
            public void run() {
                try {
                    Process p = new ProcessBuilder("am", "broadcast", "-a", "com.hkmc.intent.action.TOAST",
                            "-e", "com.hkmc.intent.extra.TOAST_MESSAGE", message,
                            "--ez", "com.hkmc.intent.extra.TOAST_POPUPSHOWING", "true")
                            .redirectErrorStream(true).start();
                    byte[] b = new byte[512];
                    while (p.getInputStream().read(b) > 0) {
                        // Drained so am can't block on a full pipe.
                    }
                    p.waitFor();
                } catch (Exception e) {
                    Log.w("can't show \"" + message + "\": " + e);
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }
}
