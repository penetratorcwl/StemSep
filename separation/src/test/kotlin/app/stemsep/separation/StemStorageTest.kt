package app.stemsep.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StemStorageTest {
    @Test
    fun acceptsMatchingStemLengths() {
        val directory = Files.createTempDirectory("stemsep-storage").toFile()
        try {
            val stems = session(directory, frameCount = 3, vocalsBytes = 24, instrumentalBytes = 24)
            assertTrue(validateSeparatedStems(stems))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsTruncatedStemFiles() {
        val directory = Files.createTempDirectory("stemsep-storage").toFile()
        try {
            val stems = session(directory, frameCount = 3, vocalsBytes = 24, instrumentalBytes = 16)
            assertFalse(validateSeparatedStems(stems))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsMissingStemFiles() {
        val directory = Files.createTempDirectory("stemsep-storage").toFile()
        try {
            val vocals = File(directory, "vocals.f32").apply { writeBytes(ByteArray(8)) }
            val instrumental = File(directory, "instrumental.f32")
            val stems = SeparatedStems(
                vocals = vocals,
                instrumental = instrumental,
                metadata = metadata(frameCount = 1),
            )
            assertFalse(validateSeparatedStems(stems))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun createSessionReplacesPreviousSession() {
        val directory = Files.createTempDirectory("stemsep-storage").toFile()
        try {
            val root = File(directory, "session")
            val work = File(directory, "work")
            val storage = StemStorage(root, work)
            root.mkdirs()
            File(root, "old.txt").writeText("old")

            val temp = storage.createSessionDirectory()

            assertFalse(root.exists())
            assertTrue(temp.isDirectory)
            assertTrue(temp.toPath().startsWith(work.toPath()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun finishSessionMovesWorkIntoSessionRoot() {
        val directory = Files.createTempDirectory("stemsep-storage").toFile()
        try {
            val root = File(directory, "session")
            val work = File(directory, "work")
            val storage = StemStorage(root, work)
            val temp = storage.createSessionDirectory()
            File(temp, "vocals.f32").writeBytes(ByteArray(8))
            File(temp, "instrumental.f32").writeBytes(ByteArray(8))

            val stems = storage.finishSession(temp, metadata(frameCount = 1))

            assertEquals(root, stems.vocals.parentFile)
            assertTrue(stems.vocals.isFile)
            assertTrue(stems.instrumental.isFile)
            assertFalse(work.exists())

            storage.clearSession()
            assertFalse(root.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun finishSessionRejectsIncompleteStems() {
        val directory = Files.createTempDirectory("stemsep-storage").toFile()
        try {
            val root = File(directory, "session")
            val work = File(directory, "work")
            val storage = StemStorage(root, work)
            val temp = storage.createSessionDirectory()
            File(temp, "vocals.f32").writeBytes(ByteArray(8))
            File(temp, "instrumental.f32").writeBytes(ByteArray(8))

            assertThrows(IllegalStateException::class.java) {
                storage.finishSession(temp, metadata(frameCount = 2))
            }
            assertFalse(root.exists())
            assertFalse(temp.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun clearsLegacyStemCacheDirectories() {
        val directory = Files.createTempDirectory("stemsep-storage").toFile()
        try {
            val legacy = File(directory, "stems").apply {
                mkdirs()
                File(this, "vocals.f32").writeBytes(ByteArray(8))
            }
            val storage = StemStorage(
                root = File(directory, "session"),
                workRoot = File(directory, "work"),
                legacyRoots = listOf(legacy),
            )

            storage.clearSession()

            assertFalse(legacy.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun session(
        directory: File,
        frameCount: Long,
        vocalsBytes: Int,
        instrumentalBytes: Int,
    ): SeparatedStems = SeparatedStems(
        vocals = File(directory, "vocals.f32").apply { writeBytes(ByteArray(vocalsBytes)) },
        instrumental = File(directory, "instrumental.f32").apply {
            writeBytes(ByteArray(instrumentalBytes))
        },
        metadata = metadata(frameCount),
    )

    private fun metadata(frameCount: Long) = StemMetadata(
        modelId = ModelCatalog.DEFAULT_MODEL_ID,
        sourceName = "song.wav",
        frameCount = frameCount,
        sampleRate = 44_100,
        createdAt = 0L,
        separatorVersion = ModelCatalog.SEPARATOR_VERSION,
        requestedBackend = ComputeBackend.CPU.cacheId,
        effectiveBackend = ComputeBackend.CPU.cacheId,
        cpuThreads = 4,
        acceleratorMode = AcceleratorMode.BALANCED.cacheId,
    )
}
