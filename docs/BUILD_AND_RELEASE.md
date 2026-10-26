# Build and release

## Local build

Requirements:

- JDK 17
- Android SDK with platform 36 and build-tools 35.0.0
- An `ANDROID_HOME` or `local.properties` pointing to the SDK

```bash
./gradlew :core-dsp:test
./gradlew :separation:testDebugUnitTest
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
- `build`: installs Android platform 36 and build-tools 35.0.0, decodes the stable signing keystore, runs `:separation:testDebugUnitTest`, builds `:app:assembleDebug`, verifies the APK with `apksigner`, and uploads it as the `stemsep-debug` artifact.

Both jobs run on pushes and pull requests. The APK artifact is temporary and expires according to the repository's artifact retention setting.

## Direct APK release

On every push to `master` or `main`, the build job also publishes the APK to a rolling GitHub release tagged `latest`.

- Release page: <https://github.com/penetratorcwl/StemSep/releases/tag/latest>
- Direct APK: <https://github.com/penetratorcwl/StemSep/releases/latest/download/StemSep-debug.apk>

The repository is private, so open these links while signed in to the GitHub account that can access it. The release asset is named `StemSep-debug.apk` so it can be downloaded without unpacking the Actions artifact ZIP. The release is updated with `gh release upload --clobber`.

The build job has `contents: write` so the release step can create and update the release; the test job remains read-only. If the repository's Actions permissions are restricted to read-only, change the repository setting to allow GitHub Actions to create releases, or use a maintainer token with release permission.

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

The debug APK is signed with a dedicated stable keystore stored only in GitHub Actions secrets. This keeps the certificate stable across builds so later APKs can update an installed app without uninstalling it.

Required secrets:

- `SIGNING_KEYSTORE_BASE64` — base64-encoded JKS/PKCS12 keystore;
- `SIGNING_STORE_PASSWORD`;
- `SIGNING_KEY_ALIAS` — `stemsep`;
- `SIGNING_KEY_PASSWORD`.

The workflow writes the decoded keystore to `$RUNNER_TEMP`, sets `STEMSEP_KEYSTORE_FILE`, `STEMSEP_KEYSTORE_PASSWORD`, `STEMSEP_KEY_ALIAS` and `STEMSEP_KEY_PASSWORD`, and uses `github.run_number` as `STEMSEP_VERSION_CODE`. It then runs:

```bash
apksigner verify --print-certs app/build/outputs/apk/debug/*.apk
```

The current certificate SHA-256 is:

```text
0f5e3836bdb644bb0a48a53ca56aa0cc5487d0bb4a0a56be729ccfb0b9e105d2
```

Builds before commit `035f3a7` used an ephemeral CI debug key. **Uninstall `app.stemsep.debug` once before installing a stable-key build.** After that, later stable-key builds should install as updates. If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the installed APK has a different certificate; uninstall it once and install the new APK.

The keystore must never be committed. Do not print the password or base64 secret in logs. If the signing secret is missing on a push build, the workflow fails; pull-request builds may fall back to an ephemeral debug key. Local builds also fall back to the normal Android debug key.

The release remains a debug build type (`app.stemsep.debug`) suitable for sideloading and development testing, not a Play Store production build.

## Common failures

- `Unsupported class file major version`: use JDK 17 for Gradle/AGP, not a much newer JDK.
- Missing Android SDK: install platform 36 and build-tools 35.0.0.
- Missing model at runtime: the first separation requires network access; check the download URL and the pinned SHA-256.
- Release step fails: verify `permissions: contents: write` and the repository's Actions workflow permissions.
- Signing step fails: verify all four `SIGNING_*` secrets exist and `SIGNING_KEYSTORE_BASE64` decodes to a valid keystore. A push build intentionally fails instead of silently producing a differently signed APK.
- Update install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: uninstall the old `app.stemsep.debug` once, then install the stable-key APK.
- APK is very large: ONNX Runtime native libraries are bundled for multiple ABIs. This is expected for the current debug build. The QNN runtime is not included yet; adding it would increase the APK substantially.
