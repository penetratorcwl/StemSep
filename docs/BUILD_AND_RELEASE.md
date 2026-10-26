# Build and release

## Local build

Requirements:

- JDK 17
- Android SDK with platform 36 and build-tools 35.0.0
- An `ANDROID_HOME` or `local.properties` pointing to the SDK

```bash
./gradlew :core-dsp:test
./gradlew :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The repository is currently developed from Termux. A local Android SDK may not be present there; the authoritative build is GitHub Actions.

## GitHub Actions

Workflow: `.github/workflows/android.yml`

- `test`: runs `:core-dsp:test` on JDK 17.
- `build`: installs Android platform 36 and build-tools 35.0.0, runs `:app:assembleDebug`, and uploads the APK as the `stemsep-debug` artifact.

Both jobs run on pushes and pull requests. The APK artifact is temporary and expires according to the repository's artifact retention setting.

## Direct APK release

On every push to `master` or `main`, the build job also publishes the APK to a rolling GitHub release tagged `latest`.

- Release page: <https://github.com/penetratorcwl/StemSep/releases/tag/latest>
- Direct APK: <https://github.com/penetratorcwl/StemSep/releases/latest/download/StemSep-debug.apk>

The release asset is named `StemSep-debug.apk` so it can be downloaded without unpacking the Actions artifact ZIP. The release is updated with `gh release upload --clobber`.

The workflow grants the build job `contents: write` only for this release step. If the repository's Actions permissions are restricted to read-only, change the repository setting to allow GitHub Actions to create releases, or use a maintainer token with release permission.

### Manual release fallback

```bash
cp app/build/outputs/apk/debug/app-debug.apk /tmp/StemSep-debug.apk
gh release create latest /tmp/StemSep-debug.apk \
  --title "Latest debug build" \
  --notes "Manual debug build"
```

If the release already exists:

```bash
gh release upload latest app/build/outputs/apk/debug/app-debug.apk \
  --clobber
```

## Signing

The release currently contains a debug APK signed by the CI debug keystore. It is suitable for sideloading and development testing, not for Play Store distribution. A production release should use a dedicated upload key and a non-debug build type.

## Common failures

- `Unsupported class file major version`: use JDK 17 for Gradle/AGP, not a much newer JDK.
- Missing Android SDK: install platform 36 and build-tools 35.0.0.
- Missing model at runtime: the first separation requires network access; check the download URL and the pinned SHA-256.
- Release step fails: verify `permissions: contents: write` and the repository's Actions workflow permissions.
- APK is very large: ONNX Runtime native libraries are bundled for multiple ABIs. This is expected for the current debug build.
