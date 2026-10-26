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

## Documentation protocol

Documentation is part of "done". Before changing code:

1. Read [`docs/README.md`](docs/README.md) and the docs relevant to the change.
2. Check [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) for known gaps; do not trust an old chat summary over the files.
3. After the change, update the affected docs, `README.md`, and this file in the same commit.
4. In the final response, state which docs were updated and what remains unverified.

When context is compacted, preserve the docs paths, the current cache/version semantics, and the backend limitations in the summary. A fresh agent must re-read the docs before editing.

## Read these first

1. [`docs/README.md`](docs/README.md)
2. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
3. [`docs/MODELS.md`](docs/MODELS.md)
4. [`docs/COMPUTE_BACKENDS.md`](docs/COMPUTE_BACKENDS.md)
5. [`docs/PLAYBACK.md`](docs/PLAYBACK.md)
6. [`docs/BUILD_AND_RELEASE.md`](docs/BUILD_AND_RELEASE.md)
7. [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md)

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
- `separation/src/main/kotlin/app/stemsep/separation/ComputeBackend.kt`
- `separation/src/main/kotlin/app/stemsep/separation/OnnxMdxEngine.kt`
- `separation/src/main/kotlin/app/stemsep/separation/SeparationPipeline.kt`
- `separation/src/main/kotlin/app/stemsep/separation/StemCache.kt`
- `separation/src/main/kotlin/app/stemsep/separation/StemExporter.kt`
- `separation/src/main/kotlin/app/stemsep/separation/StemPlayer.kt`
- `separation/src/main/kotlin/app/stemsep/separation/LiveStemPlayer.kt`
- `separation/src/main/kotlin/app/stemsep/separation/LiveStemPreview.kt`
- `app/src/main/kotlin/app/stemsep/ui/MainViewModel.kt`
- `app/src/main/kotlin/app/stemsep/ui/ComputeStatsSampler.kt`
- `app/src/main/kotlin/app/stemsep/work/SeparationWorker.kt`
- `app/src/main/kotlin/app/stemsep/ui/StemSepScreen.kt`
- `.github/workflows/android.yml`

## Build and test commands

```bash
./gradlew :core-dsp:test
./gradlew :separation:testDebugUnitTest
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
  -> MDX STFT + ONNX inference (CPU or Snapdragon NNAPI) + overlap-add
  -> vocals.f32 + instrumental.f32 (flushed progressively)
  -> live preview reads growing files; WorkManager publishes paths/frames/stats
  -> meta.json (requested/effective backend, fallback, CPU threads, accelerator mode)
  -> cache commit
  -> AudioTrack mixer / SAF export

Live preview starts after the first separated chunk and defaults to vocals. It is released before the committed stems are opened.
```

Cache key:

```text
<source-sha256>-<model-id>-<requested-backend>-<profile>-v<separator-version>
```

`SEPARATOR_VERSION` is `3`; `profile` is `balanced` or `accelerator` (CPU always `balanced`). The cache is under `context.cacheDir/stems`; work files are under `context.cacheDir/stems-work`. A valid entry has both stem files exactly `frameCount * 8` bytes and a matching separator version. Metadata stores requested/effective backends, an optional fallback reason, the CPU thread count and the accelerator mode. CPU thread count is not part of the key because it does not change the audio.

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

## Compute backends

- CPU: ONNX Runtime CPU EP with a user-selectable intra-op thread count (default 4, capped at reported cores) and one inter-op thread. This is the default and the existing benchmark path.
- Snapdragon GPU/NPU: Android NNAPI EP, gated by Qualcomm Snapdragon + `arm64-v8a` + API 27+. It requests FP16; the Android/Qualcomm driver decides whether GPU, NPU or CPU handles each node.
- NNAPI Balanced mode retries on CPU if session creation fails. Accelerator-only mode adds `CPU_DISABLED` and does not retry, so the job fails instead of silently using CPU. Unsupported nodes may fall back per operation even when the session succeeds; the Java API cannot report that.
- Compute stats show process CPU share and average inference time. GPU sysfs busy values are best-effort; Android does not expose reliable GPU/NPU utilization or core counts.
- QNN HTP is not enabled. The current models are float32; QNN HTP requires quantized models and calibration. QNN GPU is possible but adds a large runtime and is not currently packaged.
- See [`docs/COMPUTE_BACKENDS.md`](docs/COMPUTE_BACKENDS.md) before changing providers or cache keys.

## Android/UI invariants

- One `AudioTrack` for both stems; do not create independent players per stem.
- Seek uses a generation counter to invalidate in-flight reads.
- WorkManager uses unique work `stemsep-separation`.
- The worker is a `CoroutineWorker`; `getForegroundInfo()` is `suspend`.
- The foreground service type is `dataSync`; manifest permissions must stay consistent.
- Source audio and export tree use SAF; do not replace with broad storage permissions.
- The live and final stem players must be released when the source/model/backend changes, on job success/failure/cancel, and in `MainViewModel.onCleared()`.
- Live preview reads the worker's growing temporary stem files; it must not read a partial file after the work directory is deleted.
- Keep model inference on `Dispatchers.IO` or a worker, never on the main thread.
- GPU and NPU are not separate options: NNAPI delegates to the Qualcomm driver. Show requested/effective backend and fallback reason; do not claim a specific NPU/GPU placement.
- CPU thread count is a real setting (1..reported cores). GPU/NPU core count is not controllable through NNAPI; the Accelerator-only policy only disables CPU fallback.
- Compute stats sampling must stay off the main thread. Do not present sysfs GPU values as guaranteed.
- The app uses the AndroidX splash screen and a short Compose fade/slide entrance; keep the post-splash theme pointing at `Theme.StemSep`.

## Release process

Every push to `master`/`main`:

1. Runs `:core-dsp:test`.
2. Decodes the stable signing keystore from `SIGNING_KEYSTORE_BASE64`.
3. Builds `:app:assembleDebug` with `STEMSEP_VERSION_CODE=github.run_number`.
4. Verifies the APK certificate with `apksigner`.
5. Uploads the Actions artifact.
6. Updates the rolling `latest` GitHub release with `StemSep-debug.apk`.

Direct APK:

```text
https://github.com/penetratorcwl/StemSep/releases/latest/download/StemSep-debug.apk
```

The release step uses `GITHUB_TOKEN` with job-level `contents: write`. Do not commit a token. If release publishing fails, check the repository's Actions permissions before changing the workflow.

Signing secrets are `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` (`stemsep`) and `SIGNING_KEY_PASSWORD`. The stable certificate SHA-256 is `0f5e3836bdb644bb0a48a53ca56aa0cc5487d0bb4a0a56be729ccfb0b9e105d2`. Never commit the keystore or print the secrets. Builds before `035f3a7` used an ephemeral key, so users must uninstall `app.stemsep.debug` once before installing a stable-key APK. Push builds fail if the signing secret is missing; pull-request builds may use an ephemeral debug key.

## Current status

- DSP: 7/7 local JUnit tests pass.
- Backend detection/ID parsing: JVM unit tests added; CI runs `:separation:testDebugUnitTest`.
- Stable signing: CI run `40400420284` passed; certificate SHA-256 `0f5e3836bdb644bb0a48a53ca56aa0cc5487d0bb4a0a56be729ccfb0b9e105d2`.
- Compute controls/stats/UI/splash: CI run `40400848607` passed; the APK verified with the stable certificate.
- Runtime on a physical phone: live preview, NNAPI, fallback, compute stats and splash timing are not yet verified on a device.
- Known gaps: no QNN HTP (float models), no notification cancel action, no background playback service, no audio focus, no reliable GPU/NPU utilization, cache in `cacheDir`.
- Live preview uses WorkManager progress paths and reads growing files; final stems replace it after commit.

## Working rules

- Do not commit or push unless the user explicitly requests it.
- Do not commit secrets, keystores, model weights or generated APKs.
- Prefer small, focused changes and preserve existing style.
- Do not add comments unless they are necessary for non-obvious invariants.
- After DSP changes, run `:core-dsp:test`.
- After backend/cache changes, run `:separation:testDebugUnitTest` in CI.
- After Android changes, rely on GitHub Actions for the authoritative build.
- Update this file and `docs/` when architecture, model configuration, backend, playback or release behavior changes.
