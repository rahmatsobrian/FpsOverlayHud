package com.fpsoverlay.hud.stats

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.RandomAccessFile

/**
 * Works on every device, no special permission grants beyond the ones already
 * declared in the manifest. Trade-off: GPU usage and system-wide CPU-per-core
 * load are NOT exposed by the public SDK, so those fields stay null here.
 * FPS/frame time in this mode reflect our own overlay window's Choreographer,
 * not the game's -- good enough as a rough proxy, but Root/Shizuku give the
 * real per-app numbers via gfxinfo.
 */
class NonRootStatsProvider(
    private val context: Context,
    private val fpsTracker: FpsTracker,
) : StatsProvider {

    override val mode = AccessMode.NON_ROOT

    private val am get() = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private var lastRxBytes = TrafficStats.getTotalRxBytes()
    private var lastTxBytes = TrafficStats.getTotalTxBytes()
    private var lastSampleNanos = System.nanoTime()

    // /proc/stat is world-readable on stock, non-hardened ROMs (Android 10-13).
    // Some OEM/Android 14+ builds restrict it; fall back gracefully to null.
    private var prevCpuIdle = 0L
    private var prevCpuTotal = 0L

    override suspend fun sample(enabledMetrics: Set<Metric>) = withContext(Dispatchers.IO) {
        StatsSnapshot(
            cpuUsagePercent = if (Metric.CPU in enabledMetrics) readCpuUsagePercentFromProcStat() else null,
            cpuTempC = if (Metric.CPU in enabledMetrics) readThermalZoneTempC() else null,
            cpuFreqMhz = if (Metric.CPU in enabledMetrics) readCpuFreqMhz() else null,
            ramUsedMb = if (Metric.RAM in enabledMetrics) readRamUsedMb() else null,
            ramTotalMb = if (Metric.RAM in enabledMetrics) readRamTotalMb() else null,
            batteryTempC = if (Metric.BATTERY in enabledMetrics) readBatteryTempC() else null,
            batteryLevelPercent = if (Metric.BATTERY in enabledMetrics) readBatteryLevel() else null,
            netDownKbps = if (Metric.NETWORK in enabledMetrics) readNetKbps().first else null,
            netUpKbps = if (Metric.NETWORK in enabledMetrics) readNetKbps().second else null,
            fps = if (Metric.FPS_FRAMETIME in enabledMetrics) fpsTracker.currentFps else null,
            frameTimeMs = if (Metric.FPS_FRAMETIME in enabledMetrics) fpsTracker.currentFrameTimeMs else null,
            jankCount = if (Metric.FPS_FRAMETIME in enabledMetrics) fpsTracker.jankCount else null,
            renderApi = null, // needs dumpsys / Shizuku or Root to query reliably
            gpuUsagePercent = null,
            gpuFreqMhz = null,
            gpuMemUsedMb = null,
        )
    }

    private fun readCpuUsagePercentFromProcStat(): Int? = try {
        RandomAccessFile("/proc/stat", "r").use { f ->
            val line = f.readLine() ?: return null
            val parts = line.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
            if (parts.size < 4) return null
            val idle = parts[3] + (parts.getOrElse(4) { 0L })
            val total = parts.sum()
            val idleDelta = idle - prevCpuIdle
            val totalDelta = total - prevCpuTotal
            prevCpuIdle = idle
            prevCpuTotal = total
            if (totalDelta <= 0) null else (100 * (totalDelta - idleDelta) / totalDelta).toInt().coerceIn(0, 100)
        }
    } catch (_: Exception) {
        null
    }

    private fun readThermalZoneTempC(): Float? = try {
        // type "cpu" zone name/index varies per SoC; scan and pick the first
        // zone whose type mentions cpu, else fall back to zone0.
        val base = java.io.File("/sys/class/thermal")
        val zones = base.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
        var picked: java.io.File? = null
        for (z in zones) {
            val type = runCatching { java.io.File(z, "type").readText().trim().lowercase() }.getOrNull() ?: continue
            if (type.contains("cpu")) { picked = z; break }
        }
        val zoneFile = picked ?: zones.firstOrNull() ?: return null
        val raw = java.io.File(zoneFile, "temp").readText().trim().toFloat()
        if (raw > 1000) raw / 1000f else raw
    } catch (_: Exception) {
        null
    }

    private fun readCpuFreqMhz(): Int? = try {
        // World-readable on many stock ROMs even without root; restricted on
        // some OEM/hardened builds, in which case this just returns null.
        val khz = java.io.File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").readText().trim().toLongOrNull()
        khz?.let { (it / 1000).toInt() }
    } catch (_: Exception) {
        null
    }

    private fun readRamUsedMb(): Int? = try {
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        ((info.totalMem - info.availMem) / (1024 * 1024)).toInt()
    } catch (_: Exception) {
        null
    }

    private fun readRamTotalMb(): Int? = try {
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        (info.totalMem / (1024 * 1024)).toInt()
    } catch (_: Exception) {
        null
    }

    private fun readBatteryTempC(): Float? = try {
        // BATTERY_PROPERTY has no temperature constant; read the sticky
        // ACTION_BATTERY_CHANGED intent instead (registerReceiver with a
        // null receiver just returns the last sticky broadcast, no leak).
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val tenthsC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (tenthsC == Int.MIN_VALUE) null else tenthsC / 10f
    } catch (_: Exception) {
        null
    }

    private fun readBatteryLevel(): Int? = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) {
        null
    }

    private fun readNetKbps(): Pair<Int, Int> {
        val nowRx = TrafficStats.getTotalRxBytes()
        val nowTx = TrafficStats.getTotalTxBytes()
        val nowNanos = System.nanoTime()
        val elapsedSec = ((nowNanos - lastSampleNanos).coerceAtLeast(1)) / 1_000_000_000.0
        val downKbps = if (nowRx >= lastRxBytes) ((nowRx - lastRxBytes) * 8 / 1000.0 / elapsedSec).toInt() else 0
        val upKbps = if (nowTx >= lastTxBytes) ((nowTx - lastTxBytes) * 8 / 1000.0 / elapsedSec).toInt() else 0
        lastRxBytes = nowRx; lastTxBytes = nowTx; lastSampleNanos = nowNanos
        return downKbps to upKbps
    }
}
