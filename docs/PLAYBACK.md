# Playback

## Source preview during separation

While a separation job is running, the UI can show a **Source preview** card. It uses a separate `MediaPlayer` instance over the original SAF URI, so the user can listen to the source while the model works.

- The preview is prepared asynchronously after the WorkManager job is enqueued.
- Play/pause and seek are independent of the separation job.
- The preview is released when the job succeeds, fails or is cancelled.
- It is not a background playback service; it is tied to the Activity/ViewModel lifetime.
- The preview decoder and the separation decoder both read the source. On a slow device, pausing the preview reduces CPU/IO contention.

This is intentionally a source preview, not a live stem preview. The separated stems are not playable until the worker has written and committed both `vocals.f32` and `instrumental.f32`. A future progressive-stem preview would need a shared growing-file reader and explicit synchronization with the worker.

## Stem playback after separation

- One `AudioTrack` in float PCM mode is shared by both stems.
- Modes: vocals only, instrumental only, or mix.
- Mix mode has a separate vocal gain.
- Seeking uses a frame position and a generation counter so an in-flight read cannot overwrite the new position.
- The player is released when the source, model or backend changes, and in `MainViewModel.onCleared()`.

## Limitations

- No audio focus handling yet; preview playback can overlap other apps.
- No media notification or lock-screen controls.
- No background service, so playback may stop if the process is reclaimed.
- Source preview and stem playback are mutually exclusive in the UI: the preview is released before the stem player opens.
