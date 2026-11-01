#!/system/bin/sh
# NEMESIS preinstall: refuses to install without the Video Codec Fix package (media.codecs), which
# the Dashcam app needs to play recordings on the head unit, and refuses cars whose camera can't
# be recorded.
STAGE=/data/local/tmp/nemesis/stage/dashcam
[ -f "$STAGE/dashcam.jar" ] || STAGE=$(cd "${0%/*}/.." && pwd)
. "$STAGE/scripts/common.sh"
load_android_env || log "preinstall: WARN: no framework environment"

toast() {
    am broadcast -a com.hkmc.intent.action.TOAST \
        -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" \
        --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true > /dev/null 2>&1
}

CODECS_ID=media.codecs
CODECS_NAME="Video Codec Fix"
CODECS_MIN_VERSION=2.0
CODECS_DATA=/data/nemesis/apps/$CODECS_ID/NEMESIS.data

# version_ge <a> <b>: a >= b, comparing up to three dot-separated numbers.
# Non-digits in a part are ignored ("2.0-rc1" compares as 2.0.1).
version_ge() {
    _a=$1 _b=$2 _i=0
    while [ $_i -lt 3 ]; do
        _pa=${_a%%.*}; _pb=${_b%%.*}
        case "$_a" in *.*) _a=${_a#*.} ;; *) _a= ;; esac
        case "$_b" in *.*) _b=${_b#*.} ;; *) _b= ;; esac
        _pa=$(digits "$_pa"); _pb=$(digits "$_pb")
        [ "$_pa" -gt "$_pb" ] && return 0
        [ "$_pa" -lt "$_pb" ] && return 1
        _i=$((_i + 1))
    done
    return 0
}
digits() {
    _d=
    _s=$1
    while [ -n "$_s" ]; do
        _ch=${_s%"${_s#?}"}
        _s=${_s#?}
        case "$_ch" in [0-9]) _d=$_d$_ch ;; esac
    done
    print -r -- "${_d:-0}"
}

# Installed media.codecs version, or nothing.
codecs_version() {
    [ -f "$CODECS_DATA" ] || return 1
    while IFS= read -r _line; do
        case "$_line" in version=*) print -r -- "${_line#version=}"; return 0 ;; esac
    done < "$CODECS_DATA"
    print -r -- 0
}

_have=$(codecs_version) || _have=
if [ -z "$_have" ] || ! version_ge "$_have" "$CODECS_MIN_VERSION"; then
    log "preinstall: refused, needs $CODECS_ID $CODECS_MIN_VERSION or newer (found: ${_have:-not installed})"
    toast "Rear Dashcam not installed: install the $CODECS_NAME package ($CODECS_ID $CODECS_MIN_VERSION or newer) first. Found: ${_have:-not installed}."
    exit 1
fi
log "preinstall: $CODECS_ID $_have"

# Camera type 6 (the digital camera of 360-view cars, and the EV6) sends video only while the car
# shows its own camera view, so the dashcam would record nothing. Asks the HAL from the staged
# package; if it can't tell, installs anyway.
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
