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
- GitHub Actions DSP tests and debug APK build.
- Rolling `latest` GitHub release with a directly downloadable APK.

## Verified

- `:core-dsp` compiles locally and passes 7/7 JUnit tests.
- GitHub Actions `test` job passes.
- GitHub Actions `build` job passes and produces a debug APK.
- The release workflow is designed to attach `StemSep-debug.apk` to the `latest` release.

## Not yet verified on a physical device

The Android runtime path still needs a real-device smoke test:

- first-run model download and SHA verification;
- MediaCodec decode of common formats;
- ONNX inference on the target CPU;
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
- The current UI text is mostly English; the notification channel uses Polish strings.

## Suggested next steps

1. Install the latest APK and run a short track through the default Mobile model.
2. Verify the two exported files and check that vocals + instrumental reconstruct the source.
3. Test a cache hit and a cancelled job.
4. Measure peak memory and runtime on a 3-5 minute track.
5. Add a notification cancel action if cancellation from the shade is required.
6. Consider a release build type and a stable update channel once runtime testing is complete.
