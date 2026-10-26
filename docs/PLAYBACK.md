# Playback

## Live stem preview during separation

The running job exposes its temporary stem files through WorkManager progress data. Once the first chunk is written, the UI opens `LiveStemPlayer`, which reads the growing `vocals.f32` and `instrumental.f32` files while inference continues.

- The default mode is **Vocals**, because the requested behaviour is to hear the already-separated vocal during separation.
- Modes: vocals, instrumental or mix; mix has a separate vocal gain.
- Playback waits at the current end of the available audio and resumes when the worker writes the next chunk.
- The slider spans the decoded track length; a separate progress bar and label show how much audio is buffered. Seeking beyond the buffered position waits for the worker to catch up.
- The live player is released when the job succeeds, fails or is cancelled, before the committed cache entry is opened.

The live reader does not use `FloatPcmFileSource`, because that class requires the final frame count and a complete file at construction. `LiveStemPlayer` uses `RandomAccessFile` and checks the current file length on every read. The writer flushes after each chunk, so a whole number of frames is visible to the reader.

WorkManager progress carries:

```text
preview_vocals, preview_instrumental, preview_available, preview_total, preview_sample_rate,
effective_backend, fallback_reason, inference_ms, inference_chunks, worker_threads
```

The playback card uses the preview fields. The compute stats card uses the backend and inference fields; see [`COMPUTE_BACKENDS.md`](COMPUTE_BACKENDS.md).

Absolute paths are used because the worker and UI run in the same app process. Progress data is small; it contains paths and frame counts, not PCM samples.

After commit, the temporary directory is renamed into the cache. The UI switches to the normal `StemPlayer` reading the committed files. A successful job does not leave a second live player running.

## Stem playback after separation

- One `AudioTrack` in float PCM mode is shared by both stems.
- Modes: vocals only, instrumental only, or mix.
- Mix mode has a separate vocal gain.
- Seeking uses a frame position and a generation counter so an in-flight read cannot overwrite the new position.
- The player is released when the source, model or backend changes, and in `MainViewModel.onCleared()`.

## Limitations

- No audio focus handling yet; live preview and stem playback can overlap other apps.
- No media notification or lock-screen controls.
- No background playback service, so playback may stop if the process is reclaimed.
- Live preview and the compute stats card are tied to the current job/ViewModel; stats are not persisted across app restarts.
- The live preview is tied to the Activity/ViewModel lifetime.
- The live preview is not gapless across a seek while the worker is still writing; it flushes the AudioTrack on seek.
- If the app process dies, the live preview is lost. The worker may continue, and the completed cache entry is available on the next run.
