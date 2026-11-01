#!/system/bin/sh
# NEMESIS preinstall: refuses cars it hasn't been tested on. NEMESIS.data's depends/os/models
# cover the codec package and the firmware, but only as a warning the user can accept, and they
# can't single out the IONIQ 5 (its firmware also runs the IONIQ 6) or tell trims apart. So this
# checks the model and the camera itself.
STAGE=/data/local/tmp/nemesis/stage/dashcam
[ -f "$STAGE/dashcam.jar" ] || STAGE=$(cd "${0%/*}/.." && pwd)
. "$STAGE/scripts/common.sh"
load_android_env || log "preinstall: WARN: no framework environment"

toast() {
    am broadcast -a com.hkmc.intent.action.TOAST \
        -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" \
        --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true > /dev/null 2>&1
}

# The car model is the firmware's model directory, e.g. /system/onebin/neev23_us. IONIQ 5 is neev*.
model=$(getprop ro.mobis.targetdir)
model=${model%/}
model=${model##*/}
log "preinstall: model ${model:-unknown}"
case "$model" in
    neev*) ;;
    *)
        toast "Rear Dashcam: only supported on the Hyundai IONIQ 5 (this car: ${model:-unknown})"
        log "preinstall: refused, not an IONIQ 5"
        exit 1
        ;;
esac

# Camera type 6 (the digital camera of 360-view trims such as the IONIQ 5 Limited, and the EV6)
# sends video only while the car shows its own camera view, so the dashcam would record nothing.
# Asks the HAL from the staged package; if it can't tell, installs anyway.
# Hooks run as root without DAC override, which can't write /data/dalvik-cache, so Dalvik gets a
# cache of its own here (ANDROID_DATA/dalvik-cache), removed after.
DALVIK=$STAGE/.dalvik
mkdir -p "$DALVIK/dalvik-cache"
type=$(ANDROID_DATA=$DALVIK CLASSPATH=$STAGE/dashcam.jar app_process /system/bin dev.skramer.dashcam.CameraCheck 2>/dev/null)
rm -rf "$DALVIK"
log "preinstall: camera type ${type:-unknown}"
if [ "$type" = 6 ]; then
    toast "Rear Dashcam: camera system not supported (360 view cars)"
    log "preinstall: refused, camera type 6 isn't supported"
    exit 1
fi
exit 0
