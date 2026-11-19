# Releasing a new version

Releases are built by GitHub Actions and delivered to SideStore through `altstore.json`.

1. Push your changes to `main`.
2. On GitHub: **Actions → Release → Run workflow**, then enter the version (for example `0.1.8`).
3. The workflow:
   - runs the tests on an iOS simulator
   - builds an unsigned `Lumo.ipa` (`build_ipa.sh`)
   - publishes a GitHub release `v<version>` with the IPA attached
   - updates `altstore.json` and commits it, so SideStore shows **Update**

## When YouTube breaks playback

Playback depends on SwiftyTube's stream resolution. Bump its version in `project.yml`:

```yaml
packages:
  SwiftyTube:
    url: https://github.com/SwiftyMedia/SwiftyTube.git
    exactVersion: <new version>
```

Then run `xcodegen generate`, test, and release.

## Building locally

```bash
./build_ipa.sh 0.2.0 10     # iPhone: version, build number → build/Lumo.ipa (unsigned, for SideStore)
./build_dmg.sh 0.2.0 10     # Mac: → build/Lumo-0.2.0.dmg (Apple Silicon, ad-hoc signed)
```

Android:

```bash
cd android && ./build_apk.sh 0.3.0 30   # → build/Lumo-0.3.0.apk, signed
```

Attach all three files (IPA, DMG, APK) to the GitHub release.

### Android signing key

`build_apk.sh` signs with `~/.lumo/android-release.jks`; its password is in the macOS Keychain item `lumo-android-keystore`. The first run creates both. **Back them up.** Android only installs an update over the old app when it's signed with the same key. With a new key, everyone has to uninstall (losing their library unless they exported it) and install again.

The Android build needs JDK 21 (`brew install openjdk@21`) and the Android command-line tools (`brew install --cask android-commandlinetools`, then `sdkmanager "platforms;android-37.0" "build-tools;36.0.0"`).
