#!/usr/bin/env bash
# Linux/ARM64 port of tools/build.ps1 — builds DSMonitor without Gradle/Android Studio.
#
#   tools/build.sh
#
# Needs: JDK 17+ (javac/java/keytool), an Android SDK with platforms/android-34/android.jar
# and build-tools 35.0.0 (only for d8.jar; aapt2/zipalign/apksigner may come from the distro
# package android-sdk-build-tools when the SDK binaries are x86_64 and the host is arm64).
set -euo pipefail

VERSION_NAME="${VERSION_NAME:-1.3.0}"
VERSION_CODE="${VERSION_CODE:-130}"
SDK="${ANDROID_SDK_ROOT:-/root/android-sdk}"
BT="${BUILD_TOOLS:-$SDK/build-tools/35.0.0}"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
AAPT2="${AAPT2:-$BT/aapt2}"
ZIPALIGN="${ZIPALIGN:-$BT/zipalign}"
D8_JAR="$BT/lib/d8.jar"
APKSIGNER_JAR="${APKSIGNER_JAR:-/usr/lib/android-sdk/build-tools/29.0.3/apksigner.jar}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
RES="$APP/src/main/res"
SRC="$APP/src/main/java"
MANIFEST="$APP/src/main/AndroidManifest.xml"
BUILD="$ROOT/.build"
DIST="$ROOT/dist"
KEYSTORE="$ROOT/keystore/dsmonitor.jks"
STORE_PASS="dsmonitor"
KEY_ALIAS="dsmonitor"
APK="$DIST/DSMonitor-$VERSION_NAME.apk"

# x86_64 binaries of the SDK cannot run on an arm64 host: fall back to the distro build-tools.
if [ ! -x "$AAPT2" ] || ! "$AAPT2" version >/dev/null 2>&1; then
  AAPT2="/usr/lib/android-sdk/build-tools/29.0.3/aapt2"
  ZIPALIGN="/usr/lib/android-sdk/build-tools/29.0.3/zipalign"
fi
[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR"; exit 1; }
[ -f "$D8_JAR" ] || { echo "missing $D8_JAR"; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "[1/8] aapt2 compile"
"$AAPT2" compile --dir "$RES" -o "$BUILD/res.zip"

echo "[2/8] aapt2 link"
"$AAPT2" link -o "$BUILD/base.apk" -I "$ANDROID_JAR" --manifest "$MANIFEST" \
  -R "$BUILD/res.zip" --java "$BUILD/gen" \
  --min-sdk-version 23 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --custom-package com.pigeoncookie.dsmonitor --auto-add-overlay

echo "[3/8] javac"
find "$SRC" -name '*.java' > "$BUILD/sources.txt"
echo "$BUILD/gen/com/pigeoncookie/dsmonitor/R.java" >> "$BUILD/sources.txt"
javac -nowarn --release 11 -classpath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[4/8] d8 (dex)"
python3 - "$BUILD/classes" "$BUILD/classes.jar" <<'PY'
import sys, os, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for name in files:
            p = os.path.join(root, name)
            z.write(p, os.path.relpath(p, src))
PY
java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 23 \
  --lib "$ANDROID_JAR" --output "$BUILD/dex" "$BUILD/classes.jar"

echo "[5/8] package classes.dex"
cp "$BUILD/base.apk" "$BUILD/app-unsigned.apk"
python3 - "$BUILD/app-unsigned.apk" "$BUILD/dex/classes.dex" <<'PY'
import sys, zipfile, shutil
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_STORED) as z:
    z.write(dex, "classes.dex")
PY

echo "[6/8] zipalign"
"$ZIPALIGN" -f -p 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

echo "[7/8] sign"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  echo "      creating a local keystore (alias $KEY_ALIAS)"
  keytool -genkeypair -keystore "$KEYSTORE" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 \
    -validity 10950 -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
    -dname 'CN=DS Monitor, OU=Dev, O=PigeonCookie, L=Beijing, C=CN' >/dev/null 2>&1
fi
java -jar "$APKSIGNER_JAR" sign --ks "$KEYSTORE" --ks-pass "pass:$STORE_PASS" \
  --key-pass "pass:$STORE_PASS" --ks-key-alias "$KEY_ALIAS" --out "$APK" "$BUILD/app-aligned.apk"
java -jar "$APKSIGNER_JAR" verify --print-certs "$APK" | head -3

echo "[8/8] core logic tests"
mkdir -p "$BUILD/tests"
javac -nowarn --release 11 -classpath "$ANDROID_JAR" -d "$BUILD/tests" \
  "$SRC"/com/pigeoncookie/dsmonitor/core/*.java \
  "$SRC"/com/pigeoncookie/dsmonitor/data/HourlyCache.java \
  "$ROOT/tools/jvm-tests/CoreTest.java"
java -cp "$BUILD/tests" com.pigeoncookie.dsmonitor.core.CoreTest | tail -2

echo
echo "APK  -> $APK ($(( $(stat -c%s "$APK") / 1024 )) KB)"
