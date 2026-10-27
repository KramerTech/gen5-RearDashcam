#!/system/bin/sh
# NEMESIS preuninstall, which also runs before an update: stop the recorder (it may outlive its
# service) and remove its state, except the copy of the app's settings. An update reinstalls the
# player app, wiping its own copy, so this one is what carries the settings over.
cd /data/nemesis/apps/dashcam || exit 0
. ./scripts/common.sh
kill_recorder "uninstalling"
restore_guide_lines
for f in "$STATE"/*; do
    case "$f" in */settings) ;; *) rm -rf "$f" ;; esac
done
log "uninstalled"
exit 0
