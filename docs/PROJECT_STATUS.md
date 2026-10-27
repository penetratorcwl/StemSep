# Project status

Last updated: 2026-10-27

## Implemented

- Gradle multi-module Android project.
- Pure JVM MDX DSP pipeline with unit tests.
- WAV reader/writer and raw interleaved float stem I/O.
- MediaExtractor/MediaCodec audio decoding to 44.1 kHz stereo float PCM.
- ONNX Runtime MDX inference.
- Single-model catalog (`UVR_MDXNET_9482`) with a pinned size and SHA-256.
- Download fallback for the default model.
- Single temporary stem session with metadata and atomic finalization; no hash, lookup or persistent stem cache.
- Legacy `stems`/`stems-work` cache directories are removed when the session is cleared.
- WorkManager separation job with progress, cancellation and error data; the data-sync foreground notification starts only after the app leaves the foreground.
- Compose Material 3 UI with System/Light/Dark modes and Material You, Violet, Teal and Graphite palettes.
- SAF source picker and SAF export tree picker.
- WAV and FLAC export.
- Single-AudioTrack stem mixer with vocals/instrumental/mix modes, seek and vocal gain.
- Live stem preview from growing temporary files while separation runs; defaults to vocals.
- CPU and experimental Snapdragon GPU/NPU (NNAPI) backend selection with Snapdragon/arm64/API gate.
- Requested/effective-backend session metadata with visible fallback reason.
- Live progress publishes temporary stem paths, available/total frames, backend status and inference statistics through WorkManager.
- Compute card with persisted Auto/manual CPU thread count and an inline NNAPI "Allow CPU fallback" switch; the thread control is hidden in Accelerator-only mode.
- Auto CPU thread policy: starts at four threads, reads live playback lead and adjusts the ONNX CPU session at chunk boundaries to keep about five seconds buffered.
- Compute stats card with CPU/GPU/inference sparklines, NPU status, battery power/energy, and a swipe-dismiss detail bottom sheet with current/average/peak values. GPU counters are sampled as deltas.
- Settings screen for theme mode and color palette, persisted across launches; system back returns to the main screen.
- Live and final playback controls are in the card header; the mode chips fill the row, and inference detail points are drawn individually.
- Background-only normal-importance separation notification on channel `stemsep-separation-v2` with a cancel action.
- Media audio focus for live and final playback; focus loss or ducking pauses playback.
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
- Model cleanup, themes/settings, stats charts/bottom sheet and launch zoom passed CI run `40406906028`; the APK verified with the stable certificate and the `latest` release asset was `137,451,530` bytes.
- Auto CPU threads, playback header/mode chips, power/energy, GPU counter deltas, settings back handling and the delayed notification passed CI run `40413756893`; the follow-up docs-only runs `40414141571` and `40414432613` also passed both jobs. The APK verified with the stable certificate and the `latest` release asset was `137,467,914` bytes.
- Cache removal, temporary session storage, discard, notification cancel and audio focus passed CI run `40425367343`; both jobs passed, including `:separation:testDebugUnitTest` with the new storage tests. The APK verified with the stable certificate and the `latest` release asset was `137,468,310` bytes (`sha256:deec28efa1c51849bb36f085cb94ce06517f0bfc04e444b8eb3e113e8c6835eb`).
- The NNAPI path compiles; it has not been executed on a Snapdragon device in this environment.
- Live-preview code compiles in CI; it has not been runtime-tested on a device yet.

## Not yet verified on a physical device

The Android runtime path still needs a real-device smoke test:

- first-run model download and SHA verification;
- MediaCodec decode of common formats;
- ONNX inference on the target CPU;
- NNAPI GPU/NPU selection, FP16 output, Balanced fallback and Accelerator-only failure;
- live vocal/instrumental/mix playback while separation runs;
- Auto thread changes during live playback and their effect on the five-second buffer;
- compute settings persistence, CPU usage, GPU sysfs availability, power/energy and inference timing;
- notification timing: no notification while the app is visible, normal-importance notification after leaving, and WorkManager behavior for long jobs;
- stats sparklines, detail bottom sheet, and swipe-down dismissal;
- theme switching, Material You dynamic colors, and settings persistence;
- splash handoff and X-style launch zoom timing;
- leftover current-session cleanup after process death (on next run or exit);
- notification cancel action;
- audio-focus loss and ducking behavior;
- playback, seek and mode switching;
- WAV/FLAC export into a SAF directory;
- cancellation and notification behavior;
- memory pressure with a long track.

## Known limitations

- Development debug build with a stable debug key; not a Play Store production build.
- The model is not bundled and requires network access on first use.
- No audio waveform or duration display before separation.
- No background playback service; playback is tied to the Activity/ViewModel lifetime.
- The temporary session is in `cacheDir`; Android may evict it under storage pressure. It is removed on normal exit or the next separation; a process kill can leave inaccessible files until then.
- The completed stems are temporary: starting another separation, discarding the result, or leaving the app removes them; export first to keep them.
- The Snapdragon GPU/NPU path is experimental. NNAPI does not expose a GPU-only/NPU-only switch, and unsupported nodes may fall back to CPU in Balanced mode.
- GPU/NPU utilization and core counts are not publicly exposed by Android. GPU sysfs sampling is best-effort; NPU utilization is unavailable.
- CPU statistics are the process's share of total CPU capacity, not a per-core utilization graph.
- Auto thread changes recreate the ONNX CPU session and can add a short pause at a chunk boundary.
- Battery power and run energy depend on device support for current and charge counters; some phones report unavailable or coarse values.
- Stats history is in memory only; it is not persisted across app restarts.
- Separated stems are never reused across runs, so changing CPU threads or the backend always reruns inference.
- QNN HTP NPU is not enabled because the current float32 model would need quantization and calibration.
- The foreground notification is delayed until the app is backgrounded, so a separation that stays visible for more than WorkManager's normal-work limit may be stopped before a foreground service starts.
- Live preview is tied to the Activity/ViewModel; if the process dies, it is lost even if the worker continues.
- A NNAPI session that fell back to CPU is retried on the next run because completed stems are not reused.
- The current UI text is mostly English; the notification channel uses Polish strings.

## Suggested next steps

1. Uninstall the old `app.stemsep.debug` once, install the stable-key APK, then verify a later build installs as an update.
2. Run a short track through the default UVR_MDXNET_9482 model on CPU and verify the launch zoom, stats sparklines and detail sheet.
3. Change CPU worker threads and verify the stats card, time and thermal behavior.
4. Change theme mode and palette, then verify settings persistence and Material You colors.
5. Run the same track with Auto threads and live playback; verify the worker raises/lowers threads as the buffer crosses the five-second target.
6. Run the same track with Snapdragon GPU/NPU Balanced; compare time, output quality and fallback behavior.
7. Switch to Accelerator-only and verify that a failed NNAPI session produces an error instead of silently using CPU.
8. Verify live vocal preview play/pause/seek during separation and the automatic switch to the completed stems.
9. Verify the two exported files and check that vocals + instrumental reconstruct the source.
10. Test a cancelled job, a backend switch and an accelerator-profile switch; each new run must start without reusing stems.
11. Force an NNAPI fallback and verify the **Requested / Active / Fallback** text and session metadata.
12. Measure peak memory and runtime on a 3-5 minute track, including live preview and stats sampling while inference runs.
13. Verify notification cancellation from the shade.
14. Evaluate QNN HTP with a quantized model before calling NPU support complete.
