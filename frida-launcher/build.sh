#!/usr/bin/env bash
# Builds and signs S-Voice-Frida-Launcher.apk from this folder.
# The payload (bridge.js, inject.sh, watchdog.sh) and the Frida 12.8.20 injectors come from
# ../frida, so there is only one copy of them in the repository.
set -euo pipefail

APP="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$APP/.." && pwd)"
cd "$APP"

BT="$REPO/tools/sdk/build-tools/34.0.0"
AJ="$REPO/tools/sdk/platforms/android-21/android.jar"
KS="$REPO/signing/svoice.keystore"
OUT="$APP/S-Voice-Frida-Launcher.apk"
B="$APP/build"

for f in "$BT/aapt2.exe" "$AJ" \
         "$REPO/frida/bridge.js" \
         "$REPO/frida/device-inject.sh" \
         "$REPO/frida/watchdog.sh" \
         "$REPO/frida/bin/frida-inject-12.8.20-android-arm64" \
         "$REPO/frida/bin/frida-inject-12.8.20-android-arm"; do
  [ -f "$f" ] || { echo "missing: $f"; exit 1; }
done

rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex" "$B/assets" \
         "$B/lib/arm64-v8a" "$B/lib/armeabi-v7a"

cp "$REPO/frida/bridge.js" "$B/assets/bridge.js"
cp "$REPO/frida/device-inject.sh" "$B/assets/inject.sh"
cp "$REPO/frida/watchdog.sh" "$B/assets/watchdog.sh"
cp "$REPO/frida/bin/frida-inject-12.8.20-android-arm64" "$B/lib/arm64-v8a/libfrida_inject.so"
cp "$REPO/frida/bin/frida-inject-12.8.20-android-arm" "$B/lib/armeabi-v7a/libfrida_inject.so"

"$BT/aapt2.exe" compile --dir "$APP/res" -o "$B/res.zip"
"$BT/aapt2.exe" link -o "$B/base.apk" -I "$AJ" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$B/gen" --auto-add-overlay -A "$B/assets" "$B/res.zip"

javac -source 8 -target 8 -bootclasspath "$AJ" \
  -d "$B/classes" $(find "$APP/src" "$B/gen" -name '*.java')

jar cf "$B/classes.jar" -C "$B/classes" .
"$BT/d8.bat" --min-api 21 --lib "$AJ" --output "$B/dex" "$B/classes.jar"

cp "$B/base.apk" "$B/app.apk"
(cd "$B/dex" && jar uf ../app.apk classes.dex)
(cd "$B" && jar uf app.apk \
  lib/arm64-v8a/libfrida_inject.so \
  lib/armeabi-v7a/libfrida_inject.so)
"$BT/zipalign.exe" -f 4 "$B/app.apk" "$B/app-aligned.apk"

if [ ! -f "$KS" ]; then
  mkdir -p "$REPO/signing"
  keytool -genkeypair -keystore "$KS" -alias svoice -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass svoicegemini -keypass svoicegemini \
    -dname "CN=S Voice Gemini Bridge, OU=Personal, O=Personal, L=NA, ST=NA, C=US"
fi

"$BT/apksigner.bat" sign --ks "$KS" --ks-key-alias svoice \
  --ks-pass pass:svoicegemini --key-pass pass:svoicegemini \
  --min-sdk-version 21 --out "$OUT" "$B/app-aligned.apk"
"$BT/apksigner.bat" verify --print-certs "$OUT"

echo "built: $OUT ($(du -h "$OUT" | cut -f1))"
