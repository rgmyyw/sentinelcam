#!/bin/bash
# Offline build: aapt2 + javac + d8 + zipalign + apksigner (no gradle)
set -e
cd "$(dirname "$0")"

SDK=/Users/yanghai/Library/Android/sdk
BT=$SDK/build-tools/36.0.0
PLAT=$SDK/platforms/android-36/android.jar

rm -rf build
mkdir -p build/gen build/obj build/dex

RESARGS=""
if [ -d res ]; then
  $BT/aapt2 compile --dir res -o build/res.zip
  RESARGS=build/res.zip
fi
$BT/aapt2 link -I "$PLAT" --manifest AndroidManifest.xml --java build/gen $RESARGS -o build/base.apk

find src -name '*.java' > build/sources.txt
if [ -d build/gen ] && find build/gen -name '*.java' | grep -q .; then
  find build/gen -name '*.java' >> build/sources.txt
fi
javac -source 1.8 -target 1.8 -Xlint:-options -bootclasspath "$PLAT" \
  -d build/obj @build/sources.txt

$BT/d8 --release --lib "$PLAT" --min-api 30 --output build/dex \
  $(find build/obj -name '*.class')

(cd build/dex && zip -q -j ../base.apk classes.dex)

$BT/zipalign -f 4 build/base.apk build/aligned.apk

if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out SentinelCam.apk build/aligned.apk

echo "BUILD OK: $(pwd)/SentinelCam.apk"
