package com.fpsoverlay.hud.stats

/** One sampled snapshot of everything the HUD can show. Any field can be null
 *  if the active [AccessMode] can't read that value on this device. */
data class StatsSnapshot(
    val gpuUsagePercent: Int? = null,
    val gpuFreqMhz: Int? = null,
    val gpuMemUsedMb: Int? = null,
    /** GPU temp, read from the Thermal HAL. Unlike usage/clock this is
     *  readable without root on most devices (MediaTek+SELinux included),
     *  so it's kept separate: it can be non-null even when the two above
     *  are null because devfreq is blocked for the shell/Shizuku user. */
    val gpuTempC: Float? = null,
    val cpuUsagePercent: Int? = null,
    val cpuTempC: Float? = null,
    val cpuFreqMhz: Int? = null,
    val ramUsedMb: Int? = null,
    val ramTotalMb: Int? = null,
    val batteryTempC: Float? = null,
    val batteryLevelPercent: Int? = null,
    val batteryDrainMwNow: Int? = null,
    val netDownKbps: Int? = null,
    val netUpKbps: Int? = null,
    val fps: Int? = null,
    val frameTimeMs: Float? = null,
    val jankCount: Int? = null,
    val renderApi: String? = null, // e.g. "D3D11", "OpenGL ES 3.2", "Vulkan"
)

enum class AccessMode { ROOT, SHIZUKU, NON_ROOT }

/** Aggregate min/avg/max captured for the current overlay session, shown on stop. */
data class SessionSummary(
    val durationSec: Long,
    val fpsMin: Int,
    val fpsAvg: Int,
    val fpsMax: Int,
    val frameTimeAvgMs: Float,
    val jankTotal: Int,
    val cpuTempMaxC: Float?,
)

interface StatsProvider {
    val mode: AccessMode
    /** Returns null for any metric it cannot read; never throws. */
    suspend fun sample(enabledMetrics: Set<Metric>): StatsSnapshot
}

enum class Metric {
    GPU, MEM_GPU, CPU, RAM, BATTERY, NETWORK, FPS_FRAMETIME
}
