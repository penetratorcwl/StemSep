# StemSep documentation

This directory contains the technical context for humans and AI agents working on StemSep.

- [Architecture](ARCHITECTURE.md): modules, runtime flow, DSP and playback invariants.
- [Models](MODELS.md): pinned models, hashes, input configurations and licensing notes.
- [Compute backends](COMPUTE_BACKENDS.md): CPU, Snapdragon NNAPI, fallback and the QNN/NPU path.
- [Playback](PLAYBACK.md): live stem preview during separation and the synchronized stem player.
- [Build and release](BUILD_AND_RELEASE.md): local commands, GitHub Actions and the APK release process.
- [Project status](PROJECT_STATUS.md): what is implemented, verified and still open.

The short AI onboarding file is [`../AGENTS.md`](../AGENTS.md).

## Quick facts

- Repository: `penetratorcwl/StemSep`
- Default branch: `master`
- Application ID: `app.stemsep`; debug application ID: `app.stemsep.debug`
- Min SDK: 26; compile/target SDK: 36
- Debug APK: <https://github.com/penetratorcwl/StemSep/releases/latest/download/StemSep-debug.apk>
- Latest debug build release: <https://github.com/penetratorcwl/StemSep/releases/tag/latest>
- Model weights are never committed to the repository; they are downloaded and SHA-256 verified on first use.
- Default inference is the ONNX Runtime CPU EP; Snapdragon GPU/NPU via NNAPI is experimental.
- The app exposes growing separated stems as a live preview (vocals by default) while separation runs, then switches to the committed stems.

## Product boundary

StemSep is an Android prototype for on-device vocal/instrumental separation. The separation engine is intentionally isolated in `:core-dsp` and `:separation` so it can later be reused by BitChord without the Compose UI or WorkManager layer.

The current release is a debug-signed development build. It is not a Play Store release and does not include model weights.
