# Compute backends

## Current implementation

StemSep runs the MDX model inside the WorkManager foreground worker, never on the UI thread.

| UI option | Execution provider | Device gate | Status |
|---|---|---|---|
| CPU | ONNX Runtime CPU EP | Any supported ABI | Default, tested |
| Snapdragon GPU/NPU | Android NNAPI EP | Qualcomm Snapdragon, `arm64-v8a`, API 27+ | Experimental |

The CPU backend uses four intra-op threads and one inter-op thread. This is the setting used for the existing speed measurements: about 1.8 s per 5.92 s chunk for `mdx-9482` on the test phone.

## Snapdragon detection

`ComputeCapabilityDetector` checks:

- `Build.SOC_MANUFACTURER` / `Build.SOC_MODEL` on Android 12+;
- `Build.MANUFACTURER`, `Build.HARDWARE` and `Build.BOARD` as fallbacks;
- Snapdragon model prefixes such as `SM`, `MSM`, `APQ`, `QCM`, `QCS`, `SDA` and `SDM`;
- `Build.SUPPORTED_ABIS` contains `arm64-v8a`;
- NNAPI is available on API 27+.

The Snapdragon option is hidden/disabled on other SoCs. This is intentional: the option is not offered as a generic NNAPI toggle.

## How GPU/NPU selection works

The app calls:

```kotlin
options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
```

Android NNAPI is a system API, not a direct GPU or NPU API. The Qualcomm NNAPI driver chooses the available accelerator for each supported operation. As a result:

- the app cannot force "GPU only" or "NPU only";
- unsupported nodes can fall back to the CPU EP;
- the effective device may differ by Android version, driver and model;
- `USE_FP16` is requested for throughput, so output can differ slightly from the CPU path.

GPU and NPU are therefore one UI option, not two switches. The UI reports the requested and active provider; it cannot report which individual nodes ran on GPU versus NPU.

The engine attempts the requested backend and retries with CPU if session creation fails. The failure message is stored as `fallbackReason`, sent through WorkManager progress and persisted in `meta.json`. The UI shows:

- **Requested** — the chip selected by the user;
- **Active** — `engine.activeBackend` while running, or the cached effective backend;
- **Fallback** — the exception message when NNAPI session creation failed.

If ORT falls back per operation inside a successful NNAPI session, the active backend remains `nnapi` and no fallback reason is available; this is a limitation of the Java API.

## Cache impact

The cache key includes the requested backend:

```text
<source-sha256>-<model-id>-<requested-backend>-v<separator-version>
```

`SEPARATOR_VERSION` is `3`. Metadata stores `requestedBackend`, `effectiveBackend` and optional `fallbackReason`. Cache entries created before this format are ignored.

A NNAPI session that fell back to CPU is still cached under the requested NNAPI key, while its metadata records the CPU effective backend. This avoids a second expensive run after the fallback, but a future NNAPI attempt can receive that cache hit without retrying NNAPI. Clear the app cache or change the source/model/backend to force a new attempt.

## Why there is no QNN HTP backend yet

ONNX Runtime's Qualcomm QNN execution provider is the direct route to the Hexagon NPU. The Android QNN AAR exists, but the HTP backend only supports quantized models. The current `mdx-9482` and `Kim_Vocal_2` files are float32 models and cannot run on HTP without a calibration/quantization pipeline and a quantized model artifact.

QNN GPU can run float models, but adding the QNN runtime increases the APK by a large amount (the QNN runtime AAR is roughly 61 MB compressed and includes multiple Hexagon skels). That is a separate build-size decision.

Planned path if NPU work continues:

1. Add a quantized model variant and calibration data.
2. Validate operator coverage and output quality against the CPU/float path.
3. Add the QNN AAR, restrict ABI to `arm64-v8a`, and expose an explicit QNN HTP backend.
4. Benchmark HTP against CPU and NNAPI on real Snapdragon devices.
5. Keep CPU fallback and document the model-format requirement.

## Device verification

Use a release/debug build on the target phone and watch the backend log:

```bash
adb logcat -s StemSepOnnx
```

The app logs `Falling back to CPU ...` when NNAPI session creation fails. The UI also shows the fallback reason from WorkManager progress. A successful NNAPI session does not prove that every node used the accelerator. Compare wall-clock time, output quality and battery/thermal behavior between CPU and Snapdragon modes.

## Verification status

- Backend detection and ID parsing have JVM unit tests.
- The requested/effective/fallback metadata and UI compile in CI run `40396988552`.
- The NNAPI path has not yet been run on a physical Snapdragon device.
- The existing NNAPI benchmark on the test phone was slower than CPU; the option is experimental until re-measured with the current pipeline.
