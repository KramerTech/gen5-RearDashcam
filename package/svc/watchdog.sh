#!/system/bin/sh
# NEMESIS service: kill the recorder if its heartbeat stops (the whole process wedged). While it
# holds the camera, the stock reverse view can't open, so a hung recorder must not stay alive.
# The recorder handles a slow camera release itself; this is the backstop. NEMESIS restarts it.
cd /data/nemesis/apps/dashcam || exit 1
. ./scripts/common.sh
wait_for_boot

# Overridable for tests: seconds between checks, and how many unchanged checks mean wedged.
INTERVAL=${WATCHDOG_INTERVAL:-2}
STALE=${WATCHDOG_STALE:-3}
last=
same=0
while true; do
    sleep $INTERVAL
    beat=$(cat "$STATE/heartbeat" 2>/dev/null) || continue
    if [ "$beat" = "$last" ]; then
        same=$((same + 1))
    else
        same=0
        last=$beat
    fi
    if [ $same -ge $STALE ] && recorder_pid > /dev/null; then
        kill_recorder "no heartbeat for $((same * INTERVAL)) s"
        same=0
    fi
done
