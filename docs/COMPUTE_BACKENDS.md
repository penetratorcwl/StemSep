# Compute backends

## Current implementation

StemSep runs the MDX model inside the WorkManager foreground worker, never on the UI thread.

| UI option | Execution provider | Device gate | Status |
|---|---|---|---|
| CPU | ONNX Runtime CPU EP | Any supported ABI | Default, tested |
| Snapdragon GPU/NPU | Android NNAPI EP | Qualcomm Snapdragon, `arm64-v8a`, API 27+ | Experimental |

CPU inference uses a user-selectable number of intra-op threads, from 1 to the number of CPUs reported by the device, or **Auto**. Auto starts at four threads (capped at the reported core count) and can change the session's intra-op count at chunk boundaries. Inter-op threads remain fixed at one. The existing speed measurements used four threads: about 1.8 s per 5.92 s chunk for `mdx-9482` on the test phone.

Auto uses `LivePlaybackTracker` to read the live playback position and buffered frame count. `AutoThreadController` targets at least five seconds of audio ahead of playback, jumps to the core limit below two seconds, and reduces threads again after the buffer exceeds eight seconds. Changes are rate-limited and only happen between chunks. `OnnxMdxEngine.setThreads` creates a replacement CPU session and keeps the old one if creation fails. Auto has no effect when NNAPI is the active backend because NNAPI uses one intra-op thread.

The worker thread count is persisted in app preferences and sent to the worker as `cpu_threads`. Auto is sent as `0`; manual values are 1..reported cores. It changes CPU parallelism, not the separation result.

## Snapdragon detection

`ComputeCapabilityDetector` checks:

- `Build.SOC_MANUFACTURER` / `Build.SOC_MODEL` on Android 12+;
- `Build.MANUFACTURER`, `Build.HARDWARE` and `Build.BOARD` as fallbacks;
- Snapdragon model prefixes such as `SM`, `MSM`, `APQ`, `QCM`, `QCS`, `SDA` and `SDM`;
- `Build.SUPPORTED_ABIS` contains `arm64-v8a`;
- NNAPI is available on API 27+.

The Snapdragon option is hidden/disabled on other SoCs. This is intentional: the option is not offered as a generic NNAPI toggle.

## How GPU/NPU selection works

The app calls NNAPI through ONNX Runtime:

```kotlin
options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
```

The Compute card exposes the policy as one inline **Allow CPU fallback** switch:

- **On / Balanced** (default): NNAPI may use GPU, NPU or CPU per operation, and the app retries on the CPU EP if NNAPI session creation fails.
- **Off / Accelerator only**: adds `NNAPIFlags.CPU_DISABLED` and does not retry on CPU. If the driver cannot compile or run the model, the job fails with an error instead of silently using CPU.

Android NNAPI is a system API, not a direct GPU or NPU API. The Qualcomm NNAPI driver chooses the available accelerator for each supported operation. As a result:

- the app cannot force "GPU only" or "NPU only";
- unsupported nodes can fall back to the CPU EP in Balanced mode;
- the effective device may differ by Android version, driver and model;
- `USE_FP16` is requested for throughput, so output can differ slightly from the CPU path;
- the app cannot set a GPU/NPU core count.

GPU and NPU are therefore one UI option, not two switches. The UI reports the requested and active provider; it cannot report which individual nodes ran on GPU versus NPU.

The engine attempts the requested backend and, in Balanced mode, retries with CPU if session creation fails. The failure message is stored as `fallbackReason`, sent through WorkManager progress and returned in the completed session metadata. The UI shows:

- **Requested** — the backend chip selected by the user;
- **Active** — `engine.activeBackend` while running, or the effective backend of the current completed session;
- **Fallback** — the exception message when NNAPI session creation failed.

If ORT falls back per operation inside a successful NNAPI session, the active backend remains `nnapi` and no fallback reason is available; this is a limitation of the Java API.

## Settings and statistics

The Compute card exposes:

- **CPU worker threads** — Auto or 1 to `Runtime.availableProcessors()`, persisted across launches. The control is hidden when NNAPI is selected with CPU fallback disabled because NNAPI does not use the CPU thread setting;
- **Allow CPU fallback** — an inline switch mapping to Balanced or Accelerator only, persisted across launches;
- **Requested / Active / Fallback** status.

The Compute stats card shows:

- **CPU** — this process's share of total CPU capacity, derived from `Process.getElapsedCpuTime()` over wall time and divided by the reported core count. It is not a per-core utilization graph.
- **Worker threads** — the effective inference thread count. NNAPI uses one intra-op thread; CPU uses the selected count.
- **Inference** — average milliseconds per completed chunk and the number of chunks.
- **GPU** — best-effort busy percentage from Qualcomm sysfs paths such as `gpu_busy_percentage`, `gpubusy` or `devfreq/gpu_load` when readable, otherwise `driver-managed`. Counter files such as `gpubusy` are differenced between samples; reading the cumulative ratio directly would report a lifetime average.
- **NPU** — `driver-managed` when NNAPI is active, otherwise `off`. Android does not expose a public NPU utilization counter.
- **Power** — battery current from `BATTERY_PROPERTY_CURRENT_NOW` multiplied by the sticky battery voltage; shown in watts.
- **Energy** — charge-counter delta since the run started, or an integrated current fallback, shown in mAh. Values are hidden or marked charging when the device does not report usable data.

CPU, GPU and inference rows keep a short rolling history and show a mini sparkline next to the value. Tapping a row opens a full-height bottom sheet with a larger chart, current/average/peak values, and swipe-down dismissal; the inference chart draws a dot for every sample. Power and energy are shown as current values without charts. The sampler runs every 750 ms off the main thread and keeps 90 points (about one minute for CPU/GPU; inference adds one point per completed chunk). NPU has no chart because there is no public utilization counter.

GPU/NPU utilization and core counts are not available through a stable Android API. Do not present the sysfs value as guaranteed; SELinux or a different driver may hide it. A low GPU reading can mean NNAPI placed the work on the CPU or NPU, or that the driver does not expose a useful busy counter.

## Repeated runs

Separated stems are not cached or reused. Every separation starts from a fresh temporary session, even when the source, model, backend and accelerator profile are unchanged. The completed session metadata stores `requestedBackend`, `effectiveBackend`, optional `fallbackReason`, `cpuThreads` and `acceleratorMode` for the UI, but it is not a lookup key.

This means a NNAPI session that fell back to CPU is retried on the next run instead of being served from an earlier CPU result. Export the stems before starting another separation or leaving the app if the result should be kept.

## Why there is no QNN HTP backend yet

ONNX Runtime's Qualcomm QNN execution provider is the direct route to the Hexagon NPU. The Android QNN AAR exists, but the HTP backend only supports quantized models. The current `mdx-9482` file is float32 and cannot run on HTP without a calibration/quantization pipeline and a quantized model artifact.

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

The app logs `Falling back to CPU ...` when NNAPI session creation fails. The UI also shows the fallback reason from WorkManager progress. A successful NNAPI session does not prove that every node used the accelerator. Compare wall-clock time, output quality and battery/thermal behavior between CPU and Snapdragon modes. Test both Balanced and Accelerator only, and test changing CPU threads.

## Verification status

- Backend detection and ID parsing have JVM unit tests.
- Requested/effective/fallback metadata and the compute UI compile in CI. Stable signing was verified in run `40400420284`; the compute/settings/UI build passed in run `40400848607`; the chart/theme/settings build passed in run `40406906028`; the Auto-thread/power/notification build passed in run `40413756893`.
- The NNAPI path has not yet been run on a physical Snapdragon device.
- GPU/NPU statistics have not been validated on a physical device; sysfs availability and battery current/charge-counter support are device-specific.
- The existing NNAPI benchmark on the test phone was slower than CPU; the option is experimental until re-measured with the current pipeline.
