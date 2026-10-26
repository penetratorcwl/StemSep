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
        for (path in GPU_PERCENT_PATHS) {
            val value = readNumbers(path).firstOrNull() ?: continue
            if (value in 0f..100f) return value
        }
        for (path in GPU_COUNTER_PATHS) {
            val numbers = readNumbers(path)
            if (numbers.size < 2) continue
            val busy = numbers[0]
            val total = numbers[1]
            if (total <= 0f) continue
            return ((busy / total) * 100f).coerceIn(0f, 100f)
        }
        return null
    }

    private fun readNumbers(path: String): List<Float> = runCatching {
        val text = File(path).readText().trim()
        NUMBER.findAll(text).mapNotNull { it.value.toFloatOrNull() }.toList()
    }.getOrDefault(emptyList())

    private companion object {
        val NUMBER = Regex("""([0-9]+(?:\.[0-9]+)?)""")
        val GPU_PERCENT_PATHS = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/kernel/gpu/gpu_busy_percentage",
            "/sys/kernel/gpu/gpu_load",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy",
            "/sys/kernel/gpu/gpu_busy",
        )
        val GPU_COUNTER_PATHS = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy",
            "/sys/kernel/gpu/gpubusy",
            "/sys/kernel/gpu/gpu_busy",
        )
    }
}
