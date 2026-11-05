#!/usr/bin/env bash
# Moves /sdcard/.svoice_gemini.json between phones without printing the keys.
# The file is stashed in work/ (git-ignored) so only one phone needs to be plugged in at a time.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STASH="$ROOT/work/.svoice_gemini.json"
DEVICE_FILE=/sdcard/.svoice_gemini.json
MODE="${1:-}"

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

usage() {
  cat <<'EOF'
Usage:
  bash frida/copy-settings.sh save      copy the settings from the connected phone into work/
  bash frida/copy-settings.sh restore   copy the stashed settings onto the connected phone

Connect one phone at a time, or set ANDROID_SERIAL=<serial> when several are attached.
The API keys are never printed; only their lengths are reported.
EOF
}

ADB="$(find_adb)"
if [ -z "$ADB" ]; then
  echo "adb was not found. Install platform-tools or pass its path via ADB=..." >&2
  exit 1
fi

case "$MODE" in
  save|restore) ;;
  *) usage; exit 1 ;;
esac

SERIAL="${ANDROID_SERIAL:-}"
if [ -z "$SERIAL" ]; then
  devices=$("$ADB" devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}')
  count=$(printf '%s\n' "$devices" | grep -c . || true)
  if [ "$count" -eq 0 ]; then
    echo "No device in 'device' state." >&2
    exit 1
  fi
  if [ "$count" -gt 1 ]; then
    echo "More than one device; set ANDROID_SERIAL (candidates: $(printf '%s ' $devices))" >&2
    exit 1
  fi
  SERIAL=$devices
fi

export MSYS_NO_PATHCONV=1
win() {
  if command -v wslpath >/dev/null 2>&1; then wslpath -w "$1" 2>/dev/null && return; fi
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1" 2>/dev/null && return; fi
  printf '%s' "$1"
}

report() {
  local file=$1
  local size
  size=$(wc -c <"$file" | tr -d ' ')
  if command -v perl >/dev/null 2>&1; then
    perl -MJSON::PP -0777 -e '
      my $j = eval { decode_json(<STDIN>) };
      if (!$j) { print "unreadable JSON\n"; exit }
      my $k = $j->{apiKey} // "";
      my $g = $j->{groqKey} // "";
      printf "apiKey=%d chars, groqKey=%d chars\n", length($k), length($g);
    ' <"$file"
  fi
  echo "$size bytes at $file"
}

if [ "$MODE" = save ]; then
  mkdir -p "$ROOT/work"
  if [ -f "$STASH" ]; then
    mv -f "$STASH" "$STASH.bak"
    echo "previous stash kept as $STASH.bak"
  fi
  if ! "$ADB" -s "$SERIAL" pull "$DEVICE_FILE" "$(win "$STASH")" >/dev/null 2>&1; then
    echo "No $DEVICE_FILE on $SERIAL; open S Voice Gemini Bridge there and press Save first." >&2
    exit 1
  fi
  chmod 600 "$STASH" 2>/dev/null || true
  report "$STASH"
  echo "Stashed. Now connect the other phone and run: bash frida/copy-settings.sh restore"
  exit 0
fi

if [ ! -f "$STASH" ]; then
  echo "No stash at $STASH. Run 'save' on the phone that has the keys first." >&2
  exit 1
fi

"$ADB" -s "$SERIAL" push "$(win "$STASH")" "$DEVICE_FILE" >/dev/null
"$ADB" -s "$SERIAL" shell "chmod 644 $DEVICE_FILE" >/dev/null 2>&1 || true
size=$("$ADB" -s "$SERIAL" shell "wc -c < $DEVICE_FILE" | tr -d '\r ')
echo "Pushed $size bytes to $SERIAL"
report "$STASH"
echo "Start the bridge with: bash frida/push-and-run.sh (or it attaches on the next S Voice launch)"
