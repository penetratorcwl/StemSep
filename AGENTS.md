# AGENTS.md

Project context for AI coding agents working on StemSep.

## Project

StemSep is an Android prototype for on-device vocal/instrumental separation.

- Repository: `penetratorcwl/StemSep` (private)
- Default branch: `master`
- Application ID: `app.stemsep`
- Debug application ID: `app.stemsep.debug`
- Min SDK: 26
- Compile/target SDK: 36
- UI: Kotlin + Jetpack Compose + Material 3, dark theme
- Separation: UVR MDX-Net ONNX models, stereo 44.1 kHz

The separation engine is intentionally separated from the UI so it can later be reused by BitChord.

## Read these first

1. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
2. [`docs/MODELS.md`](docs/MODELS.md)
3. [`docs/BUILD_AND_RELEASE.md`](docs/BUILD_AND_RELEASE.md)
4. [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md)

## Repository layout

```text
core-dsp/     pure JVM DSP and tests; no Android imports
separation/   Android library: decoder, ONNX, cache, export, player
app/          Compose UI, ViewModel, WorkManager, SAF
docs/         technical documentation
.github/      CI and APK release workflow
```

Important files:

- `core-dsp/src/main/kotlin/app/stemsep/dsp/MdxConfig.kt`
- `core-dsp/src/main/kotlin/app/stemsep/dsp/MdxStft.kt`
- `core-dsp/src/main/kotlin/app/stemsep/dsp/MdxStreamingSeparator.kt`
- `core-dsp/src/main/kotlin/app/stemsep/dsp/StereoResampler.kt`
- `core-dsp/src/test/kotlin/app/stemsep/dsp/CoreDspTest.kt`
- `separation/src/main/kotlin/app/stemsep/separation/ModelCatalog.kt`
- `separation/src/main/kotlin/app/stemsep/separation/SeparationPipeline.kt`
- `separation/src/main/kotlin/app/stemsep/separation/StemCache.kt`
- `separation/src/main/kotlin/app/stemsep/separation/StemExporter.kt`
- `separation/src/main/kotlin/app/stemsep/separation/StemPlayer.kt`
- `app/src/main/kotlin/app/stemsep/ui/MainViewModel.kt`
- `app/src/main/kotlin/app/stemsep/work/SeparationWorker.kt`
- `app/src/main/kotlin/app/stemsep/ui/StemSepScreen.kt`
- `.github/workflows/android.yml`

## Build and test commands

```bash
./gradlew :core-dsp:test
./gradlew :app:assembleDebug
```

CI uses JDK 17, Android platform 36 and build-tools 35.0.0.

The author's Termux environment may not have an Android SDK. GitHub Actions is the authoritative Android build. Do not replace CI with a local build. On that host, the default Java 21 installation has a broken `javac`; JDK 25 works for `kotlinc`, but Gradle/AGP expects JDK 17. This is an environment issue, not a reason to change project versions.

## Runtime pipeline

```text
SAF URI
  -> SHA-256 source hash
  -> cache lookup
  -> model download + SHA-256 verification
  -> MediaExtractor/MediaCodec decode to 44.1 kHz stereo f32
  -> MDX STFT + ONNX inference + overlap-add
  -> vocals.f32 + instrumental.f32 + meta.json
  -> cache commit
  -> AudioTrack mixer / SAF export
```

Cache key:

```text
<source-sha256>-<model-id>-v<separator-version>
```

The cache is under `context.cacheDir/stems`. A valid entry has both stem files exactly `frameCount * 8` bytes and a matching separator version.

## Models

Default: `mdx-9482` (`UVR_MDXNET_9482`)

- 29,704,436 bytes
- SHA-256 `f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184`
- Tensor `[1, 4, 2048, 256]`
- fft 6144, trim 3072, compensation 1.035, overlap 0.10

Optional: `kim-vocal-2` (`Kim_Vocal_2`)

- 66,759,214 bytes
- SHA-256 `ce74ef3b6a6024ce44211a07be9cf8bc6d87728cc852a68ab34eb8e58cde9c8b`
- Tensor `[1, 4, 3072, 256]`
- fft 7680, trim 3840, compensation 1.009, overlap 0.10

Never commit `.onnx` files or other model weights. Downloads must use HTTPS and the pinned SHA-256.

## DSP invariants

- Stereo, 44.1 kHz.
- Periodic Hann, `center = true`, reflect padding of `n_fft / 2`.
- Hop 1024; chunk 261,120 samples.
- Tensor channel order: L-real, L-imag, R-real, R-imag.
- Zero the first three input frequency bins when `zeroLowBins` is true.
- Instrumental = mixture - compensated vocals.
- Vocals + instrumental must reconstruct the mixture.
- Do not change STFT, chunking or overlap math without updating tests and `ModelCatalog.SEPARATOR_VERSION`.

## Android/UI invariants

- One `AudioTrack` for both stems; do not create independent players per stem.
- Seek uses a generation counter to invalidate in-flight reads.
- WorkManager uses unique work `stemsep-separation`.
- The worker is a `CoroutineWorker`; `getForegroundInfo()` is `suspend`.
- The foreground service type is `dataSync`; manifest permissions must stay consistent.
- Source audio and export tree use SAF; do not replace with broad storage permissions.
- The player and cache must be released when the source/model changes.
- Keep model inference on `Dispatchers.IO` or a worker, never on the main thread.

## Release process

Every push to `master`/`main`:

1. Runs `:core-dsp:test`.
2. Builds `:app:assembleDebug`.
3. Uploads the Actions artifact.
4. Updates the rolling `latest` GitHub release with `StemSep-debug.apk`.

Direct APK:

```text
https://github.com/penetratorcwl/StemSep/releases/latest/download/StemSep-debug.apk
```

The release step uses `GITHUB_TOKEN` with job-level `contents: write`. Do not commit a token. If release publishing fails, check the repository's Actions permissions before changing the workflow.

## Current status

- DSP: 7/7 local JUnit tests pass.
- CI: DSP test and debug APK build pass.
- Release: latest debug APK is published on GitHub Releases.
- Runtime on a physical phone: not yet fully verified.
- Known gaps: no notification cancel action, no background playback service, debug signing only, cache in `cacheDir`.

## Working rules

- Do not commit or push unless the user explicitly requests it.
- Do not commit secrets, keystores, model weights or generated APKs.
- Prefer small, focused changes and preserve existing style.
- Do not add comments unless they are necessary for non-obvious invariants.
- After DSP changes, run `:core-dsp:test`.
- After Android changes, rely on GitHub Actions for the authoritative build.
- Update this file and `docs/` when architecture, model configuration or release behavior changes.
