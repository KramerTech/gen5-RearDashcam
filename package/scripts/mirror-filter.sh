#!/system/bin/sh
# Run as system, which owns IntentFirewall's 0700 directory. NEMESIS root has no DAC override.
HERE=/data/nemesis/apps/dashcam
STATE=/data/nemesis/dashcam
RULE=/data/system/ifw/dashcam-mirror-mode.xml
TMP=/data/system/ifw/.dashcam-mirror-mode.tmp
ACK=$STATE/mirror-key-filtered

case "$1" in
    enable)
        mkdir -p /data/system/ifw || exit 1
        if [ ! -f "$RULE" ]; then
            cp "$HERE/mirror-mode.xml" "$TMP" && chmod 644 "$TMP" && mv "$TMP" "$RULE" || {
                rm -f "$TMP"
                exit 1
            }
        fi
        if [ ! -f "$ACK" ]; then
            # FileObserver reloads rules asynchronously. Also handles an interrupted install.
            sleep 1
            touch "$ACK" || exit 1
        fi
        ;;
    disable)
        rm -f "$RULE" "$TMP" "$ACK" || exit 1
        ;;
    *) exit 2 ;;
esac
