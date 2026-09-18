package com.fpsoverlay.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.fpsoverlay.hud.stats.StatsSnapshot
import com.fpsoverlay.hud.ui.theme.HudColors

/** One value chip inside a HUD row: the text and whether it should render
 *  orange (a raw metric) or white (a derived/time-based metric, e.g. ms). */
private data class Chip(val text: String, val color: Color)

/** What the user has chosen to show, all in one place so OverlayService only
 *  has to build this once per settings change instead of passing ~10 args. */
data class HudDisplayOptions(
    val scale: Float = 1f,
    val opacity: Float = 0.9f,
    val compact: Boolean = false,
    val showGpu: Boolean = true,
    val showGpuMem: Boolean = true,
    val showCpu: Boolean = true,
    val showCpuClock: Boolean = true,
    val showRam: Boolean = true,
    val showFrame: Boolean = true,
    val showApiLabel: Boolean = true,
    val showBattery: Boolean = false,
    val showNetwork: Boolean = false,
    val showUptime: Boolean = false,
)

/**
 * The floating HUD row grid, styled after the reference screenshots: a
 * colored label column, then as many bold black-outlined value chips as
 * that row has data for. Unlike a fixed 2-column table, each row just wraps
 * whatever chips are non-null, so GPU clock / CPU clock / battery / network
 * all get room instead of being silently dropped.
 */
@Composable
fun OverlayHud(
    snapshot: StatsSnapshot,
    options: HudDisplayOptions = HudDisplayOptions(),
    sessionUptimeSec: Long = 0,
) {
    val scale = options.scale
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.35f * options.opacity))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (options.showGpu) {
            val chips = buildList {
                snapshot.gpuTempC?.let { add(Chip("${it.toInt()}°C", HudColors.ValueOrange)) }
                snapshot.gpuUsagePercent?.let { add(Chip("$it%", HudColors.ValueOrange)) }
                snapshot.gpuFreqMhz?.let { add(Chip("${it}MHz", HudColors.ValueOrange)) }
                // Usage/clock read from devfreq, which a lot of MediaTek
                // builds block for shell/Shizuku (root-only). Say so
                // explicitly instead of silently showing just the temp (or
                // nothing at all), so it reads as a device limit, not a bug.
                if (snapshot.gpuUsagePercent == null && snapshot.gpuFreqMhz == null) {
                    add(Chip("N/A (root)", HudColors.ValueDim))
                }
            }
            HudRow("GPU", HudColors.LabelGpu, chips, scale, options.compact)
        }
        if (options.showGpuMem) {
            val chips = buildList {
                snapshot.gpuMemUsedMb?.let { add(Chip("${it}MB", HudColors.ValueOrange)) }
            }
            // MEM = GPU memory (VRAM), distinct from the RAM row below which
            // is total physical system RAM.
            HudRow("MEM", HudColors.LabelMem, chips, scale, options.compact)
        }
        if (options.showCpu) {
            val chips = buildList {
                snapshot.cpuTempC?.let { add(Chip("${it.toInt()}°C", HudColors.ValueOrange)) }
                snapshot.cpuUsagePercent?.let { add(Chip("$it%", HudColors.ValueOrange)) }
                if (options.showCpuClock) snapshot.cpuFreqMhz?.let { add(Chip("${it}MHz", HudColors.ValueOrange)) }
            }
            HudRow("CPU", HudColors.LabelCpu, chips, scale, options.compact)
        }
        if (options.showRam) {
            // Left = RAM currently in use, right = total physical RAM.
            val chips = buildList {
                snapshot.ramUsedMb?.let { add(Chip("${it}MB", HudColors.ValueOrange)) }
                snapshot.ramTotalMb?.let { add(Chip("${it}MB", HudColors.ValueOrange)) }
            }
            HudRow("RAM", HudColors.LabelRam, chips, scale, options.compact)
        }
        if (options.showFrame) {
            val chips = buildList {
                snapshot.fps?.let { add(Chip("${it}FPS", HudColors.ValueWhite)) }
                snapshot.frameTimeMs?.let { add(Chip("${it.toInt()}ms", HudColors.ValueWhite)) }
            }
            // Label shows the detected render backend (Adreno/Mali + GLES or
            // Vulkan) instead of a hardcoded "GFX", when that's enabled.
            val label = if (options.showApiLabel) (snapshot.renderApi ?: "GFX") else "FPS"
            HudRow(label, HudColors.LabelApi, chips, scale, options.compact)
        }
        if (options.showBattery) {
            val chips = buildList {
                snapshot.batteryLevelPercent?.let { add(Chip("$it%", HudColors.ValueOrange)) }
                snapshot.batteryTempC?.let { add(Chip("${it.toInt()}°C", HudColors.ValueOrange)) }
            }
            HudRow("BATT", HudColors.LabelCpu, chips, scale, options.compact)
        }
        if (options.showNetwork) {
            val chips = buildList {
                snapshot.netDownKbps?.let { add(Chip("↓${it}kb", HudColors.ValueOrange)) }
                snapshot.netUpKbps?.let { add(Chip("↑${it}kb", HudColors.ValueOrange)) }
            }
            HudRow("NET", HudColors.LabelGpu, chips, scale, options.compact)
        }
        if (options.showUptime) {
            val m = sessionUptimeSec / 60
            val s = sessionUptimeSec % 60
            HudRow("TIME", HudColors.LabelApi, listOf(Chip("%02d:%02d".format(m, s), HudColors.ValueWhite)), scale, options.compact)
        }
    }
}

@Composable
private fun HudRow(label: String, labelColor: Color, chips: List<Chip>, scale: Float, compact: Boolean) {
    if (chips.isEmpty()) return // nothing this access mode could read - skip the row instead of showing it empty
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!compact) {
            OutlinedText(label, labelColor, scale, modifier = Modifier.width((64 * scale).dp))
            Spacer(Modifier.width(10.dp))
        }
        chips.forEachIndexed { index, chip ->
            OutlinedText(chip.text, chip.color, scale, modifier = Modifier.widthIn(min = (68 * scale).dp))
            if (index != chips.lastIndex) Spacer(Modifier.width(10.dp))
        }
    }
}

/** Bold text with a real black stroke outline behind the fill, matching the
 *  reference image's "comic sans-ish outlined HUD font" look. */
@Composable
private fun OutlinedText(text: String, color: Color, scale: Float, modifier: Modifier = Modifier) {
    val fontSizePx = 20f * scale * 2.6f // approximate sp->px at typical HUD density
    Canvas(modifier = modifier.height((26 * scale).dp)) {
        val baseline = size.height * 0.75f
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = fontSizePx
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD)
                textAlign = android.graphics.Paint.Align.LEFT
            }
            // stroke pass (outline)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 5f * scale
            paint.color = android.graphics.Color.BLACK
            drawText(text, 0f, baseline, paint)
            // fill pass (colored text on top)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = android.graphics.Color.argb(
                (color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()
            )
            drawText(text, 0f, baseline, paint)
        }
    }
}
