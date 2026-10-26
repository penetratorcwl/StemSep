# Project status

Last updated: 2026-10-26

## Implemented

- Gradle multi-module Android project.
- Pure JVM MDX DSP pipeline with unit tests.
- WAV reader/writer and raw interleaved float stem I/O.
- MediaExtractor/MediaCodec audio decoding to 44.1 kHz stereo float PCM.
- ONNX Runtime MDX inference.
- Two-model catalog with pinned sizes and SHA-256.
- Download fallback for the default model.
- Streaming hash, cache lookup, cache metadata and atomic commit.
- WorkManager foreground separation job with progress, cancellation and error data.
- Compose Material 3 dark UI.
- SAF source picker and SAF export tree picker.
- WAV and FLAC export.
- Single-AudioTrack stem mixer with vocals/instrumental/mix modes, seek and vocal gain.
- Source preview via `MediaPlayer` while separation runs.
- CPU and experimental Snapdragon GPU/NPU (NNAPI) backend selection with Snapdragon/arm64/API gate.
- Backend-aware cache key and effective-backend metadata.
- GitHub Actions DSP tests, separation tests and debug APK build.
- Rolling `latest` GitHub release with a directly downloadable APK.

## Verified

- `:core-dsp` compiles locally and passes 7/7 JUnit tests.
- Backend detection and backend-ID parsing have JVM tests.
- GitHub Actions `test` job passes.
- GitHub Actions `build` job passes and produces a debug APK.
- The release workflow attached `StemSep-debug.apk` to the `latest` release; the current asset is 137,331,086 bytes and was downloaded and checked through `gh release download`.
- CI run `40387537046` passed both jobs, including `:separation:testDebugUnitTest` and `:app:assembleDebug`.
- The NNAPI path compiles; it has not been executed on a Snapdragon device in this environment.

## Not yet verified on a physical device

The Android runtime path still needs a real-device smoke test:

- first-run model download and SHA verification;
- MediaCodec decode of common formats;
- ONNX inference on the target CPU;
- NNAPI GPU/NPU selection, FP16 output and CPU fallback;
- source preview playback while separation runs;
- cache hit after a successful run;
- playback, seek and mode switching;
- WAV/FLAC export into a SAF directory;
- cancellation and notification behavior;
- memory pressure with a long track.

## Known limitations

- Debug APK only; debug signing.
- The model is not bundled and requires network access on first use.
- No audio waveform or duration display before separation.
- No background playback service; playback is tied to the Activity/ViewModel lifetime.
- The cache is in `cacheDir`, so Android may evict it under storage pressure.
- The notification has no cancel action yet; cancellation is available in the app UI.
- Kim Vocal 2 can be slower than realtime on the test phone.
- The Snapdragon GPU/NPU path is experimental. NNAPI does not expose a GPU-only/NPU-only switch, and unsupported nodes may fall back to CPU.
- QNN HTP NPU is not enabled because the current float32 models would need quantization and calibration.
- The source preview is the original mix, not a live preview of partially separated stems.
- Source preview does not request audio focus yet.
- The current UI text is mostly English; the notification channel uses Polish strings.

## Suggested next steps

1. Install the latest APK and run a short track through the default Mobile model on CPU.
2. Run the same track with Snapdragon GPU/NPU selected; compare time, output quality and fallback behavior.
3. Verify source preview play/pause/seek during separation.
4. Verify the two exported files and check that vocals + instrumental reconstruct the source.
5. Test a cache hit, a cancelled job and a backend switch (cache key must change).
6. Measure peak memory and runtime on a 3-5 minute track.
7. Add a notification cancel action if cancellation from the shade is required.
8. Evaluate QNN HTP with a quantized model before calling NPU support complete.
9. Consider a release build type and a stable update channel once runtime testing is complete.
