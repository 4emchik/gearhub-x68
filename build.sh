#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_JAR:-sdk/android.jar}"
OUT="build"
APK="GearHubX68.apk"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile resources"
aapt2 compile --dir res -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
aapt2 link \
  -o "$OUT/unsigned.apk" \
  -I "$SDK" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 24 \
  --target-sdk-version 32 \
  --version-code 4 \
  --version-name 1.3 \
  "$OUT/res.zip"

echo "[3/6] javac"
if ! javac -source 8 -target 8 -Xlint:-options -classpath "$SDK" \
  -d "$OUT/classes" \
  $(find src -name '*.java'); then
  echo "javac failed" >&2
  exit 1
fi

echo "[4/6] d8"
d8 --lib "$SDK" --min-api 24 --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "[5/6] package assets + dex"
( cd "$OUT/dex" && zip -q -u ../unsigned.apk classes.dex )
zip -q -u "$OUT/unsigned.apk" assets/webhid_shim.js

echo "[6/6] sign"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out "$APK" "$OUT/unsigned.apk"
apksigner verify --verbose "$APK"

ls -lh "$APK"
