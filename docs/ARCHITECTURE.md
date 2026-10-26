# Architecture

## Module map

```text
:core-dsp      pure JVM DSP, no Android dependencies
:separation    Android library: decode, ONNX, cache, export, playback
:app           Compose UI, ViewModel, WorkManager, SAF entry points
```

### `:core-dsp`

- `MdxConfig` is the single source of truth for model geometry and chunking.
- `MdxStft` performs the MDX-compatible STFT and inverse STFT.
- `MdxStreamingSeparator` drives chunking, overlap-add and instrumental reconstruction.
- `WavIO` / `WavStreamWriter` read and write PCM WAV, including non-seekable output streams.
- `FloatPcmFileSource` / `FloatPcmFileWriter` store interleaved stereo `float32` stems.
- `StereoResampler` converts arbitrary source rates to 44.1 kHz.
- `StemMixer` and `BlockStereoSink` support the playback and write paths.
- Tests live in `core-dsp/src/test/kotlin/app/stemsep/dsp/CoreDspTest.kt`.

### `:separation`

- `ModelCatalog` contains the supported model and its exact inference config.
- `ModelDownloader` downloads with a fallback URL, verifies byte length and SHA-256, and writes atomically.
- `AudioDecoder` uses `MediaExtractor` + `MediaCodec` and emits interleaved stereo float PCM at 44.1 kHz.
- `ComputeBackend` and `ComputeCapabilityDetector` model CPU vs Snapdragon NNAPI support.
- `OnnxMdxEngine` owns an ONNX Runtime session, selects the execution provider, applies the CPU thread count and NNAPI accelerator policy, exposes an `MdxInference` callback, records inference timing, and can recreate the CPU session at chunk boundaries when Auto changes the thread count.
- `AutoThreadController` implements the Auto thread policy from the live playback lead.
- `StemCache` owns the on-disk stem cache and metadata, including requested/effective backends, fallback reason, CPU thread count and accelerator mode.
- `SeparationPipeline` orchestrates hash -> cache lookup -> model -> decode -> inference -> commit, publishes growing stem files for live preview, forwards inference statistics, and applies Auto thread changes between chunks.
- `StemExporter` writes WAV or FLAC through the Storage Access Framework.
- `StemPlayer` mixes two committed stem files into one `AudioTrack` and supports seek/mode/gain.
- `LiveStemPlayer` reads growing temporary stem files while separation is running.
- `LiveStemPreview` describes the temporary files and available/total frames.

### `:app`

- `StemSepApp` creates the singleton `AppContainer`.
- `AppContainer` owns `StemCache`, `SeparationPipeline` and `StemExporter`.
- `MainActivity` installs the AndroidX splash screen, hosts Compose, applies the selected theme, requests `POST_NOTIFICATIONS` on API 33+, and updates `AppVisibilityTracker` in `onStart`/`onStop`.
- `MainViewModel` exposes `UiState` and `AppSettings`, persists compute and theme settings, observes WorkManager, keeps rolling CPU/GPU/inference histories plus power/energy, owns the live/final players and export coroutine, and updates `LivePlaybackTracker` from the live player.
- `LivePlaybackTracker` exposes the current live playback position, buffered frames and sample rate to the worker for Auto thread control.
- `ComputeStatsSampler` samples process CPU usage, interval GPU sysfs values, battery power and run energy off the main thread.
- `SeparationWorker` is a `CoroutineWorker`; it forwards live-preview metadata, backend status and inference statistics through WorkManager progress, and promotes itself to a data-sync foreground worker only after the app leaves the foreground. The notification channel is normal importance, not silent.
- `StemSepRoot` hosts the main screen/settings crossfade and the X-style launch zoom overlay.
- `StemSepScreen` is the Material 3 main UI: source, model, compute settings, stats with sparklines and a detail bottom sheet, progress, live stem preview, final stem player and export.
- `SettingsScreen` selects System/Light/Dark mode and Material You/Violet/Teal/Graphite palettes; preferences are stored in `stemsep-settings`.

## Separation flow

1. The user selects an audio document with `ActivityResultContracts.OpenDocument`.
2. The URI permission is persisted for reading. A unique WorkManager job is enqueued.
3. `SeparationWorker` reads the requested `ComputeBackend`, CPU thread setting (`0` means Auto) and accelerator policy, and calls `SeparationPipeline`. It waits until `AppVisibilityTracker` reports that the app is in the background before calling `setForeground`.
4. The pipeline streams the source through SHA-256. The cache key is:

   ```text
   <source-sha256>-<model-id>-<backend>-<profile>-v<separator-version>
   ```

5. A cache hit returns immediately with `cached = true`.
6. On a miss, the model is downloaded if needed and verified before use.
7. `AudioDecoder` writes a temporary interleaved stereo `float32` file at 44.1 kHz.
8. `MdxStreamingSeparator` runs the ONNX model over 261,120-sample chunks and writes:
   - `vocals.f32`
   - `instrumental.f32`
9. After each flushed chunk, the pipeline publishes `LiveStemPreview` (temporary paths, available frames, total frames, sample rate) plus inference timing/thread statistics. The worker forwards them through WorkManager progress; `MainViewModel` opens `LiveStemPlayer` when the first frames are available. While live playback is active, the pipeline reads `LivePlaybackTracker` at chunk boundaries and may recreate the CPU session with a different thread count to keep about five seconds buffered.
10. `StemCache` writes metadata (`requestedBackend`, `effectiveBackend`, `fallbackReason`, `cpuThreads`, `acceleratorMode`) and atomically moves the temporary directory into the cache.
11. The worker returns the cache key; `MainViewModel` releases the live player and opens the committed stems in `StemPlayer`.

## DSP contract

The catalog model is stereo 44.1 kHz MDX-Net.

- Periodic Hann window.
- `center = true` with reflect padding of `n_fft / 2`.
- Hop length: 1024 samples.
- Chunk length: `segmentSamples = hop * (timeFrames - 1) = 261,120` samples.
- STFT tensor layout: `[1, 4, frequencyBins, timeFrames]`.
- Channels: left real, left imaginary, right real, right imaginary.
- Frequency bins above `frequencyBins` are discarded for the model input.
- When `zeroLowBins` is true, the first three input bins are zeroed.
- Inverse STFT divides by the accumulated squared window.
- The separator uses symmetric Hann overlap-add for chunk stitching.
- Vocals are compensated by the model-specific factor.
- Instrumental is always `mixture - compensated vocals`, so vocals + instrumental reconstruct the mixture.

Model-specific values are in [`MODELS.md`](MODELS.md) and `ModelCatalog.kt`.

## Compute backends

- CPU is the default: ONNX Runtime CPU EP, one inter-op thread and a user-selectable intra-op thread count (Auto or 1..reported cores). Auto starts at four threads and adjusts between chunks to keep about five seconds of live playback buffered.
- `SNAPDRAGON_NNAPI` is offered only on Qualcomm Snapdragon, `arm64-v8a`, API 27+.
- NNAPI requests FP16 and delegates operation placement to the Android/Qualcomm driver. The driver may use GPU, NPU or CPU per node.
- The inline **Allow CPU fallback** switch maps to Balanced (on) or Accelerator-only (off). Balanced retries on CPU if NNAPI session creation fails; Accelerator-only adds `CPU_DISABLED` and does not retry, so the job fails instead of silently using CPU.
- The Java API cannot report per-node fallback inside a successful NNAPI session.
- The UI exposes CPU thread count and the NNAPI CPU-fallback switch; the CPU thread control is hidden when NNAPI is selected with CPU fallback disabled. GPU/NPU core counts and reliable per-unit utilization are not available through NNAPI.
- QNN HTP is not enabled because the current float32 model is not quantized; see [`COMPUTE_BACKENDS.md`](COMPUTE_BACKENDS.md).

## Cache invariants

- Cache root: `context.cacheDir/stems`.
- Work root: `context.cacheDir/stems-work`.
- Each cache entry contains `vocals.f32`, `instrumental.f32` and `meta.json`.
- `frameCount` must be positive.
- Each stem file must be exactly `frameCount * 8` bytes (2 channels × 4 bytes).
- `separatorVersion` must match `ModelCatalog.SEPARATOR_VERSION`; a mismatch is a cache miss.
- The requested backend and accelerator profile are part of the cache key; `meta.json` stores requested/effective backends, optional fallback reason, CPU thread count and accelerator mode. CPU thread count is not part of the key because it does not change the audio.
- Temporary directories are deleted when separation fails or is cancelled; on success they are renamed into the cache.

## Playback

- While a job runs, `LiveStemPlayer` reads the growing temporary stems and starts in **Vocals** mode. It is released on job completion/failure/cancel before the committed cache entry is opened.
- One `AudioTrack` in float PCM mode is shared by both stems in each player.
- The live and final playback cards put play/pause in the top-right, omit the separate "Mode" label, and stretch the three mode chips across the row. The live player starts in Vocals.
- The player reads fixed-size chunks and mixes according to `StemMode`:
  - `VOCALS`: vocals only.
  - `INSTRUMENTAL`: instrumental only.
  - `MIX`: both stems, with a separate vocal gain.
- Seeking sets a frame position and increments a generation counter so an in-flight read cannot overwrite the new position.
- `StemPlayer` and `LiveStemPlayer` are closed when the source/model/backend changes and in `MainViewModel.onCleared()`.
- Live preview uses WorkManager progress keys `preview_vocals`, `preview_instrumental`, `preview_available`, `preview_total` and `preview_sample_rate`.
- Compute progress uses `effective_backend`, `fallback_reason`, `inference_ms`, `inference_chunks` and `worker_threads`.

## Themes and settings

- `ThemeMode` is System, Light or Dark. System follows `isSystemInDarkTheme()`.
- `ThemePalette` is Material You, Violet, Teal or Graphite. Material You uses dynamic system colors on API 31+ and falls back to Violet on older devices.
- Theme preferences live in `SharedPreferences` under `stemsep-settings` and are exposed by `MainViewModel.settings`.
- The app updates status/navigation bar icon appearance from the active Compose color scheme.
- The launch overlay is a Compose zoom/fade of `ic_launcher_foreground`; it is shown once per Activity saved-state instance after the AndroidX splash.

## Export

- The export directory is selected with `ActivityResultContracts.OpenDocumentTree`.
- WAV is written directly through a non-seekable `OutputStream` with a precomputed header.
- FLAC is encoded to a temporary file in `cacheDir`, then copied to the SAF document.
- Export progress is split evenly between the vocals and instrumental files.
- Existing files with the same name are deleted before creating the new document.

## Cancellation and threading

- All pipeline work runs on `Dispatchers.IO`.
- Every long loop calls `ensureActive()` and/or the injected `isCancelled` callback.
- WorkManager cancellation maps to `isStopped`; the worker rethrows `CancellationException`.
- The UI shows progress and phase strings from WorkManager progress data.
- The notification is updated only when the phase or whole percentage changes, and only after the foreground service has started. The foreground notification is delayed until the app leaves the foreground and uses `IMPORTANCE_DEFAULT` on `stemsep-separation-v2`.
- CPU/GPU statistics and battery power/energy are sampled every 750 ms on `Dispatchers.IO` while a job runs; the sampler never touches UI state from a background thread. GPU counters are differenced between samples. The UI keeps 90 points for sparklines and the detail sheet.

## Important invariants for changes

- Do not change STFT/chunk math without updating `MdxConfig` and running the DSP tests.
- Keep the cache key tied to the source hash, model ID, backend, accelerator profile and separator version.
- Keep CPU thread count out of the cache key; it changes performance, not audio. Auto is the `cpu_threads = 0` sentinel and must not leak into cache identity.
- Keep the foreground notification delayed until the app is backgrounded; the channel must stay normal importance.
- Do not claim reliable GPU/NPU utilization or a GPU/NPU core count; NNAPI does not expose them.
- Never commit `.onnx` files or model weights.
- Keep model downloads on HTTPS and verify the pinned SHA-256.
- Do not introduce a second AudioTrack unless the player is redesigned; synchronization depends on one track.
- Keep the separation engine independent of Compose and WorkManager where possible; live-preview paths travel through generic progress callbacks.
- Do not expose partial PCM through the cache before commit; live preview reads the work directory directly.
