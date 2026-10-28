#!/bin/bash
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cat > "$work/RecordCommandTest.java" <<'JAVA'
package dev.skramer.dashcam.player;
public final class RecordCommandTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        RecordCommand off = new RecordCommand(false, false, 100);
        check(off.waiting(true, "recording", false, 200), "ignore old on status after clicking off");
        check(off.waiting(false, "recording", false, 300), "wait for active recording to end");
        check(!off.waiting(false, "off", false, 1000), "acknowledge stopped recording");
        RecordCommand on = new RecordCommand(true, false, 100);
        check(on.waiting(null, null, false, 150), "unknown status cannot acknowledge a command");
        check(on.waiting(false, "off", false, 200), "ignore old off status after clicking on");
        check(on.waiting(true, "starting", false, 300), "hold the transition while starting");
        check(!on.waiting(true, "nodrive", false, 1000), "enabled without drive is acknowledged");
        RecordCommand cancel = new RecordCommand(true, true, 100);
        check(cancel.waiting(true, "recording-null", true, 200), "wait until null output is cancelled");
        check(!cancel.waiting(true, "nodrive", false, 1000), "return to waiting for drive");
        check(!on.waiting(false, "off", false, 10100), "timeout must leave UI usable");
        check(!off.waiting(null, "off", false, 1000), "compatible with old status without enabled field");
        System.out.println("Record command: stale polls, transition, null cancellation and timeout passed");
    }
}
JAVA
javac -d "$work/classes" "$repo/player/app/src/main/java/dev/skramer/dashcam/player/RecordCommand.java" "$work/RecordCommandTest.java"
java -cp "$work/classes" dev.skramer.dashcam.player.RecordCommandTest
