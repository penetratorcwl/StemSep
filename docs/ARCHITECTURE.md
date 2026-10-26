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

- `ModelCatalog` contains the two supported models and their exact inference configs.
- `ModelDownloader` downloads with a fallback URL, verifies byte length and SHA-256, and writes atomically.
- `AudioDecoder` uses `MediaExtractor` + `MediaCodec` and emits interleaved stereo float PCM at 44.1 kHz.
- `ComputeBackend` and `ComputeCapabilityDetector` model CPU vs Snapdragon NNAPI support.
- `OnnxMdxEngine` owns an ONNX Runtime session, selects the execution provider and exposes an `MdxInference` callback.
- `StemCache` owns the on-disk stem cache and metadata, including requested/effective backends and fallback reason.
- `SeparationPipeline` orchestrates hash -> cache lookup -> model -> decode -> inference -> commit, and publishes growing stem files for live preview.
- `StemExporter` writes WAV or FLAC through the Storage Access Framework.
- `StemPlayer` mixes two committed stem files into one `AudioTrack` and supports seek/mode/gain.
- `LiveStemPlayer` reads growing temporary stem files while separation is running.
- `LiveStemPreview` describes the temporary files and available/total frames.

### `:app`

- `StemSepApp` creates the singleton `AppContainer`.
- `AppContainer` owns `StemCache`, `SeparationPipeline` and `StemExporter`.
- `MainActivity` hosts Compose and requests `POST_NOTIFICATIONS` on API 33+.
- `MainViewModel` exposes `UiState`, observes WorkManager, owns the live/final players and export coroutine.
- `SeparationWorker` is a `CoroutineWorker` with a data-sync foreground notification; it forwards live-preview metadata and backend status through WorkManager progress.
- `StemSepScreen` is the Material 3 dark UI: source, model/backend, progress, live stem preview, final stem player and export.

## Separation flow

1. The user selects an audio document with `ActivityResultContracts.OpenDocument`.
2. The URI permission is persisted for reading. A unique WorkManager job is enqueued.
3. `SeparationWorker` promotes itself to a foreground worker, reads the requested `ComputeBackend`, and calls `SeparationPipeline`.
4. The pipeline streams the source through SHA-256. The cache key is:

   ```text
   <source-sha256>-<model-id>-<backend>-v<separator-version>
   ```

5. A cache hit returns immediately with `cached = true`.
6. On a miss, the model is downloaded if needed and verified before use.
7. `AudioDecoder` writes a temporary interleaved stereo `float32` file at 44.1 kHz.
8. `MdxStreamingSeparator` runs the ONNX model over 261,120-sample chunks and writes:
   - `vocals.f32`
   - `instrumental.f32`
9. After each flushed chunk, the pipeline publishes `LiveStemPreview` (temporary paths, available frames, total frames, sample rate). The worker forwards it through WorkManager progress; `MainViewModel` opens `LiveStemPlayer` when the first frames are available.
10. `StemCache` writes metadata (`requestedBackend`, `effectiveBackend`, `fallbackReason`) and atomically moves the temporary directory into the cache.
11. The worker returns the cache key; `MainViewModel` releases the live player and opens the committed stems in `StemPlayer`.

## DSP contract

All models are stereo, 44.1 kHz MDX-Net models.

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

- CPU is the default: ONNX Runtime CPU EP, four intra-op threads, one inter-op thread.
- `SNAPDRAGON_NNAPI` is offered only on Qualcomm Snapdragon, `arm64-v8a`, API 27+.
- NNAPI requests FP16 and delegates operation placement to the Android/Qualcomm driver. The driver may use GPU, NPU or CPU per node.
- If NNAPI session creation fails, `OnnxMdxEngine` retries on CPU and records the effective backend.
- The Java API cannot report per-node fallback inside a successful NNAPI session.
- QNN HTP is not enabled because the current float32 models are not quantized; see [`COMPUTE_BACKENDS.md`](COMPUTE_BACKENDS.md).

## Cache invariants

- Cache root: `context.cacheDir/stems`.
- Work root: `context.cacheDir/stems-work`.
- Each cache entry contains `vocals.f32`, `instrumental.f32` and `meta.json`.
- `frameCount` must be positive.
- Each stem file must be exactly `frameCount * 8` bytes (2 channels × 4 bytes).
- `separatorVersion` must match `ModelCatalog.SEPARATOR_VERSION`; a mismatch is a cache miss.
- The requested backend is part of the cache key; `meta.json` stores both requested and effective backends plus an optional fallback reason.
- Temporary directories are deleted when separation fails or is cancelled; on success they are renamed into the cache.

## Playback

- While a job runs, `LiveStemPlayer` reads the growing temporary stems and starts in **Vocals** mode. It is released on job completion/failure/cancel before the committed cache entry is opened.
- One `AudioTrack` in float PCM mode is shared by both stems in each player.
- The player reads fixed-size chunks and mixes according to `StemMode`:
  - `VOCALS`: vocals only.
  - `INSTRUMENTAL`: instrumental only.
  - `MIX`: both stems, with a separate vocal gain.
- Seeking sets a frame position and increments a generation counter so an in-flight read cannot overwrite the new position.
- `StemPlayer` and `LiveStemPlayer` are closed when the source/model/backend changes and in `MainViewModel.onCleared()`.
- Live preview uses WorkManager progress keys `preview_vocals`, `preview_instrumental`, `preview_available`, `preview_total` and `preview_sample_rate`.

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
- The notification is updated only when the phase or whole percentage changes.

## Important invariants for changes

- Do not change STFT/chunk math without updating `MdxConfig` and running the DSP tests.
- Keep the cache key tied to the source hash, model ID, backend and separator version.
- Never commit `.onnx` files or model weights.
- Keep model downloads on HTTPS and verify the pinned SHA-256.
- Do not introduce a second AudioTrack unless the player is redesigned; synchronization depends on one track.
- Keep the separation engine independent of Compose and WorkManager where possible; live-preview paths travel through generic progress callbacks.
- Do not expose partial PCM through the cache before commit; live preview reads the work directory directly.
