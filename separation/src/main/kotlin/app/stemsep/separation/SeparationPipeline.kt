package app.stemsep.separation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.stemsep.dsp.FloatPcmFileSource
import app.stemsep.dsp.FloatPcmFileWriter
import app.stemsep.dsp.MdxStreamingSeparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

enum class SeparationPhase {
    PREPARING,
    DOWNLOADING_MODEL,
    DECODING,
    SEPARATING,
    FINALIZING,
}

data class SeparationProgress(
    val phase: SeparationPhase,
    val fraction: Float,
    val effectiveBackend: ComputeBackend? = null,
    val fallbackReason: String? = null,
    val inferenceAverageMillis: Long = 0,
    val inferenceChunks: Int = 0,
    val workerThreads: Int = 0,
)

data class SeparationResult(
    val stems: SeparatedStems,
)

class SeparationPipeline(
    private val context: Context,
    private val storage: StemStorage = StemStorage(context),
    private val downloader: ModelDownloader = ModelDownloader(context),
    private val decoder: AudioDecoder = AudioDecoder(context),
) {
    suspend fun separate(
        uri: Uri,
        model: ModelSpec,
        backend: ComputeBackend = ComputeBackend.CPU,
        cpuThreads: Int = 4,
        acceleratorMode: AcceleratorMode = AcceleratorMode.BALANCED,
        playbackLeadSeconds: () -> Float? = { null },
        onProgress: (SeparationProgress) -> Unit = {},
        onPreview: (LiveStemPreview) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): SeparationResult = withContext(Dispatchers.IO) {
        val sourceInfo = readSourceInfo(uri)
        onProgress(SeparationProgress(SeparationPhase.PREPARING, 0f))
        val tempDirectory = storage.createSessionDirectory()
        val decodedFile = File(context.cacheDir, "stemsep-decode-${UUID.randomUUID()}.f32")
        var committed = false
        try {
            val modelFile = downloader.ensureModel(model) { fraction ->
                onProgress(SeparationProgress(SeparationPhase.DOWNLOADING_MODEL, fraction))
            }
            currentCoroutineContext().ensureActive()
            if (isCancelled()) throw InterruptedException("Separation cancelled")

            onProgress(SeparationProgress(SeparationPhase.DECODING, 0f))
            val decoded = decoder.decode(
                uri = uri,
                output = decodedFile,
                onProgress = { fraction ->
                    onProgress(SeparationProgress(SeparationPhase.DECODING, fraction))
                },
                isCancelled = isCancelled,
            )
            if (decoded.frameCount <= 0) throw IOException("Decoded audio is empty")

            onProgress(SeparationProgress(SeparationPhase.SEPARATING, 0f))
            var vocalFrames = 0L
            var instrumentalFrames = 0L
            var effectiveBackend = backend
            var fallbackReason: String? = null
            var finalInferenceAverage = 0L
            var finalInferenceChunks = 0
            var finalWorkerThreads = 0
            val threadLimit = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val autoThreads = cpuThreads <= 0
            val autoController = if (autoThreads) {
                AutoThreadController(threadLimit)
            } else {
                null
            }
            val initialThreads = autoController?.threads
                ?: cpuThreads.coerceIn(1, threadLimit)
            val vocalsFile = File(tempDirectory, StemStorage.VOCALS_FILE)
            val instrumentalFile = File(tempDirectory, StemStorage.INSTRUMENTAL_FILE)
            onPreview(
                LiveStemPreview(
                    vocalsFile = vocalsFile,
                    instrumentalFile = instrumentalFile,
                    availableFrames = 0,
                    totalFrames = decoded.frameCount,
                    sampleRate = decoded.sampleRate,
                ),
            )
            FloatPcmFileSource(decodedFile, decoded.frameCount).use { source ->
                FloatPcmFileWriter(vocalsFile).use { vocals ->
                    FloatPcmFileWriter(instrumentalFile).use { instrumental ->
                        OnnxMdxEngine(
                            modelFile = modelFile,
                            threads = initialThreads,
                            requestedBackend = backend,
                            acceleratorMode = acceleratorMode,
                        ).use { engine ->
                            effectiveBackend = engine.activeBackend
                            fallbackReason = engine.fallbackReason
                            finalWorkerThreads = engine.effectiveThreads
                            onProgress(
                                SeparationProgress(
                                    phase = SeparationPhase.SEPARATING,
                                    fraction = 0f,
                                    effectiveBackend = effectiveBackend,
                                    fallbackReason = fallbackReason,
                                    workerThreads = engine.effectiveThreads,
                                ),
                            )
                            MdxStreamingSeparator(
                                config = model.config,
                                inference = engine.inference(model.config),
                            ).separate(
                                source = source,
                                vocalsSink = vocals,
                                instrumentalSink = instrumental,
                                onProgress = { fraction ->
                                    onPreview(
                                        LiveStemPreview(
                                            vocalsFile = vocalsFile,
                                            instrumentalFile = instrumentalFile,
                                            availableFrames = minOf(
                                                vocals.frameCount,
                                                instrumental.frameCount,
                                            ),
                                            totalFrames = decoded.frameCount,
                                            sampleRate = decoded.sampleRate,
                                        ),
                                    )
                                    if (
                                        autoController != null &&
                                        effectiveBackend == ComputeBackend.CPU
                                    ) {
                                        val desired = autoController.observe(
                                            playbackLeadSeconds(),
                                        )
                                        if (desired != engine.effectiveThreads) {
                                            engine.setThreads(desired)
                                        }
                                    }
                                    finalWorkerThreads = engine.effectiveThreads
                                    val stats = engine.stats()
                                    finalInferenceAverage = stats.averageMillis
                                    finalInferenceChunks = stats.chunks
                                    onProgress(
                                        SeparationProgress(
                                            phase = SeparationPhase.SEPARATING,
                                            fraction = fraction,
                                            effectiveBackend = effectiveBackend,
                                            fallbackReason = fallbackReason,
                                            inferenceAverageMillis = stats.averageMillis,
                                            inferenceChunks = stats.chunks,
                                            workerThreads = engine.effectiveThreads,
                                        ),
                                    )
                                },
                                isCancelled = isCancelled,
                            )
                        }
                        vocalFrames = vocals.frameCount
                        instrumentalFrames = instrumental.frameCount
                    }
                }
            }

            if (vocalFrames != decoded.frameCount || instrumentalFrames != decoded.frameCount) {
                throw IOException("Separated stems do not match the decoded length")
            }

            onPreview(
                LiveStemPreview(
                    vocalsFile = vocalsFile,
                    instrumentalFile = instrumentalFile,
                    availableFrames = decoded.frameCount,
                    totalFrames = decoded.frameCount,
                    sampleRate = decoded.sampleRate,
                ),
            )
            onProgress(SeparationProgress(SeparationPhase.FINALIZING, 0f))
            val metadata = StemMetadata(
                modelId = model.id,
                sourceName = sourceInfo.displayName,
                frameCount = decoded.frameCount,
                sampleRate = decoded.sampleRate,
                createdAt = System.currentTimeMillis(),
                separatorVersion = ModelCatalog.SEPARATOR_VERSION,
                requestedBackend = backend.cacheId,
                effectiveBackend = effectiveBackend.cacheId,
                fallbackReason = fallbackReason,
                cpuThreads = cpuThreads,
                acceleratorMode = acceleratorMode.cacheId,
            )
            val stems = storage.finishSession(tempDirectory, metadata)
            committed = true
            onProgress(
                SeparationProgress(
                    phase = SeparationPhase.FINALIZING,
                    fraction = 1f,
                    effectiveBackend = effectiveBackend,
                    fallbackReason = fallbackReason,
                    inferenceAverageMillis = finalInferenceAverage,
                    inferenceChunks = finalInferenceChunks,
                    workerThreads = finalWorkerThreads,
                ),
            )
            SeparationResult(stems)
        } finally {
            decodedFile.delete()
            if (!committed) storage.deleteTemp(tempDirectory)
        }
    }

    private fun readSourceInfo(uri: Uri): SourceInfo {
        var displayName = uri.lastPathSegment ?: "audio"
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                }
            }
        }
        return SourceInfo(displayName)
    }

    private data class SourceInfo(val displayName: String)
}
