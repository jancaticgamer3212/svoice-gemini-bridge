#!/usr/bin/env bash
# Pulls S Voice from a connected Samsung device so it can be sideloaded onto another phone.
# Personal use only: the APK stays yours, it is not redistributed by this repository.
set -euo pipefail

OUT="${1:-./SVoice.apk}"
PKG=com.vlingo.midas

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
  echo "  ADB=/c/adb/adb.exe bash frida/dump-svoice.sh" >&2
  exit 1
fi

# Keep device paths like /system/priv-app intact; give adb.exe a Windows path for the local file.
export MSYS_NO_PATHCONV=1
win() {
  if command -v wslpath >/dev/null 2>&1; then wslpath -w "$1" 2>/dev/null && return; fi
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1" 2>/dev/null && return; fi
  printf '%s' "$1"
}

devices=$("$ADB" devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$devices" ]; then
  echo "No device in 'device' state. Connect the Samsung phone and enable USB debugging." >&2
  exit 1
fi
if [ "$(printf '%s\n' "$devices" | wc -l)" -gt 1 ]; then
  echo "More than one device is connected; attach only the Samsung phone:" >&2
  printf '  %s\n' $devices >&2
  exit 1
fi

pkg_path=$("$ADB" shell pm path "$PKG" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -n 1 || true)
if [ -z "$pkg_path" ]; then
  echo "S Voice ($PKG) is not installed on this device." >&2
  exit 1
fi

info=$("$ADB" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' || true)
version=$(printf '%s\n' "$info" | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -n 1)
echo "Found S Voice ${version:-<unknown version>} at $pkg_path"

echo "Pulling to $OUT ..."
mkdir -p "$(dirname "$OUT")"
"$ADB" pull "$pkg_path" "$(win "$OUT")"

if [ ! -s "$OUT" ]; then
  echo "Pull failed; $OUT is missing or empty." >&2
  exit 1
fi

case "$version" in
  11.8.0.0|11.9.0.0)
    echo "Version $version matches the builds the bridge was written for."
    ;;
  "")
    echo "WARNING: could not read the version. The bridge only targets 11.8.0.0/11.9.0.0."
    ;;
  *)
    echo "WARNING: $version has not been tested. DialogFlow hook signatures may differ;"
    echo "         check frida/bridge.js against this build before relying on it."
    ;;
esac

if command -v unzip >/dev/null 2>&1; then
  abis=$(unzip -l "$OUT" 2>/dev/null | awk '/lib\// {print $4}' | cut -d/ -f2 | sort -u | tr '\n' ' ' || true)
  if [ -n "${abis// /}" ]; then
    echo "Native ABIs: $abis"
  else
    echo "Native ABIs: none (pure Java)"
  fi
fi

size=$(wc -c <"$OUT" | tr -d ' ')
echo "Size: $size bytes"
if command -v sha256sum >/dev/null 2>&1; then
  echo "SHA-256: $(sha256sum "$OUT" | awk '{print $1}')"
fi

cat <<EOF

Next, on the target phone:
  adb install -r $(basename "$OUT")
  adb install -r S-Voice-Gemini-Frida.apk
  bash frida/push-and-run.sh

Keep this APK local. Do not publish it: it is Samsung's software.
EOF
