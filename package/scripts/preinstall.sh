#!/system/bin/sh
# NEMESIS preinstall: refuses cars whose camera can't be recorded. Camera type 6 (the digital
# camera of 360-view cars, and the EV6) sends video only while the car shows its own camera view,
# so the dashcam would record nothing. Asks the HAL from the staged package; if it can't tell,
# installs anyway.
STAGE=/data/local/tmp/nemesis/stage/dashcam
[ -f "$STAGE/dashcam.jar" ] || STAGE=$(cd "${0%/*}/.." && pwd)
. "$STAGE/scripts/common.sh"
load_android_env || log "preinstall: WARN: no framework environment"
# Hooks run as root without DAC override, which can't write /data/dalvik-cache, so Dalvik gets a
# cache of its own here (ANDROID_DATA/dalvik-cache), removed after.
DALVIK=$STAGE/.dalvik
mkdir -p "$DALVIK/dalvik-cache"
type=$(ANDROID_DATA=$DALVIK CLASSPATH=$STAGE/dashcam.jar app_process /system/bin dev.skramer.dashcam.CameraCheck 2>/dev/null)
rm -rf "$DALVIK"
log "preinstall: camera type ${type:-unknown}"
if [ "$type" = 6 ]; then
    am broadcast -a com.hkmc.intent.action.TOAST \
        -e com.hkmc.intent.extra.TOAST_MESSAGE "Rear Dashcam: camera system not supported (360 view cars)" \
        --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true > /dev/null 2>&1
    log "preinstall: refused, camera type 6 isn't supported"
    exit 1
fi
exit 0
