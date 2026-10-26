package app.stemsep.separation

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.stemsep.dsp.FloatPcmFileSource
import app.stemsep.dsp.WavStreamWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.sourceforge.javaflacencoder.FLACEncoder
import net.sourceforge.javaflacencoder.FLACFileOutputStream
import net.sourceforge.javaflacencoder.StreamConfiguration
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

enum class ExportFormat(val extension: String, val mimeType: String) {
    WAV("wav", "audio/wav"),
    FLAC("flac", "audio/flac"),
}

class StemExporter(private val context: Context) {
    suspend fun export(
        stems: CachedStems,
        treeUri: Uri,
        format: ExportFormat,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("Could not open the export directory")
        if (!tree.canWrite()) throw IOException("The export directory is not writable")

        val baseName = safeBaseName(stems.metadata.sourceName)
        val entries = listOf(
            stems.vocals to "${baseName}_vocals.${format.extension}",
            stems.instrumental to "${baseName}_instrumental.${format.extension}",
        )
        for ((index, entry) in entries.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (isCancelled()) throw InterruptedException("Export cancelled")
            val (sourceFile, name) = entry
            tree.findFile(name)?.delete()
            val document = tree.createFile(format.mimeType, name)
                ?: throw IOException("Could not create $name")

            try {
                context.contentResolver.openOutputStream(document.uri, "w")?.use { output ->
                    when (format) {
                        ExportFormat.WAV -> writeWav(
                            sourceFile,
                            stems.metadata.frameCount,
                            output,
                            onProgress = { fraction ->
                                onProgress((index + fraction) / entries.size)
                            },
                            isCancelled = isCancelled,
                        )

                        ExportFormat.FLAC -> writeFlac(
                            sourceFile,
                            stems.metadata.frameCount,
                            output,
                            onProgress = { fraction ->
                                onProgress((index + fraction) / entries.size)
                            },
                            isCancelled = isCancelled,
                        )
                    }
                } ?: throw IOException("Could not write $name")
            } catch (error: Exception) {
                document.delete()
                throw error
            }
        }
        onProgress(1f)
    }

    private suspend fun writeWav(
        sourceFile: File,
        frameCount: Long,
        output: java.io.OutputStream,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        WavStreamWriter(output, frameCount = frameCount).use { writer ->
            FloatPcmFileSource(sourceFile, frameCount).use { source ->
                copyFrames(source, frameCount, onProgress, isCancelled) { frames, count ->
                    writer.writeFrames(frames, count)
                }
            }
        }
    }

    private suspend fun writeFlac(
        sourceFile: File,
        frameCount: Long,
        output: java.io.OutputStream,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val temp = File(context.cacheDir, "stemsep-export-${UUID.randomUUID()}.flac")
        try {
            val configuration = StreamConfiguration(2, 4096, 4096, 44_100, 16)
            require(configuration.isValid()) { "Invalid FLAC configuration" }
            val encoder = FLACEncoder()
            encoder.setThreadCount(1)
            require(encoder.setStreamConfiguration(configuration)) {
                "Could not configure the FLAC encoder"
            }
            val fileOutput = FLACFileOutputStream(temp)
            try {
                encoder.setOutputStream(fileOutput)
                encoder.openFLACStream()
                val samples = IntArray(4096 * 2)
                FloatPcmFileSource(sourceFile, frameCount).use { source ->
                    copyFrames(source, frameCount, onProgress, isCancelled) { frames, count ->
                        for (index in 0 until count * 2) {
                            samples[index] = (frames[index].coerceIn(-1f, 1f) * 32767f)
                                .roundToInt()
                        }
                        encoder.addSamples(samples, count)
                        drainEncoder(encoder, end = false)
                    }
                }
                drainEncoder(encoder, end = true)
            } finally {
                runCatching { fileOutput.close() }
            }

            currentCoroutineContext().ensureActive()
            if (isCancelled()) throw InterruptedException("Export cancelled")
            output.use { destination ->
                temp.inputStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        destination.write(buffer, 0, read)
                    }
                    destination.flush()
                }
            }
            onProgress(1f)
        } finally {
            temp.delete()
        }
    }

    private fun drainEncoder(encoder: FLACEncoder, end: Boolean) {
        var guard = 0
        while (true) {
            val available = encoder.samplesAvailableToEncode()
            if (available <= 0) return
            val encoded = encoder.encodeSamples(available, end)
            if (encoded <= 0) return
            if (++guard > 1_000_000) error("FLAC encoder made no progress")
        }
    }

    private suspend fun copyFrames(
        source: FloatPcmFileSource,
        frameCount: Long,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        sink: (FloatArray, Int) -> Unit,
    ) {
        val chunkFrames = 4096
        val frames = FloatArray(chunkFrames * 2)
        var position = 0L
        while (position < frameCount) {
            currentCoroutineContext().ensureActive()
            if (isCancelled()) throw InterruptedException("Export cancelled")
            val count = minOf(chunkFrames.toLong(), frameCount - position).toInt()
            val read = source.readFrames(position, count, frames)
            if (read <= 0) break
            sink(frames, read)
            position += read
            onProgress((position.toDouble() / frameCount).toFloat().coerceIn(0f, 1f))
        }
    }

    private fun safeBaseName(sourceName: String): String {
        val base = sourceName.substringBeforeLast('.', sourceName).ifBlank { "stemsep" }
        return base.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "stemsep" }
    }
}
