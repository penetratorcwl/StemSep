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
- `OnnxMdxEngine` owns an ONNX Runtime session and exposes an `MdxInference` callback.
- `StemCache` owns the on-disk stem cache and metadata.
- `SeparationPipeline` orchestrates hash -> cache lookup -> model -> decode -> inference -> commit.
- `StemExporter` writes WAV or FLAC through the Storage Access Framework.
- `StemPlayer` mixes two stem files into one `AudioTrack` and supports seek/mode/gain.

### `:app`

- `StemSepApp` creates the singleton `AppContainer`.
- `AppContainer` owns `StemCache`, `SeparationPipeline` and `StemExporter`.
- `MainActivity` hosts Compose and requests `POST_NOTIFICATIONS` on API 33+.
- `MainViewModel` exposes `UiState`, observes WorkManager, owns the player and export coroutine.
- `SeparationWorker` is a `CoroutineWorker` with a data-sync foreground notification.
- `StemSepScreen` is the Material 3 dark UI: source, model, progress, player and export.

## Separation flow

1. The user selects an audio document with `ActivityResultContracts.OpenDocument`.
2. The URI permission is persisted for reading. A unique WorkManager job is enqueued.
3. `SeparationWorker` promotes itself to a foreground worker and calls `SeparationPipeline`.
4. The pipeline streams the source through SHA-256. The cache key is:

   ```text
   <source-sha256>-<model-id>-v<separator-version>
   ```

5. A cache hit returns immediately with `cached = true`.
6. On a miss, the model is downloaded if needed and verified before use.
7. `AudioDecoder` writes a temporary interleaved stereo `float32` file at 44.1 kHz.
8. `MdxStreamingSeparator` runs the ONNX model over 261,120-sample chunks and writes:
   - `vocals.f32`
   - `instrumental.f32`
9. `StemCache` writes metadata and atomically moves the temporary directory into the cache.
10. The worker returns the cache key; `MainViewModel` opens the stems in `StemPlayer`.

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

## Cache invariants

- Cache root: `context.cacheDir/stems`.
- Work root: `context.cacheDir/stems-work`.
- Each cache entry contains `vocals.f32`, `instrumental.f32` and `meta.json`.
- `frameCount` must be positive.
- Each stem file must be exactly `frameCount * 8` bytes (2 channels × 4 bytes).
- `separatorVersion` must match `ModelCatalog.SEPARATOR_VERSION`; a mismatch is a cache miss.
- Temporary directories are deleted when separation fails or is cancelled.

## Playback

- One `AudioTrack` in float PCM mode is shared by both stems.
- The player reads fixed-size chunks and mixes according to `StemMode`:
  - `VOCALS`: vocals only.
  - `INSTRUMENTAL`: instrumental only.
  - `MIX`: both stems, with a separate vocal gain.
- Seeking sets a frame position and increments a generation counter so an in-flight read cannot overwrite the new position.
- `StemPlayer` is closed when the source or model changes and in `MainViewModel.onCleared()`.

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
- Keep the cache key tied to the source hash, model ID and separator version.
- Never commit `.onnx` files or model weights.
- Keep model downloads on HTTPS and verify the pinned SHA-256.
- Do not introduce a second AudioTrack unless the player is redesigned; synchronization depends on one track.
- Keep the separation engine independent of Compose and WorkManager where possible.
