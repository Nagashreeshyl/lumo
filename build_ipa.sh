#!/bin/bash
# Builds an unsigned Lumo.ipa; SideStore re-signs it with your Apple ID on install.
# Usage: ./build_ipa.sh [version] [build-number]   -> build/Lumo.ipa
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${1:-0.1.0}"
BUILD="${2:-1}"

xcodegen generate --quiet
rm -rf build/Lumo.xcarchive build/Payload build/Lumo.ipa

xcodebuild archive -quiet \
  -project Lumo.xcodeproj -scheme Lumo \
  -destination 'generic/platform=iOS' \
  -archivePath build/Lumo.xcarchive \
  -derivedDataPath build/DerivedData \
  MARKETING_VERSION="$VERSION" CURRENT_PROJECT_VERSION="$BUILD" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=""

APP=build/Lumo.xcarchive/Products/Applications/Lumo.app
[ -d "$APP" ] || { echo "Archive failed: $APP missing" >&2; exit 1; }

mkdir -p build/Payload
cp -R "$APP" build/Payload/
(cd build && zip -qry Lumo.ipa Payload)
echo "Built build/Lumo.ipa ($VERSION, build $BUILD)"
