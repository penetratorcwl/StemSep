package app.stemsep.separation

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class StemMetadata(
    val modelId: String,
    val sourceName: String,
    val frameCount: Long,
    val sampleRate: Int,
    val createdAt: Long,
    val separatorVersion: Int,
    val backend: String,
)

data class CachedStems(
    val key: String,
    val vocals: File,
    val instrumental: File,
    val metadata: StemMetadata,
)

class StemCache(context: Context) {
    private val root = File(context.cacheDir, "stems")
    private val workRoot = File(context.cacheDir, "stems-work")

    fun keyFor(
        sourceHash: String,
        modelId: String,
        backend: ComputeBackend = ComputeBackend.CPU,
    ): String = "$sourceHash-$modelId-${backend.cacheId}-v${ModelCatalog.SEPARATOR_VERSION}"

    fun find(key: String): CachedStems? {
        val directory = File(root, key)
        val metaFile = File(directory, META_FILE)
        val vocals = File(directory, VOCALS_FILE)
        val instrumental = File(directory, INSTRUMENTAL_FILE)
        if (!metaFile.isFile || !vocals.isFile || !instrumental.isFile) return null

        val metadata = runCatching {
            val json = JSONObject(metaFile.readText())
            StemMetadata(
                modelId = json.getString("modelId"),
                sourceName = json.getString("sourceName"),
                frameCount = json.getLong("frameCount"),
                sampleRate = json.getInt("sampleRate"),
                createdAt = json.getLong("createdAt"),
                separatorVersion = json.getInt("separatorVersion"),
                backend = json.optString("backend", ComputeBackend.CPU.cacheId),
            )
        }.getOrNull() ?: return null

        if (metadata.separatorVersion != ModelCatalog.SEPARATOR_VERSION) return null
        if (metadata.frameCount <= 0) return null
        val expectedBytes = metadata.frameCount * 8
        if (vocals.length() != expectedBytes || instrumental.length() != expectedBytes) return null
        return CachedStems(key, vocals, instrumental, metadata)
    }

    fun createTempDirectory(): File {
        workRoot.mkdirs()
        val directory = File(workRoot, UUID.randomUUID().toString())
        directory.mkdirs()
        return directory
    }

    fun commit(key: String, tempDirectory: File, metadata: StemMetadata): CachedStems {
        writeMetadata(tempDirectory, metadata)
        root.mkdirs()
        val target = File(root, key)
        if (target.exists()) target.deleteRecursively()
        if (!tempDirectory.renameTo(target)) {
            tempDirectory.copyRecursively(target, overwrite = true)
            tempDirectory.deleteRecursively()
        }
        return find(key) ?: error("Could not commit the separated stems to cache")
    }

    fun deleteTemp(directory: File) {
        directory.deleteRecursively()
    }

    fun clear() {
        root.deleteRecursively()
        workRoot.deleteRecursively()
    }

    private fun writeMetadata(directory: File, metadata: StemMetadata) {
        val json = JSONObject()
            .put("modelId", metadata.modelId)
            .put("sourceName", metadata.sourceName)
            .put("frameCount", metadata.frameCount)
            .put("sampleRate", metadata.sampleRate)
            .put("createdAt", metadata.createdAt)
            .put("separatorVersion", metadata.separatorVersion)
            .put("backend", metadata.backend)
        File(directory, META_FILE).writeText(json.toString())
    }

    companion object {
        const val VOCALS_FILE = "vocals.f32"
        const val INSTRUMENTAL_FILE = "instrumental.f32"
        const val META_FILE = "meta.json"
    }
}
