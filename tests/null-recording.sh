#!/bin/bash
# Verify that USB insertion cancels a request across recorder restarts, even if
# the app is absent, and that an explicit new request can re-enable testing.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cat > "$work/Log.java" <<'JAVA'
package dev.skramer.dashcam;
final class Log {
    static void i(String s) { }
    static void w(String s) { }
    static void e(String s, Throwable t) { throw new AssertionError(s, t); }
}
JAVA
cat > "$work/NullRecordingTest.java" <<'JAVA'
package dev.skramer.dashcam;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public final class NullRecordingTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Config config = new Config();
        config.appConf = root.resolve("recorder.conf").toString();
        config.stateDir = root.resolve("state").toString();
        Files.createDirectories(Paths.get(config.stateDir));
        Path request = root.resolve("null-recording");
        NullRecording recording = new NullRecording(config);
        check(!recording.requested(false), "default must be off");
        String first = java.util.UUID.randomUUID().toString();
        Files.write(request, first.getBytes(StandardCharsets.US_ASCII));
        check(recording.requested(false), "explicit request without a drive");
        check(!recording.requested(true), "USB must cancel output discard");
        check(!recording.requested(false), "unplugging must not reactivate cancelled request");
        recording = new NullRecording(config);
        check(!recording.requested(false), "cancellation must survive recorder restart");
        Files.write(request, java.util.UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
        check(recording.requested(false), "explicit new request must work");
        Files.delete(request);
        check(!recording.requested(false), "unchecking must stop testing");
        Files.write(request, new byte[100]);
        check(!recording.requested(false), "invalid request must not start testing");
        System.out.println("Null recording: defaults, USB cancellation, restart, re-enable and removal passed");
    }
}
JAVA
javac -d "$work/classes" "$repo/src/dev/skramer/dashcam/Config.java" \
    "$repo/src/dev/skramer/dashcam/NullRecording.java" "$work/Log.java" "$work/NullRecordingTest.java"
java -cp "$work/classes" dev.skramer.dashcam.NullRecordingTest "$work/data"
