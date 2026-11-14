#!/bin/bash
# Builds an unsigned Nive.ipa; SideStore re-signs it with your Apple ID on install.
# Usage: ./build_ipa.sh [version] [build-number]   -> build/Nive.ipa
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${1:-0.1.0}"
BUILD="${2:-1}"

xcodegen generate --quiet
rm -rf build/Nive.xcarchive build/Payload build/Nive.ipa

xcodebuild archive -quiet \
  -project Nive.xcodeproj -scheme Nive \
  -destination 'generic/platform=iOS' \
  -archivePath build/Nive.xcarchive \
  -derivedDataPath build/DerivedData \
  MARKETING_VERSION="$VERSION" CURRENT_PROJECT_VERSION="$BUILD" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=""

APP=build/Nive.xcarchive/Products/Applications/Nive.app
[ -d "$APP" ] || { echo "Archive failed: $APP missing" >&2; exit 1; }

mkdir -p build/Payload
cp -R "$APP" build/Payload/
(cd build && zip -qry Nive.ipa Payload)
echo "Built build/Nive.ipa ($VERSION, build $BUILD)"
