#!/usr/bin/env bash
# Builds and signs S-Voice-Gemini-Bridge.apk.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

BT="$ROOT/tools/sdk/build-tools/34.0.0"
AJ="$ROOT/tools/sdk/platforms/android-21/android.jar"
XP="$ROOT/tools/lib/xposed-api-82.jar"
KS="$ROOT/signing/svoice.keystore"
OUT="$ROOT/S-Voice-Gemini-Bridge.apk"

for f in "$BT/aapt2.exe" "$AJ" "$XP"; do
  [ -f "$f" ] || { echo "missing: $f"; exit 1; }
done

rm -rf build/gen build/classes build/dex build/res.zip \
       build/base.apk build/app.apk build/app-aligned.apk build/classes.jar
mkdir -p build/gen build/classes build/dex

"$BT/aapt2.exe" compile --dir module/res -o build/res.zip
"$BT/aapt2.exe" link -o build/base.apk -I "$AJ" \
  --manifest module/AndroidManifest.xml -A module/assets \
  --java build/gen --auto-add-overlay build/res.zip

javac -source 8 -target 8 -bootclasspath "$AJ" -classpath "$XP" \
  -d build/classes $(find module/src build/gen -name '*.java')

jar cf build/classes.jar -C build/classes .
"$BT/d8.bat" --min-api 21 --lib "$AJ" --output build/dex build/classes.jar

cp build/base.apk build/app.apk
(cd build/dex && jar uf ../app.apk classes.dex)
"$BT/zipalign.exe" -f 4 build/app.apk build/app-aligned.apk

if [ ! -f "$KS" ]; then
  mkdir -p signing
  keytool -genkeypair -keystore "$KS" -alias svoice -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass svoicegemini -keypass svoicegemini \
    -dname "CN=S Voice Gemini Bridge, OU=Personal, O=Personal, L=NA, ST=NA, C=US"
fi

"$BT/apksigner.bat" sign --ks "$KS" --ks-key-alias svoice \
  --ks-pass pass:svoicegemini --key-pass pass:svoicegemini \
  --min-sdk-version 21 --out "$OUT" build/app-aligned.apk
"$BT/apksigner.bat" verify --print-certs "$OUT"

echo "built: $OUT"
