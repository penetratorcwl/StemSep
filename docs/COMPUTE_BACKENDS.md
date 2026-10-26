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

The engine attempts the requested backend and retries with CPU if session creation fails. The cache metadata records the effective backend (`cpu` or `nnapi`). If ORT falls back per operation inside a successful NNAPI session, the metadata still says `nnapi`; this is a limitation of the Java API.

## Why there is no QNN HTP backend yet

ONNX Runtime's Qualcomm QNN execution provider is the direct route to the Hexagon NPU. The Android QNN AAR exists, but the HTP backend only supports quantized models. The current `mdx-9482` and `Kim_Vocal_2` files are float32 models and cannot run on HTP without a calibration/quantization pipeline and a quantized model artifact.

QNN GPU can run float models, but adding the QNN runtime increases the APK by a large amount (the QNN runtime AAR is roughly 61 MB compressed and includes multiple Hexagon skels). That is a separate build-size decision.

Planned path if NPU work continues:

1. Add a quantized model variant and calibration data.
2. Validate operator coverage and output quality against the CPU/float path.
3. Add the QNN AAR, restrict ABI to `arm64-v8a`, and expose an explicit QNN HTP backend.
4. Benchmark HTP against CPU and NNAPI on real Snapdragon devices.
5. Keep CPU fallback and document the model-format requirement.

## Cache impact

The cache key now includes the backend:

```text
<source-sha256>-<model-id>-<backend>-v<separator-version>
```

`SEPARATOR_VERSION` is `2`. Cache entries created before this change are ignored because the key and metadata format changed.

## Device verification

Use a release/debug build on the target phone and watch the backend log:

```bash
adb logcat -s StemSepOnnx
```

The app logs `Falling back to CPU ...` when NNAPI session creation fails. A successful NNAPI session does not prove that every node used the accelerator. Compare wall-clock time, output quality and battery/thermal behavior between CPU and Snapdragon modes.

## Verification status

- Backend detection and ID parsing have JVM unit tests.
- The NNAPI path has not yet been run on a physical Snapdragon device.
- The existing NNAPI benchmark on the test phone was slower than CPU; the option is experimental until re-measured with the current pipeline.
