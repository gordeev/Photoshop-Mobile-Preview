#!/bin/bash
# Builds PSPreview.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${JAVA_HOME:-}" ]; then
  for j in /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk; do
    [ -x "$j/bin/javac" ] && JAVA_HOME="$j" && break
  done
fi
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home)"
fi
if [ -n "${JAVA_HOME:-}" ]; then
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  for s in /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk"; do
    [ -d "$s/build-tools" ] && ANDROID_HOME="$s" && break
  done
fi
SDK="${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
# Any build-tools version works; take the newest installed.
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"

rm -rf build && mkdir -p build/gen build/classes build/dex build/assets
# Cutout profiles are shared with the browser viewer.
cp ../server/cutouts.json build/assets/

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml --java build/gen -A build/assets build/res.zip

javac --release 11 -nowarn -encoding UTF-8 -classpath "$ANDROID_JAR" \
  -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --min-api 24 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)

"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

if [ -n "${KEYSTORE:-}" ]; then
  : "${SIGNING_PASSWORD_FILE:?Set SIGNING_PASSWORD_FILE to the private password file}"
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias pspreview \
    --ks-pass "file:$SIGNING_PASSWORD_FILE" \
    --v4-signing-enabled false --out ../PSPreview.apk build/aligned.apk
else
  KEYSTORE=debug.keystore
  if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
      -alias pspreview -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=PS Preview Debug" >/dev/null 2>&1
  fi
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --v4-signing-enabled false --out ../PSPreview.apk build/aligned.apk
fi
"$BT/apksigner" verify ../PSPreview.apk

echo "Built: $(cd .. && pwd)/PSPreview.apk"
