#!/system/bin/sh
# Must first start at boot for CAP_SYS_RAWIO. The helper drops root after mapping
# VIOC, sleeps on its socket, and restores alpha when the recorder disconnects.
cd /data/nemesis/apps/dashcam || exit 1
. ./scripts/common.sh
wait_for_boot
sleep 10  # also bounds retries after late installation or on unsupported hardware
if [ -f /data/nemesis/dashcam-overlay.log ]; then
    set -- $(ls -l /data/nemesis/dashcam-overlay.log)
    [ "${4:-0}" -le 1048576 ] || mv /data/nemesis/dashcam-overlay.log /data/nemesis/dashcam-overlay.log.1
fi
chmod 755 ./dashcam-overlay || exit 1
# Readable by the recorder (uid 1000), which puts it in the camera debug report.
echo "$(date) start" >> /data/nemesis/dashcam-overlay.log
chmod 644 /data/nemesis/dashcam-overlay.log
exec ./dashcam-overlay >> /data/nemesis/dashcam-overlay.log 2>&1
