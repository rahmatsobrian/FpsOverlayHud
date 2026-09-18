package com.fpsoverlay.hud.stats

import com.fpsoverlay.hud.shizuku.ShizukuUserServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Runs shell commands through a Shizuku-bound UserService (see the
 * `shizuku` package), so we get most of the same data as Root mode
 * (dumpsys gfxinfo, /proc, /sys) but the user only has to enable
 * wireless/ADB debugging + the Shizuku app once, no su binary required.
 * Requires the user has granted this app's Shizuku permission (requested
 * from MainActivity).
 */
class ShizukuStatsProvider(private val foregroundPackage: () -> String?) : StatsProvider {

    override val mode = AccessMode.SHIZUKU

    companion object {
        fun isAvailable(): Boolean =
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private val gpuUsagePaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpubusy_percentage",           // Adreno
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",          // Adreno (alt)
        "/sys/kernel/gpu/gpu_busy",                              // Mali (generic)
        "/sys/class/devfreq/gpufreq/gpu_busy",                   // Mali (generic MTK alias)
        "/sys/class/devfreq/13000000.mali/device/utilization",   // Mali (MTK Dimensity, this device)
        "/sys/class/misc/mali0/device/utilization",              // Mali (some Dimensity builds)
    )
    private val gpuFreqPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpuclk",                       // Adreno
        "/sys/class/devfreq/gpufreq/cur_freq",                   // Mali (generic MTK alias)
        "/sys/class/devfreq/13000000.mali/cur_freq",             // Mali (MTK Dimensity, this device)
    )
    private val gpuMemPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_used_memory",
        "/sys/class/kgsl/kgsl-3d0/gpu_available_memory",
        "/sys/class/misc/mali0/device/memory_usage",
    )
    private val cpuFreqPaths = listOf(
        "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
    )

    private suspend fun runShizukuShell(cmd: String): List<String> = try {
        ShizukuUserServiceManager.exec(cmd).lines()
    } catch (_: Exception) {
        emptyList()
    }

    override suspend fun sample(enabledMetrics: Set<Metric>): StatsSnapshot = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext StatsSnapshot()

        val cpuLine = if (Metric.CPU in enabledMetrics) runShizukuShell("cat /proc/stat | head -1").firstOrNull() else null
        val tempLines = if (Metric.CPU in enabledMetrics) {
            runShizukuShell("for z in /sys/class/thermal/thermal_zone*/; do t=\$(cat \${z}type 2>/dev/null); v=\$(cat \${z}temp 2>/dev/null); echo \"\$t \$v\"; done")
        } else emptyList()
        val cpuFreqLine = if (Metric.CPU in enabledMetrics) {
            runShizukuShell(cpuFreqPaths.joinToString(" || ") { "cat $it 2>/dev/null" }).firstOrNull()
        } else null
        val memLines = if (Metric.RAM in enabledMetrics) runShizukuShell("cat /proc/meminfo | head -3") else emptyList()
        val gpuLine = if (Metric.GPU in enabledMetrics) {
            runShizukuShell(gpuUsagePaths.joinToString(" || ") { "cat $it 2>/dev/null" }).firstOrNull()
        } else null
        val gpuFreqLine = if (Metric.GPU in enabledMetrics) {
            runShizukuShell(gpuFreqPaths.joinToString(" || ") { "cat $it 2>/dev/null" }).firstOrNull()
        } else null
        val gpuMemLine = if (Metric.MEM_GPU in enabledMetrics) {
            runShizukuShell(gpuMemPaths.joinToString(" || ") { "cat $it 2>/dev/null" }).firstOrNull()
        } else null
        // devfreq is blocked by SELinux for the shell/Shizuku user on a lot
        // of MediaTek devices (usage/clock stay null there no matter which
        // path we try), but the Thermal HAL is still readable — so pull GPU
        // temp from there as a fallback that doesn't need root.
        val gpuTempLine = if (Metric.GPU in enabledMetrics) {
            runShizukuShell("dumpsys thermalservice 2>/dev/null | grep -i 'mName=GPU'")
        } else emptyList()

        var fps: Int? = null
        var frameTimeMs: Float? = null
        val pkg = foregroundPackage()
        if (pkg != null && Metric.FPS_FRAMETIME in enabledMetrics) {
            val gfx = runShizukuShell("dumpsys gfxinfo $pkg framestats")
            val nsValues = gfx.mapNotNull { it.trim().toDoubleOrNull() }.filter { it > 0 }
            if (nsValues.isNotEmpty()) {
                val avgNs = nsValues.average()
                frameTimeMs = (avgNs / 1_000_000).toFloat()
                if (frameTimeMs!! > 0) fps = (1000 / frameTimeMs!!).toInt().coerceIn(0, 500)
            }
        }

        val cpuUsage = cpuLine?.let { parseProcStatLine(it) }
        val cpuTemp = tempLines.firstNotNullOfOrNull { line ->
            if (line.contains("cpu", ignoreCase = true)) {
                line.substringAfterLast(' ').toFloatOrNull()?.let { if (it > 1000) it / 1000f else it }
            } else null
        }
        val cpuFreqMhz = cpuFreqLine?.trim()?.toLongOrNull()?.let { (it / 1000).toInt() }
        val total = memLines.getOrNull(0)?.filter { it.isDigit() }?.toLongOrNull()
        val free = memLines.getOrNull(1)?.filter { it.isDigit() }?.toLongOrNull()
        val gpuFreqHz = gpuFreqLine?.trim()?.toLongOrNull()
        val gpuMemRaw = gpuMemLine?.trim()?.toLongOrNull()
        val gpuMemUsedMb = gpuMemRaw?.let { if (it > 4_000_000) (it / 1024 / 1024).toInt() else (it / 1024).toInt() }
        // "mValue=58.1, mType=..., mName=GPU" — grab the number after mValue=.
        val gpuTemp = gpuTempLine.firstNotNullOfOrNull { line ->
            Regex("mValue=(-?[0-9.]+)").find(line)?.groupValues?.get(1)?.toFloatOrNull()
        }?.let { if (it > 1000) it / 1000f else it }

        StatsSnapshot(
            gpuUsagePercent = gpuLine?.trim()?.filter { it.isDigit() }?.toIntOrNull()?.coerceIn(0, 100),
            gpuFreqMhz = gpuFreqHz?.let { (it / 1_000_000).toInt() },
            gpuMemUsedMb = gpuMemUsedMb,
            gpuTempC = gpuTemp,
            cpuUsagePercent = cpuUsage,
            cpuTempC = cpuTemp,
            cpuFreqMhz = cpuFreqMhz,
            ramTotalMb = total?.let { (it / 1024).toInt() },
            ramUsedMb = if (total != null && free != null) ((total - free) / 1024).toInt() else null,
            fps = fps,
            frameTimeMs = frameTimeMs,
        )
    }

    private var prevIdle = 0L
    private var prevTotal = 0L
    private fun parseProcStatLine(line: String): Int? {
        val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return null
        val idle = parts[3] + parts.getOrElse(4) { 0L }
        val total = parts.sum()
        val idleDelta = idle - prevIdle
        val totalDelta = total - prevTotal
        prevIdle = idle; prevTotal = total
        return if (totalDelta <= 0) null else (100 * (totalDelta - idleDelta) / totalDelta).toInt().coerceIn(0, 100)
    }
}
