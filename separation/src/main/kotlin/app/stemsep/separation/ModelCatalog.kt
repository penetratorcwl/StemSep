package app.stemsep.separation

import app.stemsep.dsp.MdxConfig

data class ModelSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val url: String,
    val fallbackUrl: String?,
    val sha256: String,
    val sizeBytes: Long,
    val config: MdxConfig,
)

object ModelCatalog {
    const val DEFAULT_MODEL_ID = "mdx-9482"
    const val SEPARATOR_VERSION = 1

    val mobile = ModelSpec(
        id = DEFAULT_MODEL_ID,
        displayName = "Mobile",
        description = "Fast MDX-Net model for everyday tracks.",
        url = "https://github.com/nomadkaraoke/python-audio-separator/releases/download/model-configs/UVR_MDXNET_9482.onnx",
        fallbackUrl = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR_MDXNET_9482.onnx",
        sha256 = "f4f365207c56deb115bceedff3ad8fe98a751c745f9e370cecec6226b8b47184",
        sizeBytes = 29_704_436L,
        config = MdxConfig(
            fftSize = 6144,
            hopLength = 1024,
            frequencyBins = 2048,
            timeFrames = 256,
            segmentSamples = 261_120,
            edgeTrimSamples = 3072,
            compensation = 1.035f,
            overlap = 0.10f,
            sampleRate = 44_100,
            zeroLowBins = true,
        ),
    )

    val highQuality = ModelSpec(
        id = "kim-vocal-2",
        displayName = "High quality",
        description = "Slower Kim Vocal 2 model; may not run faster than realtime.",
        url = "https://huggingface.co/Eddycrack864/audio-separator-models/resolve/main/mdx-net/Kim_Vocal_2.onnx",
        fallbackUrl = null,
        sha256 = "ce74ef3b6a6024ce44211a07be9cf8bc6d87728cc852a68ab34eb8e58cde9c8b",
        sizeBytes = 66_759_214L,
        config = MdxConfig(
            fftSize = 7680,
            hopLength = 1024,
            frequencyBins = 3072,
            timeFrames = 256,
            segmentSamples = 261_120,
            edgeTrimSamples = 3840,
            compensation = 1.009f,
            overlap = 0.10f,
            sampleRate = 44_100,
            zeroLowBins = true,
        ),
    )

    val all: List<ModelSpec> = listOf(mobile, highQuality)

    fun byId(id: String?): ModelSpec = all.firstOrNull { it.id == id } ?: mobile
}
