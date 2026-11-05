#!/usr/bin/env bash
# Builds and signs S-Voice-Gemini-Bridge.apk (Xposed module).
set -euo pipefail

APP="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$APP/.." && pwd)"
cd "$APP"

BT="$REPO/tools/sdk/build-tools/34.0.0"
AJ="$REPO/tools/sdk/platforms/android-21/android.jar"
XP="$REPO/tools/lib/xposed-api-82.jar"
KS="$REPO/signing/svoice.keystore"
OUT="$APP/S-Voice-Gemini-Bridge.apk"
B="$APP/build"

for f in "$BT/aapt2.exe" "$AJ" "$XP"; do
  [ -f "$f" ] || { echo "missing: $f"; exit 1; }
done

rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex"

"$BT/aapt2.exe" compile --dir "$APP/res" -o "$B/res.zip"
"$BT/aapt2.exe" link -o "$B/base.apk" -I "$AJ" \
  --manifest "$APP/AndroidManifest.xml" -A "$APP/assets" \
  --java "$B/gen" --auto-add-overlay "$B/res.zip"

javac -source 8 -target 8 -bootclasspath "$AJ" -classpath "$XP" \
  -d "$B/classes" $(find "$APP/src" "$B/gen" -name '*.java')

jar cf "$B/classes.jar" -C "$B/classes" .
"$BT/d8.bat" --min-api 21 --lib "$AJ" --output "$B/dex" "$B/classes.jar"

cp "$B/base.apk" "$B/app.apk"
(cd "$B/dex" && jar uf ../app.apk classes.dex)
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

echo "built: $OUT"
