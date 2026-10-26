# StemSep

On-device vocal / instrumental separation for Android. The app decodes a user-selected audio file, runs a UVR MDX-Net ONNX model locally, caches both stems, and plays them through a single synchronized mixer.

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
- `:separation` — Android library: MediaExtractor/MediaCodec decoding, model catalog/downloader, ONNX Runtime inference, cache, WAV/FLAC export.
- `:app` — Compose UI, WorkManager foreground job, AudioTrack mixer/player, SAF pickers.

## Build

```bash
./gradlew :core-dsp:test
./gradlew :app:assembleDebug
```

CI runs both on every push. The debug APK is uploaded as the `stemsep-debug` artifact.

## Notes

- Minimum SDK 26, target/compile SDK 36.
- ONNX Runtime 1.28.0 CPU execution provider, 4 intra-op threads. XNNPACK and NNAPI were slower on the test device.
- The model license is not bundled; the user downloads the model directly from its source.
- FLAC export uses `java-flac-encoder` (LGPL).
