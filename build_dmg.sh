#!/bin/bash
# Builds Lumo for Mac (Apple Silicon) and packages it as a drag-to-Applications DMG.
# Usage: ./build_dmg.sh [version] [build-number]   -> build/Lumo-<version>.dmg
# Ad-hoc signed (no paid Apple Developer account), so first launch needs "Open Anyway" (see docs/INSTALL-MAC.md).
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${1:-0.1.0}"
BUILD="${2:-1}"

xcodegen generate --quiet
rm -rf build/mac build/dmg "build/Lumo-$VERSION.dmg"

xcodebuild build -quiet \
  -project Lumo.xcodeproj -scheme LumoMac -configuration Release \
  -destination 'platform=macOS,arch=arm64' \
  -derivedDataPath build/mac \
  MARKETING_VERSION="$VERSION" CURRENT_PROJECT_VERSION="$BUILD"

APP=build/mac/Build/Products/Release/Lumo.app
[ -d "$APP" ] || { echo "Build failed: $APP missing" >&2; exit 1; }
codesign --force --deep --sign - "$APP"

mkdir -p build/dmg
cp -R "$APP" build/dmg/
ln -s /Applications build/dmg/Applications
hdiutil create -quiet -volname "Lumo" -srcfolder build/dmg -ov -format UDZO "build/Lumo-$VERSION.dmg"
rm -rf build/dmg
echo "Built build/Lumo-$VERSION.dmg ($VERSION, build $BUILD)"
