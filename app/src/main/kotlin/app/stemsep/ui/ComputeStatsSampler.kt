package app.stemsep.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import kotlin.math.abs

data class ComputeStatsSample(
    val cpuPercent: Float?,
    val gpuPercent: Float?,
    val powerWatts: Float?,
    val energyMilliAmpHours: Float?,
    val charging: Boolean,
)

class ComputeStatsSampler(
    private val context: Context,
    private val cpuCores: Int,
) {
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private var lastCpuMillis = Process.getElapsedCpuTime()
    private var lastWallMillis = SystemClock.elapsedRealtime()
    private var lastBatteryMillis = SystemClock.elapsedRealtime()
    private var initialChargeUah: Int? = null
    private var integratedMah = 0.0
    private val gpuCounters = mutableMapOf<String, GpuCounter>()

    fun reset() {
        lastCpuMillis = Process.getElapsedCpuTime()
        lastWallMillis = SystemClock.elapsedRealtime()
        lastBatteryMillis = SystemClock.elapsedRealtime()
        initialChargeUah = readBatteryInt(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it > 0 }
        integratedMah = 0.0
        gpuCounters.clear()
    }

    fun sample(): ComputeStatsSample {
        val now = SystemClock.elapsedRealtime()
        val battery = batterySnapshot(now)
        return ComputeStatsSample(
            cpuPercent = cpuPercent(),
            gpuPercent = gpuPercent(),
            powerWatts = battery.powerWatts,
            energyMilliAmpHours = battery.energyMah,
            charging = battery.charging,
        )
    }

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

    private fun batterySnapshot(now: Long): BatterySnapshot {
        val currentUa = readBatteryInt(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != 0 }
        val voltageMv = readVoltageMv()
        val status = readBatteryInt(BatteryManager.BATTERY_PROPERTY_STATUS)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val powerWatts = if (currentUa != null && voltageMv > 0) {
            (abs(currentUa.toDouble()) * voltageMv / 1_000_000_000.0).toFloat()
        } else {
            null
        }

        val chargeUah = readBatteryInt(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it > 0 }
        if (initialChargeUah == null) initialChargeUah = chargeUah
        val elapsedHours = if (lastBatteryMillis > 0L) {
            (now - lastBatteryMillis).coerceAtLeast(0L) / 3_600_000.0
        } else {
            0.0
        }
        if (!charging && currentUa != null) {
            integratedMah += (abs(currentUa) / 1000.0) * elapsedHours
        }
        lastBatteryMillis = now

        val chargeDeltaMah = initialChargeUah?.let { initial ->
            chargeUah
                ?.takeIf { it <= initial }
                ?.let { (initial - it) / 1000.0 }
        }
        val energyMah = when {
            charging -> null
            chargeDeltaMah != null && chargeDeltaMah > 0.0 -> chargeDeltaMah.toFloat()
            integratedMah > 0.0 -> integratedMah.toFloat()
            else -> null
        }
        return BatterySnapshot(
            powerWatts = powerWatts,
            energyMah = energyMah,
            charging = charging,
        )
    }

    private fun gpuPercent(): Float? {
        var idle = false
        for (path in GPU_COUNTER_PATHS) {
            val numbers = readNumbers(path)
            if (numbers.isEmpty()) continue
            if (numbers.size < 2) {
                val value = numbers[0]
                if (value in 0f..100f) return value
                continue
            }
            val busy = numbers[0]
            val total = numbers[1]
            if (total <= 0f || busy < 0f) continue
            val previous = gpuCounters[path]
            gpuCounters[path] = GpuCounter(busy, total)
            if (previous == null || total < previous.total || busy < previous.busy) {
                continue
            }
            val busyDelta = busy - previous.busy
            val totalDelta = total - previous.total
            if (totalDelta > 0f) {
                return ((busyDelta / totalDelta) * 100f).coerceIn(0f, 100f)
            }
            if (busyDelta == 0f) idle = true
        }
        for (path in GPU_PERCENT_PATHS) {
            val value = readNumbers(path).firstOrNull() ?: continue
            if (value in 0f..100f) return value
        }
        return if (idle) 0f else null
    }

    private fun readBatteryInt(property: Int): Int? = runCatching {
        batteryManager?.getIntProperty(property)
    }.getOrNull()?.takeUnless { it == Int.MIN_VALUE }

    private fun readVoltageMv(): Int = runCatching {
        context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
    }.getOrDefault(0)

    private fun readNumbers(path: String): List<Float> = runCatching {
        val text = File(path).readText().trim()
        NUMBER.findAll(text).mapNotNull { it.value.toFloatOrNull() }.toList()
    }.getOrDefault(emptyList())

    private data class GpuCounter(val busy: Float, val total: Float)

    private data class BatterySnapshot(
        val powerWatts: Float?,
        val energyMah: Float?,
        val charging: Boolean,
    )

    private companion object {
        val NUMBER = Regex("""([0-9]+(?:\.[0-9]+)?)""")
        val GPU_PERCENT_PATHS = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/kernel/gpu/gpu_busy_percentage",
            "/sys/kernel/gpu/gpu_load",
        )
        val GPU_COUNTER_PATHS = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy",
            "/sys/kernel/gpu/gpubusy",
            "/sys/kernel/gpu/gpu_busy",
        )
    }
}
