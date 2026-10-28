#!/system/bin/sh
# Emulator-only recorder status simulator. It never opens a camera or encodes video.
state_dir=/data/nemesis/dashcam
pidfile=/data/local/tmp/dashcam-dev-recorder.pid
if [ -r "$pidfile" ]; then
    previous=$(cat "$pidfile")
    if [ -r "/proc/$previous/cmdline" ]; then
        case "$(tr '\000' ' ' < "/proc/$previous/cmdline")" in
            *"sh /data/local/tmp/dashcam-dev-recorder.sh"*) exit 0 ;;
        esac
    fi
fi
echo $$ > "$pidfile"
trap 'rm -f "$pidfile"; exit' INT TERM EXIT
mkdir -p "$state_dir"
chmod 755 "$state_dir"
last_state=
while :; do
    enabled=true fps=15 bitrate=1500000 segment=60 minfree=5 encoder=OMX.TCC.ENC.avc
    conf=/data/data/dev.skramer.dashcam.player/files/recorder.conf
    if [ -r "$conf" ]; then
        while IFS='=' read -r key value; do
            case "$key" in
                enabled) enabled=$value ;; fps) fps=$value ;; bitrate) bitrate=$value ;;
                segment) segment=$value ;; minfree) minfree=$value ;; encoder) encoder=$value ;;
            esac
        done < "$conf"
    fi
    state=recording
    [ "$enabled" = false ] && state=off
    viewing=/data/data/dev.skramer.dashcam.player/files/viewing
    now=$(date +%s)
    if [ "$enabled" = true ] && [ -f "$viewing" ]; then
        viewed=$(stat -c '%Y' "$viewing" 2>/dev/null)
        if [ -n "$viewed" ] && [ "$((now - viewed))" -ge 0 ] && [ "$((now - viewed))" -lt 6 ]; then
            state=paused
        fi
    fi
    if [ "$state" != "$last_state" ]; then
        since=$now
        last_state=$state
    fi
    {
        printf 'state=%s\nenabled=%s\nsince=%s000\n' "$state" "$enabled" "$since"
        printf 'out=/sdcard/Dashcam\nmount=/sdcard\n'
        printf 'fps=%s\nbitrate=%s\nsegment=%s\nminfree=%s\nencoder=%s\n' "$fps" "$bitrate" "$segment" "$minfree" "$encoder"
    } > "$state_dir/status.tmp"
    chmod 644 "$state_dir/status.tmp"
    mv "$state_dir/status.tmp" "$state_dir/status"
    touch "$state_dir/heartbeat"
    chmod 644 "$state_dir/heartbeat"
    sleep 1
done
