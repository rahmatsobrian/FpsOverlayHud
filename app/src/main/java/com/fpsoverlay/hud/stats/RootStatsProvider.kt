package com.fpsoverlay.hud.stats

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Uses libsu to run a single batched `su -c` shell call per sample so we
 * only pay one root-prompt / binder round trip per tick, then parses every
 * metric out of that one blob. GPU sysfs paths differ per vendor, so we try
 * the common ones (Adreno / Mali / PowerVR) in order and keep the first hit.
 */
class RootStatsProvider(private val foregroundPackage: () -> String?) : StatsProvider {

    override val mode = AccessMode.ROOT

    private val gpuUsagePaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpubusy_percentage",          // some Adreno
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",          // other Adreno builds
        "/sys/kernel/gpu/gpu_busy",                              // Mali (generic)
        "/sys/class/devfreq/gpufreq/gpu_busy",                   // Mali (MTK generic alias)
        "/sys/class/devfreq/13000000.mali/device/utilization",   // Mali (MTK Dimensity, this device)
        "/sys/class/misc/mali0/device/utilization",              // Mali (some Dimensity builds)
    )
    private val gpuFreqPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpuclk",
        "/sys/class/devfreq/gpufreq/cur_freq",                   // Mali (MTK generic alias)
        "/sys/class/devfreq/13000000.mali/cur_freq",             // Mali (MTK Dimensity, this device)
    )
    private val gpuMemPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_used_memory",       // some Adreno builds (KB)
        "/sys/class/kgsl/kgsl-3d0/gpu_available_memory",  // fallback, not "used" but better than nothing
        "/sys/class/misc/mali0/device/memory_usage",      // some Mali builds (bytes)
    )
    private val cpuFreqPaths = listOf(
        "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", // kHz, biggest/first online core
    )

    override suspend fun sample(enabledMetrics: Set<Metric>): StatsSnapshot = withContext(Dispatchers.IO) {
        val script = buildString {
            appendLine("echo ---CPU---")
            appendLine("cat /proc/stat | head -1")
            appendLine("echo ---TEMP---")
            appendLine("for z in /sys/class/thermal/thermal_zone*/; do t=$(cat \${z}type 2>/dev/null); v=$(cat \${z}temp 2>/dev/null); echo \"\$t \$v\"; done")
            appendLine("echo ---MEM---")
            appendLine("cat /proc/meminfo | head -3")
            appendLine("echo ---GPUUSAGE---")
            gpuUsagePaths.forEach { appendLine("cat $it 2>/dev/null") }
            appendLine("echo ---GPUFREQ---")
            gpuFreqPaths.forEach { appendLine("cat $it 2>/dev/null") }
            appendLine("echo ---GPUMEM---")
            gpuMemPaths.forEach { appendLine("cat $it 2>/dev/null") }
            appendLine("echo ---GPUTEMP---")
            appendLine("dumpsys thermalservice 2>/dev/null | grep -i 'mName=GPU'")
            appendLine("echo ---CPUFREQ---")
            cpuFreqPaths.forEach { appendLine("cat $it 2>/dev/null") }
            val pkg = foregroundPackage()
            if (pkg != null && Metric.FPS_FRAMETIME in enabledMetrics) {
                appendLine("echo ---GFXINFO---")
                appendLine("dumpsys gfxinfo $pkg framestats")
            }
        }
        val out = Shell.cmd(script).exec().out
        parse(out, enabledMetrics)
    }

    private fun parse(lines: List<String>, enabledMetrics: Set<Metric>): StatsSnapshot {
        fun section(tag: String): List<String> {
            val start = lines.indexOf("---$tag---")
            if (start == -1) return emptyList()
            val nextTagIdx = lines.drop(start + 1).indexOfFirst { it.startsWith("---") }
            val end = if (nextTagIdx == -1) lines.size else start + 1 + nextTagIdx
            return lines.subList(start + 1, end)
        }

        val cpuUsage = if (Metric.CPU in enabledMetrics) {
            section("CPU").firstOrNull()?.let { parseProcStatLine(it) }
        } else null

        val cpuTemp = if (Metric.CPU in enabledMetrics) {
            section("TEMP").firstNotNullOfOrNull { line ->
                if (line.contains("cpu", ignoreCase = true)) {
                    line.substringAfterLast(' ').toFloatOrNull()?.let { if (it > 1000) it / 1000f else it }
                } else null
            }
        } else null

        val mem = if (Metric.RAM in enabledMetrics) section("MEM") else emptyList()
        val total = mem.getOrNull(0)?.filter { it.isDigit() }?.toLongOrNull()
        val free = mem.getOrNull(1)?.filter { it.isDigit() }?.toLongOrNull()
        val ramTotalMb = total?.let { (it / 1024).toInt() }
        val ramUsedMb = if (total != null && free != null) ((total - free) / 1024).toInt() else null

        val gpuUsage = if (Metric.GPU in enabledMetrics) {
            section("GPUUSAGE").firstNotNullOfOrNull { it.trim().filter { c -> c.isDigit() }.toIntOrNull() }
        } else null
        val gpuFreqHz = if (Metric.GPU in enabledMetrics) {
            section("GPUFREQ").firstNotNullOfOrNull { it.trim().toLongOrNull() }
        } else null
        val gpuMemRaw = if (Metric.MEM_GPU in enabledMetrics) {
            section("GPUMEM").firstNotNullOfOrNull { it.trim().toLongOrNull() }
        } else null
        // Values come back in KB on most Adreno builds, bytes on some Mali
        // ones; anything over ~4,000,000 is almost certainly bytes already.
        val gpuMemUsedMb = gpuMemRaw?.let { if (it > 4_000_000) (it / 1024 / 1024).toInt() else (it / 1024).toInt() }

        val gpuTemp = if (Metric.GPU in enabledMetrics) {
            section("GPUTEMP").firstNotNullOfOrNull { line ->
                Regex("mValue=(-?[0-9.]+)").find(line)?.groupValues?.get(1)?.toFloatOrNull()
            }?.let { if (it > 1000) it / 1000f else it }
        } else null

        val cpuFreqMhz = if (Metric.CPU in enabledMetrics) {
            section("CPUFREQ").firstNotNullOfOrNull { it.trim().toLongOrNull() }?.let { (it / 1000).toInt() }
        } else null

        var fps: Int? = null
        var frameTimeMs: Float? = null
        if (Metric.FPS_FRAMETIME in enabledMetrics) {
            val frameTimes = section("GFXINFO")
                .mapNotNull { it.trim().toDoubleOrNull() }
                .filter { it > 0 }
            if (frameTimes.isNotEmpty()) {
                // framestats values are in nanoseconds between Vsync timestamps
                val avgNs = frameTimes.average()
                frameTimeMs = (avgNs / 1_000_000).toFloat()
                if (frameTimeMs!! > 0) fps = (1000 / frameTimeMs!!).toInt().coerceIn(0, 500)
            }
        }

        return StatsSnapshot(
            gpuUsagePercent = gpuUsage?.coerceIn(0, 100),
            gpuFreqMhz = gpuFreqHz?.let { (it / 1_000_000).toInt() },
            gpuMemUsedMb = gpuMemUsedMb,
            gpuTempC = gpuTemp,
            cpuUsagePercent = cpuUsage,
            cpuTempC = cpuTemp,
            cpuFreqMhz = cpuFreqMhz,
            ramUsedMb = ramUsedMb,
            ramTotalMb = ramTotalMb,
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
