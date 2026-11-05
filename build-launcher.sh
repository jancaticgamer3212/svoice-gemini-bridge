#!/usr/bin/env bash
# Builds and signs S-Voice-Frida-Launcher.apk: an on-device launcher that installs the
# Frida payload from its own assets and attaches the bridge through su.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

BT="$ROOT/tools/sdk/build-tools/34.0.0"
AJ="$ROOT/tools/sdk/platforms/android-21/android.jar"
KS="$ROOT/signing/svoice.keystore"
OUT="$ROOT/S-Voice-Frida-Launcher.apk"
B="$ROOT/build/launcher"

for f in "$BT/aapt2.exe" "$AJ" \
         "$ROOT/frida/bridge.js" \
         "$ROOT/frida/device-inject.sh" \
         "$ROOT/frida/watchdog.sh" \
         "$ROOT/frida/bin/frida-inject-12.8.20-android-arm64" \
         "$ROOT/frida/bin/frida-inject-12.8.20-android-arm"; do
  [ -f "$f" ] || { echo "missing: $f"; exit 1; }
done

rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex" "$B/assets" \
         "$B/lib/arm64-v8a" "$B/lib/armeabi-v7a"

cp frida/bridge.js "$B/assets/bridge.js"
cp frida/device-inject.sh "$B/assets/inject.sh"
cp frida/watchdog.sh "$B/assets/watchdog.sh"
cp frida/bin/frida-inject-12.8.20-android-arm64 "$B/lib/arm64-v8a/libfrida_inject.so"
cp frida/bin/frida-inject-12.8.20-android-arm "$B/lib/armeabi-v7a/libfrida_inject.so"

"$BT/aapt2.exe" compile --dir launcher/res -o "$B/res.zip"
"$BT/aapt2.exe" link -o "$B/base.apk" -I "$AJ" \
  --manifest launcher/AndroidManifest.xml \
  --java "$B/gen" --auto-add-overlay -A "$B/assets" "$B/res.zip"

javac -source 8 -target 8 -bootclasspath "$AJ" \
  -d "$B/classes" $(find launcher/src "$B/gen" -name '*.java')

jar cf "$B/classes.jar" -C "$B/classes" .
"$BT/d8.bat" --min-api 21 --lib "$AJ" --output "$B/dex" "$B/classes.jar"

cp "$B/base.apk" "$B/app.apk"
(cd "$B/dex" && jar uf ../app.apk classes.dex)
(cd "$B" && jar uf app.apk \
  lib/arm64-v8a/libfrida_inject.so \
  lib/armeabi-v7a/libfrida_inject.so)
"$BT/zipalign.exe" -f 4 "$B/app.apk" "$B/app-aligned.apk"

if [ ! -f "$KS" ]; then
  mkdir -p signing
  keytool -genkeypair -keystore "$KS" -alias svoice -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass svoicegemini -keypass svoicegemini \
    -dname "CN=S Voice Gemini Bridge, OU=Personal, O=Personal, L=NA, ST=NA, C=US"
fi

"$BT/apksigner.bat" sign --ks "$KS" --ks-key-alias svoice \
  --ks-pass pass:svoicegemini --key-pass pass:svoicegemini \
  --min-sdk-version 21 --out "$OUT" "$B/app-aligned.apk"
"$BT/apksigner.bat" verify --print-certs "$OUT"

echo "built: $OUT ($(du -h "$OUT" | cut -f1))"
