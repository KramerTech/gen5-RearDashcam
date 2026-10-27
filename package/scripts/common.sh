# Shared by the dashcam services and hooks. Sourced from the package directory.
ID=dashcam
HERE=/data/nemesis/apps/$ID
LOG=/data/nemesis/$ID.log
# Written by the recorder (system uid): heartbeat and scratch files.
STATE=/data/nemesis/$ID
NEMESIS=/system/xbin/nemesis
SYSTEM_UID=1000

log() { print -r -- "$(date '+%m-%d %H:%M:%S') $1" >> "$LOG" 2>/dev/null; true; }

# The hooks and services may start without init's environment; app_process needs BOOTCLASSPATH.
load_android_env() {
    [ -n "$BOOTCLASSPATH" ] && return 0
    [ -r /init.environ.rc ] || return 1
    while read _cmd _name _value; do
        [ "$_cmd" = export ] && export "$_name=$_value"
    done < /init.environ.rc
}

wait_for_boot() {
    while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 5; done
}

# "pid count" from the heartbeat file, if that pid is a running recorder.
recorder_pid() {
    _hb=$(cat "$STATE/heartbeat" 2>/dev/null) || return 1
    _pid=${_hb%% *}
    case "$_pid" in ''|*[!0-9]*) return 1 ;; esac
    # The recorder renames itself "dashcam", so a reused pid isn't mistaken for it.
    case "$(cat /proc/$_pid/cmdline 2>/dev/null)" in dashcam*) ;; *) return 1 ;; esac
    print -r -- "$_pid"
}

# NEMESIS root can switch uid but can't signal another uid's process, so kill as the system uid.
kill_recorder() {
    _pid=$(recorder_pid) || return 0
    log "killing recorder $_pid: $1"
    "$NEMESIS" root $SYSTEM_UID -c "kill -9 $_pid"
}
