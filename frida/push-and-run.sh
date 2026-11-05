#!/usr/bin/env bash
# Installs the bridge APK, copies the Frida payload to the phone and starts it as root.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR=/data/local/tmp/svoice-gemini

find_adb() {
  if [ -n "${ADB:-}" ]; then
    if [ -x "$ADB" ] || command -v "$ADB" >/dev/null 2>&1; then printf '%s' "$ADB"; return; fi
  fi
  if command -v adb >/dev/null 2>&1; then command -v adb; return; fi
  for c in /c/adb/adb.exe /mnt/c/adb/adb.exe \
           "${HOME:-/c/Users/Jan}/AppData/Local/Android/Sdk/platform-tools/adb.exe" \
           "/c/Program Files (x86)/Android/android-sdk/platform-tools/adb.exe" \
           "/c/Program Files/Android/platform-tools/adb.exe"; do
    if [ -x "$c" ]; then printf '%s' "$c"; return; fi
  done
  printf ''
}

ADB="$(find_adb)"
if [ -z "$ADB" ]; then
  echo "adb was not found. Install Android platform-tools or pass its path, e.g.:" >&2
  echo "  ADB=/c/adb/adb.exe bash frida/push-and-run.sh" >&2
  exit 1
fi
echo "Using adb: $ADB"

# Keep device paths like /data/local/tmp intact; give adb.exe Windows paths for local files.
export MSYS_NO_PATHCONV=1
win() {
  if command -v wslpath >/dev/null 2>&1; then wslpath -w "$1" 2>/dev/null && return; fi
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1" 2>/dev/null && return; fi
  printf '%s' "$1"
}

for f in "$ROOT/S-Voice-Gemini-Frida.apk" \
         "$ROOT/frida/bridge.js" \
         "$ROOT/frida/device-inject.sh" \
         "$ROOT/frida/watchdog.sh" \
         "$ROOT/frida/bin/frida-inject-12.8.20-android-arm64" \
         "$ROOT/frida/bin/frida-inject-12.8.20-android-arm"; do
  [ -f "$f" ] || { echo "missing: $f"; exit 1; }
done

echo "Installing the bridge APK..."
"$ADB" install -r "$(win "$ROOT/S-Voice-Gemini-Frida.apk")"

echo "Copying the payload to $DIR ..."
"$ADB" shell "mkdir -p $DIR"
"$ADB" push "$(win "$ROOT/frida/bridge.js")" "$DIR/bridge.js"
"$ADB" push "$(win "$ROOT/frida/bin/frida-inject-12.8.20-android-arm64")" "$DIR/frida-inject64"
"$ADB" push "$(win "$ROOT/frida/bin/frida-inject-12.8.20-android-arm")" "$DIR/frida-inject32"
"$ADB" push "$(win "$ROOT/frida/device-inject.sh")" "$DIR/inject.sh"
"$ADB" push "$(win "$ROOT/frida/watchdog.sh")" "$DIR/watchdog.sh"
"$ADB" shell "chmod 755 $DIR/frida-inject64 $DIR/frida-inject32 $DIR/inject.sh $DIR/watchdog.sh"

echo "Attaching now (root required)..."
"$ADB" shell "su -c 'sh $DIR/inject.sh'"

echo "Starting the watchdog service..."
"$ADB" shell "am broadcast -a com.svoice.gemini.START_WATCHDOG -n com.svoice.gemini/.BootReceiver" >/dev/null
echo "If SuperSU asks for root for \"S Voice Gemini Bridge\", allow it once."
sleep 3
"$ADB" shell "su -c 'cat $DIR/watchdog.log 2>/dev/null'"
"$ADB" shell "dumpsys activity services com.svoice.gemini | grep -i watchdog" || true
