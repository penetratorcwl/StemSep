# Models

The APK contains no model weights. Models are downloaded on first use, checked against a pinned byte length and SHA-256, and stored in the app cache.

All models are float32 ONNX files. They run through ONNX Runtime on the CPU by default. The optional Snapdragon GPU/NPU path uses Android NNAPI and the same float model; it does not require a separate model file. The Qualcomm QNN HTP NPU path would require quantized models and is not enabled yet. See [`COMPUTE_BACKENDS.md`](COMPUTE_BACKENDS.md).

## Supported models

| Catalog ID | UI label | Size | SHA-256 |
|---|---|---:|---|
| `mdx-9482` | Mobile (default) | 29,704,436 B | `f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184` |
| `kim-vocal-2` | High quality | 66,759,214 B | `ce74ef3b6a6024ce44211a07be9cf8bc6d87728cc852a68ab34eb8e58cde9c8b` |

### `mdx-9482` (default)

- Source: `UVR_MDXNET_9482.onnx`
- Primary URL: <https://github.com/nomadkaraoke/python-audio-separator/releases/download/model-configs/UVR_MDXNET_9482.onnx>
- Fallback URL: <https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR_MDXNET_9482.onnx>
- Input tensor: `[1, 4, 2048, 256]`
- `fftSize`: 6144
- `hopLength`: 1024
- `frequencyBins`: 2048
- `timeFrames`: 256
- `segmentSamples`: 261,120
- `edgeTrimSamples`: 3072
- `compensation`: 1.035
- `overlap`: 0.10
- `sampleRate`: 44,100
- `zeroLowBins`: true
- Observed CPU speed on the test phone: about 1.77-1.89 s per 5.92 s chunk, roughly 3.3x faster than realtime.

### `kim-vocal-2` (optional high quality)

- Source: `Kim_Vocal_2.onnx`
- URL: <https://huggingface.co/Eddycrack864/audio-separator-models/resolve/main/mdx-net/Kim_Vocal_2.onnx>
- No fallback URL is configured.
- Input tensor: `[1, 4, 3072, 256]`
- `fftSize`: 7680
- `hopLength`: 1024
- `frequencyBins`: 3072
- `timeFrames`: 256
- `segmentSamples`: 261,120
- `edgeTrimSamples`: 3840
- `compensation`: 1.009
- `overlap`: 0.10
- `sampleRate`: 44,100
- `zeroLowBins`: true
- Observed CPU speed on the test phone: about 7.4-7.8 s per chunk, potentially slower than realtime.

## Evaluated but excluded

`UVR-MDX-NET-Voc_FT` was benchmarked at about 8.2 s per chunk. It did not offer a speed advantage over Kim Vocal 2, so it is not exposed in the default catalog.

- Size: 66,762,795 B
- SHA-256: `e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4`
- Input tensor: `[1, 4, 2048, 256]`
- `fftSize`: 6144
- `edgeTrimSamples`: 3072

## Adding or changing a model

1. Confirm the ONNX input shape and the output tensor.
2. Determine the exact MDX STFT parameters from the model configuration.
3. Add a `ModelSpec` to `separation/src/main/kotlin/app/stemsep/separation/ModelCatalog.kt`.
4. Pin both the byte length and SHA-256. Do not trust the remote file name alone.
5. Verify the model on a real track: stem lengths must equal the decoded frame count.
6. Increase `ModelCatalog.SEPARATOR_VERSION` when the separation algorithm, config, or cache-key semantics change, so old cache entries are ignored. It is currently `2` because the backend was added to the key.
7. Run `./gradlew :core-dsp:test`, `./gradlew :separation:testDebugUnitTest`, and `./gradlew :app:assembleDebug` in CI.

## Licensing

Model weights are not redistributed by this repository. The app downloads them directly from the configured source. Review the model source's license and terms before publishing or redistributing a build that bundles weights.
