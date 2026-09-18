package com.fpsoverlay.hud

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.fpsoverlay.hud.stats.ForegroundAppDetector
import com.fpsoverlay.hud.stats.StatsSnapshot
import com.fpsoverlay.hud.ui.theme.FpsOverlayTheme
import com.fpsoverlay.hud.ui.theme.SeedBlue
import com.fpsoverlay.hud.ui.theme.SeedGreen
import com.fpsoverlay.hud.ui.theme.SeedOrange
import com.fpsoverlay.hud.ui.theme.SeedPink
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository

    private val overlayPermLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val usageAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val dynamicColor by settings.get(SettingsKeys.DYNAMIC_COLOR, true).collectAsState(initial = true)
            val darkTheme by settings.get(SettingsKeys.DARK_THEME, true).collectAsState(initial = true)
            val accentArgb by settings.get(SettingsKeys.ACCENT_ARGB, 0).collectAsState(initial = 0)
            FpsOverlayTheme(
                darkTheme = darkTheme,
                dynamicColor = dynamicColor && accentArgb == 0,
                accentOverride = if (accentArgb != 0) Color(accentArgb) else null
            ) {
                SettingsScreen(
                    settings = settings,
                    onRequestOverlayPermission = ::requestOverlayPermission,
                    onRequestUsageAccess = ::requestUsageAccess,
                    onRequestShizukuPermission = ::requestShizukuPermission,
                    onRequestRoot = ::requestRoot,
                    onStartOverlay = ::startOverlay,
                    onStopOverlay = ::stopOverlay,
                )
            }
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            overlayPermLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun requestUsageAccess() {
        if (!ForegroundAppDetector(this).hasUsageAccessPermission()) {
            usageAccessLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun requestShizukuPermission() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1001)
            }
        }
        // If Shizuku isn't running, guide the user to install/start it.
    }

    private fun requestRoot() {
        lifecycleScope.launch {
            com.topjohnwu.superuser.Shell.getShell() // triggers the su prompt
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) { requestOverlayPermission(); return }
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
    }
}

/** A representative, fake StatsSnapshot just so the preview card in Settings
 *  has something to show while the real overlay isn't running. */
private val PreviewSnapshot = StatsSnapshot(
    gpuUsagePercent = 99, gpuFreqMhz = 620, gpuMemUsedMb = 512,
    cpuUsagePercent = 13, cpuTempC = 45f, cpuFreqMhz = 1900,
    ramUsedMb = 7354, ramTotalMb = 7443,
    batteryLevelPercent = 85, batteryTempC = 32f,
    netDownKbps = 340, netUpKbps = 40,
    fps = 60, frameTimeMs = 15f,
    renderApi = "Adreno GLES3.2",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    onRequestOverlayPermission: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRequestRoot: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val accessMode by settings.get(SettingsKeys.ACCESS_MODE, "AUTO").collectAsState(initial = "AUTO")
    val dynamicColor by settings.get(SettingsKeys.DYNAMIC_COLOR, true).collectAsState(initial = true)
    val darkTheme by settings.get(SettingsKeys.DARK_THEME, true).collectAsState(initial = true)
    val accentArgb by settings.get(SettingsKeys.ACCENT_ARGB, 0).collectAsState(initial = 0)
    val opacity by settings.get(SettingsKeys.OVERLAY_OPACITY, 0.9f).collectAsState(initial = 0.9f)
    val scale by settings.get(SettingsKeys.OVERLAY_SCALE, 1f).collectAsState(initial = 1f)
    val compactMode by settings.get(SettingsKeys.COMPACT_MODE, false).collectAsState(initial = false)
    val refreshMs by settings.get(SettingsKeys.REFRESH_MS, 500).collectAsState(initial = 500)
    val autoShow by settings.get(SettingsKeys.AUTO_SHOW_FOR_GAMES, false).collectAsState(initial = false)
    val logging by settings.get(SettingsKeys.LOGGING_ENABLED, false).collectAsState(initial = false)

    val metricGpu by settings.get(SettingsKeys.METRIC_GPU, true).collectAsState(initial = true)
    val metricMem by settings.get(SettingsKeys.METRIC_MEM_GPU, true).collectAsState(initial = true)
    val metricCpu by settings.get(SettingsKeys.METRIC_CPU, true).collectAsState(initial = true)
    val metricCpuClock by settings.get(SettingsKeys.METRIC_CPU_CLOCK, true).collectAsState(initial = true)
    val metricRam by settings.get(SettingsKeys.METRIC_RAM, true).collectAsState(initial = true)
    val metricFps by settings.get(SettingsKeys.METRIC_FPS, true).collectAsState(initial = true)
    val showApiLabel by settings.get(SettingsKeys.SHOW_API_LABEL, true).collectAsState(initial = true)
    val metricBattery by settings.get(SettingsKeys.METRIC_BATTERY, false).collectAsState(initial = false)
    val metricNetwork by settings.get(SettingsKeys.METRIC_NETWORK, false).collectAsState(initial = false)
    val metricUptime by settings.get(SettingsKeys.METRIC_UPTIME, false).collectAsState(initial = false)

    val previewOptions = HudDisplayOptions(
        scale = scale, opacity = opacity, compact = compactMode,
        showGpu = metricGpu, showGpuMem = metricMem,
        showCpu = metricCpu, showCpuClock = metricCpuClock,
        showRam = metricRam, showFrame = metricFps, showApiLabel = showApiLabel,
        showBattery = metricBattery, showNetwork = metricNetwork, showUptime = metricUptime,
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text("Performance HUD") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(scroll)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // --- Live preview: exactly what the floating HUD will look like ---
            Column {
                Text("Preview", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(Color(0xFF101010))
                        .padding(16.dp)
                ) {
                    OverlayHud(snapshot = PreviewSnapshot, options = previewOptions, sessionUptimeSec = 125)
                }
            }

            // --- Start / Stop ---
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onStartOverlay, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start HUD")
                }
                OutlinedButton(onClick = onStopOverlay, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop")
                }
            }

            SectionCard(title = "Access mode", icon = Icons.Filled.Security) {
                listOf(
                    "AUTO" to "Auto (Root > Shizuku > Non-root)",
                    "ROOT" to "Root",
                    "SHIZUKU" to "Shizuku (no root needed)",
                    "NON_ROOT" to "Non-root only",
                ).forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        RadioButton(selected = accessMode == value, onClick = {
                            scope.launch { settings.set(SettingsKeys.ACCESS_MODE, value) }
                        })
                        Text(label)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRequestRoot) { Text("Grant root") }
                    OutlinedButton(onClick = onRequestShizukuPermission) { Text("Grant Shizuku") }
                }
            }

            SectionCard(title = "Permissions", icon = Icons.Filled.VerifiedUser) {
                PermissionRow("Draw over other apps (required)", onRequestOverlayPermission)
                PermissionRow("Usage access (auto-show for games)", onRequestUsageAccess)
            }

            SectionCard(title = "Appearance", icon = Icons.Filled.Palette) {
                SwitchRow("Dynamic color (Android 12+)", dynamicColor && accentArgb == 0) {
                    scope.launch {
                        settings.set(SettingsKeys.DYNAMIC_COLOR, it)
                        if (it) settings.set(SettingsKeys.ACCENT_ARGB, 0)
                    }
                }
                SwitchRow("Dark theme", darkTheme) {
                    scope.launch { settings.set(SettingsKeys.DARK_THEME, it) }
                }
                SwitchRow("Compact HUD (values only, no row labels)", compactMode) {
                    scope.launch { settings.set(SettingsKeys.COMPACT_MODE, it) }
                }

                Spacer(Modifier.height(4.dp))
                Text("Accent color", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val presets = listOf(
                        0 to Color(0xFF9E9E9E),            // "use system/dynamic" swatch
                        0xFF2ECC71.toInt() to SeedGreen,
                        0xFF3B9EFF.toInt() to SeedBlue,
                        0xFFFF9E2C.toInt() to SeedOrange,
                        0xFFF6B8CC.toInt() to SeedPink,
                    )
                    presets.forEach { (argb, swatch) ->
                        val selected = argb == accentArgb
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(
                                    width = if (selected) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                )
                                .clickable { scope.launch { settings.set(SettingsKeys.ACCENT_ARGB, argb) } }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("HUD scale: ${"%.2f".format(scale)}x", style = MaterialTheme.typography.bodyMedium)
                Slider(value = scale, onValueChange = { v -> scope.launch { settings.set(SettingsKeys.OVERLAY_SCALE, v) } }, valueRange = 0.7f..1.6f)
                Text("HUD opacity: ${(opacity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(value = opacity, onValueChange = { v -> scope.launch { settings.set(SettingsKeys.OVERLAY_OPACITY, v) } }, valueRange = 0.3f..1f)
            }

            SectionCard(title = "Metrics shown", icon = Icons.Filled.Speed) {
                SwitchRow("GPU usage / clock", metricGpu) { scope.launch { settings.set(SettingsKeys.METRIC_GPU, it) } }
                SwitchRow("GPU memory (VRAM)", metricMem) { scope.launch { settings.set(SettingsKeys.METRIC_MEM_GPU, it) } }
                SwitchRow("CPU usage / temp", metricCpu) { scope.launch { settings.set(SettingsKeys.METRIC_CPU, it) } }
                SwitchRow("CPU clock speed", metricCpuClock) { scope.launch { settings.set(SettingsKeys.METRIC_CPU_CLOCK, it) } }
                SwitchRow("RAM used / total (physical)", metricRam) { scope.launch { settings.set(SettingsKeys.METRIC_RAM, it) } }
                SwitchRow("FPS / frame time", metricFps) { scope.launch { settings.set(SettingsKeys.METRIC_FPS, it) } }
                SwitchRow("Show render API as row label (Adreno/Mali/Vulkan...)", showApiLabel) { scope.launch { settings.set(SettingsKeys.SHOW_API_LABEL, it) } }
                SwitchRow("Battery level / temp", metricBattery) { scope.launch { settings.set(SettingsKeys.METRIC_BATTERY, it) } }
                SwitchRow("Network speed (down/up)", metricNetwork) { scope.launch { settings.set(SettingsKeys.METRIC_NETWORK, it) } }
                SwitchRow("Session uptime timer", metricUptime) { scope.launch { settings.set(SettingsKeys.METRIC_UPTIME, it) } }
            }

            SectionCard(title = "Behaviour", icon = Icons.Filled.Tune) {
                Text("Refresh rate: ${refreshMs}ms", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = refreshMs.toFloat(),
                    onValueChange = { v -> scope.launch { settings.set(SettingsKeys.REFRESH_MS, v.toInt()) } },
                    valueRange = 250f..2000f, steps = 6
                )
                SwitchRow("Auto-show when a game launches", autoShow) { scope.launch { settings.set(SettingsKeys.AUTO_SHOW_FOR_GAMES, it) } }
                SwitchRow("Log session to CSV (Android/data/.../hud_logs)", logging) { scope.launch { settings.set(SettingsKeys.LOGGING_ENABLED, it) } }
            }

            Text(
                "Tip: add the Quick Settings tile (swipe down twice, edit tiles) to toggle the HUD without opening the app.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text("Grant") }
    }
}
