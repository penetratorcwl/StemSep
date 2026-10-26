# StemSep

On-device vocal / instrumental separation for Android. The app decodes a user-selected audio file, runs a UVR MDX-Net ONNX model locally, caches both stems, and plays them through a single synchronized mixer. While separation runs, the already-separated vocal is available as a live preview and switches to the committed stems when the job finishes. CPU worker threads and the NNAPI accelerator policy are configurable, with live CPU/inference statistics and a splash/launch animation.

## Download

The latest debug APK is attached directly to the rolling GitHub release, so there is no ZIP to unpack:

- **APK:** <https://github.com/penetratorcwl/StemSep/releases/latest/download/StemSep-debug.apk>
- **Release:** <https://github.com/penetratorcwl/StemSep/releases/tag/latest>

The first separation downloads the selected ONNX model; model weights are not bundled in the APK.

The APK uses a stable CI signing key. If you are updating from a build made before stable signing, uninstall `app.stemsep.debug` once; later builds install as updates. See [`docs/BUILD_AND_RELEASE.md`](docs/BUILD_AND_RELEASE.md).

## Models

The APK contains no model weights. The model is downloaded on first use and verified against a pinned size and SHA-256.

| Model | Size | Input | Quality / speed | Role |
|---|---:|---|---|---|
| UVR_MDXNET_9482 | 29.7 MB | `[1,4,2048,256]` | ~1.8 s per 5.92 s chunk on SM-S931B big cores | Default |
| Kim_Vocal_2 | 66.8 MB | `[1,4,3072,256]` | ~7.4-7.8 s per chunk; can be slower than realtime | Optional high quality |

Both use stereo 44.1 kHz, periodic Hann, `n_fft` 6144/7680, hop 1024, reflect padding, 261120-sample chunks, and weighted overlap-add. Instrumental is computed as `mixture - compensated vocals`, so the two stems sum back to the source.

`UVR-MDX-NET-Voc_FT` was benchmarked at ~8.2 s per chunk on the same device and is not included as a default because it offers no speed advantage over Kim.

## Architecture

- `:core-dsp` — pure JVM DSP: MDX STFT/iSTFT, streaming overlap-add, sinc resampler, WAV reader/writer, mixer. Unit tested locally and in CI.
- `:separation` — Android library: MediaExtractor/MediaCodec decoding, model catalog/downloader, ONNX Runtime inference (CPU or Snapdragon NNAPI), cache, WAV/FLAC export.
- `:app` — Compose UI, WorkManager foreground job, live stem preview, compute settings/statistics, splash/launch animation, AudioTrack mixer/player, SAF pickers.

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — modules, runtime flow, DSP and playback invariants.
- [`docs/MODELS.md`](docs/MODELS.md) — pinned models, hashes, input configurations and licensing.
- [`docs/COMPUTE_BACKENDS.md`](docs/COMPUTE_BACKENDS.md) — CPU, Snapdragon NNAPI and the QNN/NPU path.
- [`docs/PLAYBACK.md`](docs/PLAYBACK.md) — live stem preview and synchronized stem playback.
- [`docs/BUILD_AND_RELEASE.md`](docs/BUILD_AND_RELEASE.md) — local/CI builds and APK releases.
- [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) — verified behavior, limitations and next steps.
- [`AGENTS.md`](AGENTS.md) — onboarding context for AI coding agents.

## Build

```bash
./gradlew :core-dsp:test
./gradlew :separation:testDebugUnitTest
./gradlew :app:assembleDebug
```

CI runs all three on every push. The debug APK is uploaded as the `stemsep-debug` artifact and published to the rolling [`latest` release](https://github.com/penetratorcwl/StemSep/releases/tag/latest).

## Notes

- Minimum SDK 26, target/compile SDK 36.
- ONNX Runtime 1.28.0 CPU execution provider. CPU worker threads are selectable from 1 to the reported core count (default 4). Snapdragon GPU/NPU via NNAPI is available as an experimental option with Balanced or Accelerator-only CPU fallback policy; GPU and NPU are one NNAPI option because the Qualcomm driver chooses per operation. The UI shows requested/active backend, any session-creation fallback, CPU usage and average inference time. Android does not expose reliable GPU/NPU utilization or core counts. The earlier NNAPI benchmark was slower on the test phone and needs re-measuring on the target device.
- The QNN HTP NPU path is not enabled yet because the current models are float32 and HTP requires quantized models.
- The model license is not bundled; the user downloads the model directly from its source.
- FLAC export uses `java-flac-encoder` (LGPL).
