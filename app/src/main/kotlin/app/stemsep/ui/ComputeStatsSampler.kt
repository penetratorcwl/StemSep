package app.stemsep.ui

import android.os.Process
import android.os.SystemClock
import java.io.File

data class ComputeStatsSample(
    val cpuPercent: Float?,
    val gpuPercent: Float?,
)

class ComputeStatsSampler(
    private val cpuCores: Int,
) {
    private var lastCpuMillis = Process.getElapsedCpuTime()
    private var lastWallMillis = SystemClock.elapsedRealtime()

    fun reset() {
        lastCpuMillis = Process.getElapsedCpuTime()
        lastWallMillis = SystemClock.elapsedRealtime()
    }

    fun sample(): ComputeStatsSample = ComputeStatsSample(
        cpuPercent = cpuPercent(),
        gpuPercent = gpuPercent(),
    )

    private fun cpuPercent(): Float? {
        val cpuMillis = Process.getElapsedCpuTime()
        val wallMillis = SystemClock.elapsedRealtime()
        val cpuDelta = cpuMillis - lastCpuMillis
        val wallDelta = wallMillis - lastWallMillis
        lastCpuMillis = cpuMillis
        lastWallMillis = wallMillis
        if (cpuDelta <= 0 || wallDelta <= 0) return null
        val totalCapacity = wallDelta.toDouble() * cpuCores.coerceAtLeast(1)
        return ((cpuDelta / totalCapacity) * 100.0)
            .toFloat()
            .coerceIn(0f, 100f)
    }

    private fun gpuPercent(): Float? {
        for (path in GPU_BUSY_PATHS) {
            val value = runCatching {
                val text = File(path).readText().trim()
                NUMBER.find(text)?.value?.toFloatOrNull()
            }.getOrNull() ?: continue
            if (value in 0f..100f) return value
        }
        return null
    }

    private companion object {
        val NUMBER = Regex("""([0-9]+(?:\.[0-9]+)?)""")
        val GPU_BUSY_PATHS = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/kernel/gpu/gpu_busy",
        )
    }
}
