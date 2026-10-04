#!/usr/bin/env bash
# Build the ViaPageZoom LSPosed module APK.
#
# Requires a JDK 17+ and Android build-tools (aapt2/d8/zipalign/apksigner) plus a
# platform android.jar and the Xposed api-82 jar. Point the variables below at your
# copies, or export them before invoking this script:
#
#   BUILD_TOOLS=/path/to/build-tools/36.0.0 \
#   ANDROID_JAR=/path/to/platforms/android-36/android.jar \
#   API_JAR=/path/to/api-82.jar \
#   ./build.sh
#
# See README.md ("构建") for how to obtain each piece.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

: "${BUILD_TOOLS:?set BUILD_TOOLS to your Android build-tools dir (contains aapt2, d8, zipalign, apksigner)}"
: "${ANDROID_JAR:?set ANDROID_JAR to a platform android.jar (API 26+)}"
: "${API_JAR:?set API_JAR to the Xposed api-82.jar (de.robv.android.xposed:api:82)}"

command -v javac >/dev/null || { echo "javac not found; install a JDK 17+ or set PATH" >&2; exit 1; }

cd "$ROOT"

echo "== clean"
rm -rf build/classes build/dex build/base.apk build/unsigned.apk build/aligned.apk build/ViaPageZoom-1.0.apk
mkdir -p build/classes build/dex

echo "== javac"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn \
  -cp "$ANDROID_JAR:$API_JAR" \
  -d build/classes \
  $(find src -name '*.java')

echo "== d8"
"$BUILD_TOOLS/d8" --min-api 26 --lib "$ANDROID_JAR" --output build/dex \
  $(find build/classes -name '*.class')

echo "== aapt2 link"
"$BUILD_TOOLS/aapt2" link -o build/base.apk \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  -A assets \
  --min-sdk-version 26 --target-sdk-version 36 \
  --version-code 1 --version-name 1.0

echo "== add classes.dex"
ROOT="$ROOT" python3 - <<'PY'
import os, zipfile
root = os.environ["ROOT"]
src = os.path.join(root, "build/base.apk")
dst = os.path.join(root, "build/unsigned.apk")
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    for info in zin.infolist():
        zout.writestr(info, zin.read(info.filename))
    zout.write(os.path.join(root, "build/dex/classes.dex"), "classes.dex")
print("entries:", zipfile.ZipFile(dst).namelist())
PY

echo "== zipalign"
"$BUILD_TOOLS/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

echo "== keystore"
if [ ! -f build/debug.keystore ]; then
  keytool -genkeypair -keystore build/debug.keystore -alias viamod \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=ViaMod PageZoom, O=zw1, C=CN"
fi

echo "== sign"
"$BUILD_TOOLS/apksigner" sign --ks build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out build/ViaPageZoom-1.0.apk build/aligned.apk

echo "== verify"
"$BUILD_TOOLS/apksigner" verify --print-certs build/ViaPageZoom-1.0.apk
ls -l build/ViaPageZoom-1.0.apk
