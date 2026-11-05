#!/system/bin/sh
# Runs on the phone as root. Pushed to /data/local/tmp/svoice-gemini/inject.sh
# by push-and-run.sh; it starts S Voice if needed, then attaches bridge.js.

DIR=/data/local/tmp/svoice-gemini
[ -f "$DIR/bridge.js" ] || DIR=${0%/*}

PACKAGE=com.vlingo.midas
ACTIVITY=$PACKAGE/com.vlingo.midas.gui.ConversationActivity
LOG=$DIR/bridge.log

find_pid() {
  for f in /proc/[0-9]*/cmdline; do
    cmd=$(cat "$f" 2>/dev/null)
    case "$cmd" in
      "$PACKAGE")
        pid=${f#/proc/}
        echo "${pid%/cmdline}"
        return
        ;;
    esac
  done
  echo ""
}

if [ ! -f /sdcard/.svoice_gemini.json ]; then
  echo "Settings are missing. Enter the Gemini API key in S Voice Gemini Bridge and tap Save."
  am start -n com.svoice.gemini/com.svoice.gemini.SettingsActivity >/dev/null 2>&1
  exit 2
fi

pid=$(find_pid)
if [ -z "$pid" ]; then
  echo "Starting S Voice..."
  am start -n "$ACTIVITY" >/dev/null 2>&1
  i=0
  while [ -z "$pid" ] && [ $i -lt 30 ]; do
    sleep 1
    pid=$(find_pid)
    i=$((i + 1))
  done
fi

if [ -z "$pid" ]; then
  echo "S Voice is not running and could not be started."
  exit 1
fi
echo "S Voice pid: $pid"

if grep -q frida /proc/$pid/maps 2>/dev/null; then
  echo "Frida is already attached to this S Voice process; nothing to do."
  exit 0
fi

exe=$(ls -l /proc/$pid/exe 2>/dev/null)
case "$exe" in
  *app_process64*) INJECT="$DIR/frida-inject64" ;;
  *app_process32*) INJECT="$DIR/frida-inject32" ;;
  *) INJECT="$DIR/frida-inject64" ;;
esac

if [ ! -x "$INJECT" ]; then
  echo "Missing $INJECT; run push-and-run.sh from the PC first."
  exit 1
fi

[ -f "$LOG" ] && mv -f "$LOG" "$LOG.old"

echo "Attaching $INJECT ..."
"$INJECT" -p "$pid" -s "$DIR/bridge.js" -e >>"$LOG" 2>&1
status=$?

if [ -f "$LOG" ]; then
  echo "----- bridge log -----"
  cat "$LOG"
  echo "----------------------"
fi

echo "----- Java bridge logcat -----"
logcat -d -s SVoiceGeminiFrida
echo "------------------------------"

if [ $status -ne 0 ]; then
  echo "Injection failed. If the log shows an SELinux denial, run:"
  echo "  su -c setenforce 0"
  echo "then run this script again (setenforce 0 lasts until the next reboot)."
fi

exit $status
