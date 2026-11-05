#!/system/bin/sh
# Keeps the Frida bridge attached to S Voice while the app is running.
# The agent lives inside S Voice's process, so it dies with the app; this loop
# notices the new process and attaches again.
#
# Runs on the phone as root, pushed to /data/local/tmp/svoice-gemini/watchdog.sh.
# Usage: watchdog.sh          keep running until killed
#        watchdog.sh stop     stop a running instance

DIR=/data/local/tmp/svoice-gemini
[ -f "$DIR/bridge.js" ] || DIR=${0%/*}

PACKAGE=com.vlingo.midas
LOG=$DIR/watchdog.log
PIDFILE=$DIR/watchdog.pid
BRIDGE_LOG=$DIR/bridge.log
INTERVAL=2
RETRY_PAUSE=8

stamp() {
  date '+%m-%d %H:%M:%S' 2>/dev/null || date
}

log() {
  echo "$(stamp) $*" >>"$LOG"
}

stop_existing() {
  [ -f "$PIDFILE" ] || return 0
  old=$(cat "$PIDFILE" 2>/dev/null)
  case "$old" in ''|*[!0-9]*) old="" ;; esac
  if [ -n "$old" ] && [ -d "/proc/$old" ]; then
    cmd=$(tr '\0' ' ' <"/proc/$old/cmdline" 2>/dev/null)
    case "$cmd" in
      *watchdog.sh*) kill "$old" 2>/dev/null ;;
    esac
  fi
  rm -f "$PIDFILE"
}

find_pid() {
  for f in /proc/[0-9]*/cmdline; do
    cmd=$(tr '\0' ' ' <"$f" 2>/dev/null)
    case "$cmd" in
      "$PACKAGE"|"$PACKAGE "*)
        pid=${f#/proc/}
        echo "${pid%/cmdline}"
        return
        ;;
    esac
  done
  echo ""
}

ui_visible() {
  dumpsys activity activities 2>/dev/null | grep -q "$PACKAGE"
}

attach() {
  pid=$1
  exe=$(ls -l "/proc/$pid/exe" 2>/dev/null)
  case "$exe" in
    *app_process32*) INJECT=$DIR/frida-inject32 ;;
    *app_process64*) INJECT=$DIR/frida-inject64 ;;
    *) INJECT=$DIR/frida-inject64 ;;
  esac
  if [ ! -x "$INJECT" ]; then
    log "missing $INJECT; tap Install Frida payload in the app"
    return 1
  fi

  [ -f "$BRIDGE_LOG" ] && mv -f "$BRIDGE_LOG" "$BRIDGE_LOG.old"
  log "attaching $(basename "$INJECT") to pid $pid"
  "$INJECT" -p "$pid" -s "$DIR/bridge.js" -e >>"$BRIDGE_LOG" 2>&1
  status=$?
  log "injector exited $status"
  sleep 2
  if grep -q frida "/proc/$pid/maps" 2>/dev/null; then
    log "attached to pid $pid"
    return 0
  fi
  log "agent not mapped yet"
  return 1
}

case "$1" in
  stop)
    stop_existing
    echo "watchdog stopped"
    exit 0
    ;;
esac

stop_existing
echo $$ >"$PIDFILE"

[ -f "$LOG" ] && [ "$(wc -c <"$LOG")" -gt 65536 ] && mv -f "$LOG" "$LOG.old"
log "watchdog started (pid $$), polling every ${INTERVAL}s"

while :; do
  pid=$(find_pid)
  if [ -n "$pid" ]; then
    if ! grep -q frida "/proc/$pid/maps" 2>/dev/null; then
      # Attach once the UI exists, so the bridge does not hook a half-initialised app.
      if ui_visible; then
        attach "$pid" || sleep "$RETRY_PAUSE"
      fi
    fi
  fi
  sleep "$INTERVAL"
done
