#!/bin/bash
# Builds a signed release APK: ./build_apk.sh <version> <build-number>  →  ../build/Lumo-<version>.apk
# The signing key lives at ~/.lumo/android-release.jks, its password in the macOS Keychain ("lumo-android-keystore").
# The first run creates both. Back them up: without the same key, phones can't update Lumo over the old version.
set -euo pipefail
VERSION="${1:?version, e.g. 0.3.0}"
BUILD="${2:?build number, e.g. 30}"
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

KEYSTORE="$HOME/.lumo/android-release.jks"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$HOME/.lumo"
  PASS="$(openssl rand -base64 24)"
  security add-generic-password -U -s lumo-android-keystore -a lumo -w "$PASS"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias lumo -keyalg RSA -keysize 4096 -validity 36500 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=Nive"
  echo "Created $KEYSTORE — back it up together with the Keychain item 'lumo-android-keystore'."
fi
export LUMO_KEYSTORE="$KEYSTORE"
export LUMO_KEY_PASSWORD="$(security find-generic-password -s lumo-android-keystore -a lumo -w)"

./gradlew --console=plain :app:testDebugUnitTest :app:assembleRelease -PlumoVersion="$VERSION" -PlumoBuild="$BUILD"
mkdir -p ../build
cp app/build/outputs/apk/release/app-release.apk "../build/Lumo-$VERSION.apk"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "../build/Lumo-$VERSION.apk" | head -2
ls -la "../build/Lumo-$VERSION.apk"
