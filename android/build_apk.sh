#!/bin/bash
# Builds a signed release APK: ./build_apk.sh <version> <build-number>  →  ../build/Nive-<version>.apk
# The signing key lives at ~/.nive/android-release.jks, its password in the macOS Keychain ("nive-android-keystore").
# The first run creates both. Back them up: without the same key, phones can't update Nive over the old version.
set -euo pipefail
VERSION="${1:?version, e.g. 0.3.0}"
BUILD="${2:?build number, e.g. 30}"
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

KEYSTORE="$HOME/.nive/android-release.jks"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$HOME/.nive"
  PASS="$(openssl rand -base64 24)"
  security add-generic-password -U -s nive-android-keystore -a nive -w "$PASS"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias nive -keyalg RSA -keysize 4096 -validity 36500 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=Nive"
  echo "Created $KEYSTORE — back it up together with the Keychain item 'nive-android-keystore'."
fi
export NIVE_KEYSTORE="$KEYSTORE"
export NIVE_KEY_PASSWORD="$(security find-generic-password -s nive-android-keystore -a nive -w)"

./gradlew --console=plain :app:testDebugUnitTest :app:assembleRelease -PniveVersion="$VERSION" -PniveBuild="$BUILD"
mkdir -p ../build
cp app/build/outputs/apk/release/app-release.apk "../build/Nive-$VERSION.apk"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "../build/Nive-$VERSION.apk" | head -2
ls -la "../build/Nive-$VERSION.apk"
