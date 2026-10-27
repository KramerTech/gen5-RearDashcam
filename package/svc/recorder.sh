#!/system/bin/sh
# NEMESIS service: run the dashcam recorder (dashcam.jar under app_process). It starts as root
# and switches itself to the system uid (camera) with group sdcard_r (the USB drive); see
# Main.dropRoot. NEMESIS restarts this service when the recorder exits.
cd /data/nemesis/apps/dashcam || exit 1
. ./scripts/common.sh
. ./dashcam.conf

load_android_env || log "WARN: no framework environment; app_process may fail"
wait_for_boot
sleep 2  # don't spin if the recorder keeps failing at startup

mkdir -p "$STATE" && chmod 777 "$STATE"
rm -f "$STATE/disabled"  # 0.4 test builds kept the on/off setting here; the app has it now
if [ -f "$LOG" ]; then
    set -- $(ls -l "$LOG")  # no wc here; toolbox ls -l puts the size in field 4
    [ "${4:-0}" -gt 1048576 ] && mv "$LOG" "$LOG.1"
fi
log "starting recorder"
export CLASSPATH=$HERE/dashcam.jar
exec app_process /system/bin dev.skramer.dashcam.Main $DASHCAM_ARGS >> "$LOG" 2>&1
