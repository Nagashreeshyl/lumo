# Releasing a new version

Releases are built by GitHub Actions and delivered to SideStore through `altstore.json`.

1. Push your changes to `main`.
2. On GitHub: **Actions → Release → Run workflow**, then enter the version (for example `0.1.8`).
3. The workflow:
   - runs the tests on an iOS simulator
   - builds an unsigned `Nive.ipa` (`build_ipa.sh`)
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
./build_ipa.sh 0.1.8 9      # version, build number → build/Nive.ipa (unsigned)
```
