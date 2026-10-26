# Project status

Last updated: 2026-10-26

## Implemented

- Gradle multi-module Android project.
- Pure JVM MDX DSP pipeline with unit tests.
- WAV reader/writer and raw interleaved float stem I/O.
- MediaExtractor/MediaCodec audio decoding to 44.1 kHz stereo float PCM.
- ONNX Runtime MDX inference.
- Single-model catalog (`UVR_MDXNET_9482`) with a pinned size and SHA-256.
- Download fallback for the default model.
- Streaming hash, cache lookup, cache metadata and atomic commit.
- WorkManager foreground separation job with progress, cancellation and error data.
- Compose Material 3 UI with System/Light/Dark modes and Material You, Violet, Teal and Graphite palettes.
- SAF source picker and SAF export tree picker.
- WAV and FLAC export.
- Single-AudioTrack stem mixer with vocals/instrumental/mix modes, seek and vocal gain.
- Live stem preview from growing temporary files while separation runs; defaults to vocals.
- CPU and experimental Snapdragon GPU/NPU (NNAPI) backend selection with Snapdragon/arm64/API gate.
- Backend-aware cache key and requested/effective-backend metadata with visible fallback reason.
- Live progress publishes temporary stem paths, available/total frames, backend status and inference statistics through WorkManager.
- Compute card with persisted CPU thread count (1..reported cores) and an inline NNAPI "Allow CPU fallback" switch.
- Compute stats card with CPU/GPU/inference sparklines, NPU status and a swipe-dismiss detail bottom sheet with current/average/peak values.
- Settings screen for theme mode and color palette, persisted across launches.
- Splash screen plus an X-style launch zoom where the app icon scales beyond the screen and reveals the UI.
- Stable CI signing key with `versionCode` derived from `github.run_number`.
- GitHub Actions DSP tests, separation tests, APK build, signature verification and release publishing.
- Rolling `latest` GitHub release with a directly downloadable APK.

## Verified

- `:core-dsp` compiles locally and passes 7/7 JUnit tests.
- Backend detection and backend-ID parsing have JVM tests.
- GitHub Actions `test` job passes.
- GitHub Actions `build` job passes and produces a debug APK.
- The release workflow attaches `StemSep-debug.apk` to the `latest` release; the asset is published and has been downloaded/checked through `gh release download`. Exact APK size varies by build.
- CI runs `40396988552` and `40397551805` passed both jobs, including `:separation:testDebugUnitTest` and `:app:assembleDebug`.
- Stable signing passed CI run `40400420284`; `apksigner` reports certificate SHA-256 `0f5e3836bdb644bb0a48a53ca56aa0cc5487d0bb4a0a56be729ccfb0b9e105d2`.
- Compute controls, stats, splash and UI changes passed CI run `40400848607`; the APK also verified with the stable certificate.
- Model cleanup, themes/settings, stats charts/bottom sheet and launch zoom passed CI run `40406906028`; the APK verified with the stable certificate and the `latest` release asset is `137,451,530` bytes.
- The NNAPI path compiles; it has not been executed on a Snapdragon device in this environment.
- Live-preview code compiles in CI; it has not been runtime-tested on a device yet.

## Not yet verified on a physical device

The Android runtime path still needs a real-device smoke test:

- first-run model download and SHA verification;
- MediaCodec decode of common formats;
- ONNX inference on the target CPU;
- NNAPI GPU/NPU selection, FP16 output, Balanced fallback and Accelerator-only failure;
- live vocal/instrumental/mix playback while separation runs;
- compute settings persistence, CPU usage, GPU sysfs availability and inference timing;
- stats sparklines, detail bottom sheet, and swipe-down dismissal;
- theme switching, Material You dynamic colors, and settings persistence;
- splash handoff and X-style launch zoom timing;
- cache hit after a successful run;
- playback, seek and mode switching;
- WAV/FLAC export into a SAF directory;
- cancellation and notification behavior;
- memory pressure with a long track.

## Known limitations

- Development debug build with a stable debug key; not a Play Store production build.
- The model is not bundled and requires network access on first use.
- No audio waveform or duration display before separation.
- No background playback service; playback is tied to the Activity/ViewModel lifetime.
- The cache is in `cacheDir`, so Android may evict it under storage pressure.
- The notification has no cancel action yet; cancellation is available in the app UI.
- The Snapdragon GPU/NPU path is experimental. NNAPI does not expose a GPU-only/NPU-only switch, and unsupported nodes may fall back to CPU in Balanced mode.
- GPU/NPU utilization and core counts are not publicly exposed by Android. GPU sysfs sampling is best-effort; NPU utilization is unavailable.
- CPU statistics are the process's share of total CPU capacity, not a per-core utilization graph.
- Stats history is in memory only; it is not persisted across app restarts.
- CPU thread count is not part of the cache key, so a cache hit does not rerun inference with new thread settings.
- QNN HTP NPU is not enabled because the current float32 model would need quantization and calibration.
- Live preview and final playback do not request audio focus yet.
- Live preview is tied to the Activity/ViewModel; if the process dies, it is lost even if the worker continues.
- A NNAPI session that fell back to CPU is cached under the requested NNAPI key, so a later NNAPI run can hit that cached result without retrying the accelerator.
- The current UI text is mostly English; the notification channel uses Polish strings.

## Suggested next steps

1. Uninstall the old `app.stemsep.debug` once, install the stable-key APK, then verify a later build installs as an update.
2. Run a short track through the default UVR_MDXNET_9482 model on CPU and verify the launch zoom, stats sparklines and detail sheet.
3. Change CPU worker threads and verify the stats card, time and thermal behavior.
4. Change theme mode and palette, then verify settings persistence and Material You colors.
5. Run the same track with Snapdragon GPU/NPU Balanced; compare time, output quality and fallback behavior.
6. Switch to Accelerator-only and verify that a failed NNAPI session produces an error instead of silently using CPU.
7. Verify live vocal preview play/pause/seek during separation and the automatic switch to the committed stems.
8. Verify the two exported files and check that vocals + instrumental reconstruct the source.
9. Test a cache hit, a cancelled job, a backend switch and an accelerator-profile switch (cache key must change).
10. Force an NNAPI fallback and verify the **Requested / Active / Fallback** text and cached metadata.
11. Measure peak memory and runtime on a 3-5 minute track, including live preview and stats sampling while inference runs.
12. Add a notification cancel action if cancellation from the shade is required.
13. Evaluate QNN HTP with a quantized model before calling NPU support complete.
