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
import java.security.MessageDigest
import java.util.UUID

enum class SeparationPhase {
    HASHING,
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
)

data class SeparationResult(
    val stems: CachedStems,
    val cached: Boolean,
)

class SeparationPipeline(
    private val context: Context,
    private val cache: StemCache = StemCache(context),
    private val downloader: ModelDownloader = ModelDownloader(context),
    private val decoder: AudioDecoder = AudioDecoder(context),
) {
    suspend fun separate(
        uri: Uri,
        model: ModelSpec,
        backend: ComputeBackend = ComputeBackend.CPU,
        onProgress: (SeparationProgress) -> Unit = {},
        onPreview: (LiveStemPreview) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): SeparationResult = withContext(Dispatchers.IO) {
        val sourceInfo = readSourceInfo(uri)
        onProgress(SeparationProgress(SeparationPhase.HASHING, 0f))
        val sourceHash = hashSource(uri, sourceInfo.sizeBytes, onProgress, isCancelled)
        val cacheKey = cache.keyFor(sourceHash, model.id, backend)

        cache.find(cacheKey)?.let { cached ->
            onProgress(SeparationProgress(SeparationPhase.FINALIZING, 1f))
            return@withContext SeparationResult(cached, cached = true)
        }

        val modelFile = downloader.ensureModel(model) { fraction ->
            onProgress(SeparationProgress(SeparationPhase.DOWNLOADING_MODEL, fraction))
        }
        currentCoroutineContext().ensureActive()
        if (isCancelled()) throw InterruptedException("Separation cancelled")

        val tempDirectory = cache.createTempDirectory()
        val decodedFile = File(context.cacheDir, "stemsep-decode-${UUID.randomUUID()}.f32")
        var committed = false
        try {
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
            val vocalsFile = File(tempDirectory, StemCache.VOCALS_FILE)
            val instrumentalFile = File(tempDirectory, StemCache.INSTRUMENTAL_FILE)
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
                            requestedBackend = backend,
                        ).use { engine ->
                            effectiveBackend = engine.activeBackend
                            fallbackReason = engine.fallbackReason
                            onProgress(
                                SeparationProgress(
                                    phase = SeparationPhase.SEPARATING,
                                    fraction = 0f,
                                    effectiveBackend = effectiveBackend,
                                    fallbackReason = fallbackReason,
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
                                    onProgress(
                                        SeparationProgress(
                                            phase = SeparationPhase.SEPARATING,
                                            fraction = fraction,
                                            effectiveBackend = effectiveBackend,
                                            fallbackReason = fallbackReason,
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
            )
            val stems = cache.commit(cacheKey, tempDirectory, metadata)
            committed = true
            onProgress(
                SeparationProgress(
                    phase = SeparationPhase.FINALIZING,
                    fraction = 1f,
                    effectiveBackend = effectiveBackend,
                    fallbackReason = fallbackReason,
                ),
            )
            SeparationResult(stems, cached = false)
        } finally {
            decodedFile.delete()
            if (!committed) cache.deleteTemp(tempDirectory)
        }
    }

    private suspend fun hashSource(
        uri: Uri,
        sizeBytes: Long,
        onProgress: (SeparationProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                if (isCancelled()) throw InterruptedException("Separation cancelled")
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                total += read
                if (sizeBytes > 0) {
                    onProgress(
                        SeparationProgress(
                            SeparationPhase.HASHING,
                            (total.toDouble() / sizeBytes).toFloat().coerceIn(0f, 1f),
                        ),
                    )
                }
            }
        } ?: throw IOException("Could not open the selected audio file")
        onProgress(SeparationProgress(SeparationPhase.HASHING, 1f))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readSourceInfo(uri: Uri): SourceInfo {
        var displayName = uri.lastPathSegment ?: "audio"
        var sizeBytes = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }
        }
        return SourceInfo(displayName, sizeBytes)
    }

    private data class SourceInfo(val displayName: String, val sizeBytes: Long)
}
