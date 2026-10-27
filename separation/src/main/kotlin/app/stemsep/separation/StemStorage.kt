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
    val requestedBackend: String,
    val effectiveBackend: String,
    val fallbackReason: String? = null,
    val cpuThreads: Int = 4,
    val acceleratorMode: String = AcceleratorMode.BALANCED.cacheId,
) {
    fun toJsonString(): String = JSONObject()
        .put("modelId", modelId)
        .put("sourceName", sourceName)
        .put("frameCount", frameCount)
        .put("sampleRate", sampleRate)
        .put("createdAt", createdAt)
        .put("separatorVersion", separatorVersion)
        .put("requestedBackend", requestedBackend)
        .put("effectiveBackend", effectiveBackend)
        .put("fallbackReason", fallbackReason ?: "")
        .put("cpuThreads", cpuThreads)
        .put("acceleratorMode", acceleratorMode)
        .toString()

    companion object {
        fun fromJsonString(value: String): StemMetadata? = runCatching {
            val json = JSONObject(value)
            StemMetadata(
                modelId = json.getString("modelId"),
                sourceName = json.getString("sourceName"),
                frameCount = json.getLong("frameCount"),
                sampleRate = json.getInt("sampleRate"),
                createdAt = json.optLong("createdAt", 0L),
                separatorVersion = json.optInt("separatorVersion", 0),
                requestedBackend = json.optString(
                    "requestedBackend",
                    ComputeBackend.CPU.cacheId,
                ),
                effectiveBackend = json.optString(
                    "effectiveBackend",
                    ComputeBackend.CPU.cacheId,
                ),
                fallbackReason = json.optString("fallbackReason")
                    .takeIf { it.isNotBlank() },
                cpuThreads = json.optInt("cpuThreads", 4),
                acceleratorMode = json.optString(
                    "acceleratorMode",
                    AcceleratorMode.BALANCED.cacheId,
                ),
            ).takeIf { it.frameCount > 0 && it.sampleRate > 0 }
        }.getOrNull()
    }
}

data class SeparatedStems(
    val vocals: File,
    val instrumental: File,
    val metadata: StemMetadata,
)

/**
 * Owns the one temporary stem session shown by the UI.
 *
 * There is deliberately no lookup or persistent cache: a new separation deletes
 * the previous session, and the app clears the session when it exits. Export is
 * the only way to keep a result.
 */
class StemStorage internal constructor(
    private val root: File,
    private val workRoot: File,
    private val legacyRoots: List<File> = emptyList(),
) {
    constructor(context: Context) : this(
        File(context.cacheDir, SESSION_DIRECTORY),
        File(context.cacheDir, WORK_DIRECTORY),
        listOf(
            File(context.cacheDir, LEGACY_SESSION_DIRECTORY),
            File(context.cacheDir, LEGACY_WORK_DIRECTORY),
        ),
    )

    fun createSessionDirectory(): File {
        clearSession()
        workRoot.mkdirs()
        val directory = File(workRoot, UUID.randomUUID().toString())
        if (!directory.mkdirs()) {
            error("Could not create a temporary stem directory")
        }
        return directory
    }

    fun finishSession(tempDirectory: File, metadata: StemMetadata): SeparatedStems {
        if (root.exists() && !root.deleteRecursively()) {
            error("Could not clear the previous stem session")
        }
        try {
            if (!tempDirectory.renameTo(root)) {
                tempDirectory.copyRecursively(root, overwrite = true)
                tempDirectory.deleteRecursively()
            }
            workRoot.deleteRecursively()
            val stems = SeparatedStems(
                vocals = File(root, VOCALS_FILE),
                instrumental = File(root, INSTRUMENTAL_FILE),
                metadata = metadata,
            )
            if (!validate(stems)) {
                error("The completed stem session is incomplete")
            }
            return stems
        } catch (error: Exception) {
            root.deleteRecursively()
            throw error
        }
    }

    fun validate(stems: SeparatedStems): Boolean = validateSeparatedStems(stems)

    fun clearSession() {
        root.deleteRecursively()
        workRoot.deleteRecursively()
        clearLegacyCaches()
    }

    fun clearLegacyCaches() {
        legacyRoots.forEach { it.deleteRecursively() }
    }

    fun deleteTemp(directory: File) {
        directory.deleteRecursively()
        if (workRoot.exists() && workRoot.listFiles().isNullOrEmpty()) {
            workRoot.deleteRecursively()
        }
    }

    companion object {
        const val VOCALS_FILE = "vocals.f32"
        const val INSTRUMENTAL_FILE = "instrumental.f32"
        private const val SESSION_DIRECTORY = "stemsep-session"
        private const val WORK_DIRECTORY = "stemsep-session-work"
        private const val LEGACY_SESSION_DIRECTORY = "stems"
        private const val LEGACY_WORK_DIRECTORY = "stems-work"
    }
}

fun validateSeparatedStems(stems: SeparatedStems): Boolean {
    val expectedBytes = stems.metadata.frameCount * 8
    return stems.metadata.frameCount > 0 &&
        stems.metadata.sampleRate > 0 &&
        stems.vocals.isFile &&
        stems.instrumental.isFile &&
        stems.vocals.length() == expectedBytes &&
        stems.instrumental.length() == expectedBytes
}
