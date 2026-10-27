# Playback

## Live stem preview during separation

The running job exposes its temporary stem files through WorkManager progress data. Once the first chunk is written, the UI opens `LiveStemPlayer`, which reads the growing `vocals.f32` and `instrumental.f32` files while inference continues.

- The default mode is **Vocals**, because the requested behaviour is to hear the already-separated vocal during separation.
- Play/pause is in the top-right of the card. Modes are vocals, instrumental or mix; the three chips fill the row and have no separate "Mode" label. Mix has a separate vocal gain.
- Playback waits at the current end of the available audio and resumes when the worker writes the next chunk.
- The slider spans the decoded track length; a separate progress bar and label show how much audio is buffered. Seeking beyond the buffered position waits for the worker to catch up.
- The live player is released when the job succeeds, fails or is cancelled, before the completed session is opened.
- `LivePlaybackTracker` publishes the current position and buffered frames to the separation worker. In Auto thread mode, the pipeline checks the lead at chunk boundaries and adjusts the CPU session to keep about five seconds buffered; if playback is paused or unavailable, the thread count is left unchanged.

The live reader does not use `FloatPcmFileSource`, because that class requires the final frame count and a complete file at construction. `LiveStemPlayer` uses `RandomAccessFile` and checks the current file length on every read. The writer flushes after each chunk, so a whole number of frames is visible to the reader.

WorkManager progress carries:

```text
preview_vocals, preview_instrumental, preview_available, preview_total, preview_sample_rate,
effective_backend, fallback_reason, inference_ms, inference_chunks, worker_threads
```

The playback card uses the preview fields. The compute stats card uses the backend and inference fields; see [`COMPUTE_BACKENDS.md`](COMPUTE_BACKENDS.md).

Absolute paths are used because the worker and UI run in the same app process. Progress data is small; it contains paths and frame counts, not PCM samples.

After finalization, the work directory is renamed into the single temporary session root. The UI switches to the normal `StemPlayer` reading the completed files. A successful job does not leave a second live player running. The final player also keeps play/pause in the top-right and uses the full-width mode chips. Starting another separation, tapping **Discard result**, or leaving the app deletes the session; export is the only way to keep it.

## Stem playback after separation

- One `AudioTrack` in float PCM mode is shared by both stems.
- Modes: vocals only, instrumental only, or mix.
- Mix mode has a separate vocal gain.
- Seeking uses a frame position and a generation counter so an in-flight read cannot overwrite the new position.
- Playback requests media audio focus. Loss or ducking pauses both players; the app does not auto-resume after focus returns.
- The player is released when the source, model or backend changes, when the result is discarded, and in `MainViewModel.onCleared()`.

## Limitations

- No media notification or lock-screen controls.
- The separation foreground notification is delayed until the app leaves the foreground; if the app stays visible for longer than WorkManager's normal-work limit, the job may be stopped before a foreground service starts.
- No background playback service, so playback may stop if the process is reclaimed.
- Live preview and the compute stats card are tied to the current job/ViewModel; stats are not persisted across app restarts.
- The live preview is tied to the Activity/ViewModel lifetime.
- The live preview is not gapless across a seek while the worker is still writing; it flushes the AudioTrack on seek.
- If the app process dies, the live preview is lost. The worker may continue, but the completed temporary session is never loaded again and is removed by the next separation or normal app exit; export before leaving the app to keep the result.
