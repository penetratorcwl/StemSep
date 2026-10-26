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
- Live stem preview from growing temporary files while separation runs; defaults to vocals.
- CPU and experimental Snapdragon GPU/NPU (NNAPI) backend selection with Snapdragon/arm64/API gate.
- Backend-aware cache key and requested/effective-backend metadata with visible fallback reason.
- Live progress publishes temporary stem paths and available/total frames through WorkManager.
- GitHub Actions DSP tests, separation tests and debug APK build.
- Rolling `latest` GitHub release with a directly downloadable APK.

## Verified

- `:core-dsp` compiles locally and passes 7/7 JUnit tests.
- Backend detection and backend-ID parsing have JVM tests.
- GitHub Actions `test` job passes.
- GitHub Actions `build` job passes and produces a debug APK.
- The release workflow attached `StemSep-debug.apk` to the `latest` release; the asset is 137,347,470 bytes and was downloaded and checked through `gh release download`.
- CI runs `40396988552` and `40397551805` passed both jobs, including `:separation:testDebugUnitTest` and `:app:assembleDebug`.
- The NNAPI path compiles; it has not been executed on a Snapdragon device in this environment.
- Live-preview code compiles in CI (runs `40396988552` and `40397551805`); it has not been runtime-tested on a device yet.

## Not yet verified on a physical device

The Android runtime path still needs a real-device smoke test:

- first-run model download and SHA verification;
- MediaCodec decode of common formats;
- ONNX inference on the target CPU;
- NNAPI GPU/NPU selection, FP16 output and CPU fallback;
- live vocal/instrumental/mix playback while separation runs;
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
- Live preview and final playback do not request audio focus yet.
- Live preview is tied to the Activity/ViewModel; if the process dies, it is lost even if the worker continues.
- A NNAPI session that fell back to CPU is cached under the requested NNAPI key, so a later NNAPI run can hit that cached result without retrying the accelerator.
- The current UI text is mostly English; the notification channel uses Polish strings.

## Suggested next steps

1. Install the latest APK and run a short track through the default Mobile model on CPU.
2. Run the same track with Snapdragon GPU/NPU selected; compare time, output quality and fallback behavior.
3. Verify live vocal preview play/pause/seek during separation and the automatic switch to the committed stems.
4. Verify the two exported files and check that vocals + instrumental reconstruct the source.
5. Test a cache hit, a cancelled job and a backend switch (cache key must change).
6. Force an NNAPI fallback and verify the **Requested / Active / Fallback** text and cached metadata.
7. Measure peak memory and runtime on a 3-5 minute track, including live preview while inference runs.
8. Add a notification cancel action if cancellation from the shade is required.
9. Evaluate QNN HTP with a quantized model before calling NPU support complete.
10. Consider a release build type and a stable update channel once runtime testing is complete.
