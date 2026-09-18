package com.fpsoverlay.hud

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.fpsoverlay.hud.stats.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class OverlayService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "hud_channel"
        const val ACTION_STOP = "com.fpsoverlay.hud.action.STOP"
        const val ACTION_TOGGLE_LOCK = "com.fpsoverlay.hud.action.TOGGLE_LOCK"
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayOwner: OverlayLifecycleOwner
    private lateinit var composeView: ComposeView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var settings: SettingsRepository
    private lateinit var foregroundDetector: ForegroundAppDetector

    private var provider: StatsProvider? = null
    private var fpsTracker: FpsTracker? = null
    private var job: Job? = null
    private var locked = false

    private val fpsSamples = mutableListOf<Int>()
    private var jankTotal = 0
    private var cpuTempMax: Float? = null
    private var sessionStartMs = 0L
    private var logFile: File? = null

    private var snapshotState by mutableStateOf(StatsSnapshot())
    private var displayOptions by mutableStateOf(HudDisplayOptions())
    private var uptimeSecState by mutableStateOf(0L)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        settings = SettingsRepository(this)
        foregroundDetector = ForegroundAppDetector(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        setupOverlayWindow()
        startStatsLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE_LOCK -> toggleLock()
        }
        return START_STICKY
    }

    // ---------- Window setup ----------

    private fun setupOverlayWindow() {
        overlayOwner = OverlayLifecycleOwner().apply { performRestore() }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 120
        }

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(overlayOwner)
            setViewTreeViewModelStoreOwner(overlayOwner)
            setViewTreeSavedStateRegistryOwner(overlayOwner)
        }
        overlayOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        overlayOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        recomposeContent()
        attachDragHandling()

        windowManager.addView(composeView, params)
    }

    private fun recomposeContent() {
        composeView.setContent {
            com.fpsoverlay.hud.ui.theme.FpsOverlayTheme {
                OverlayHud(snapshot = snapshotState, options = displayOptions, sessionUptimeSec = uptimeSecState)
            }
        }
    }

    private fun attachDragHandling() {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        composeView.setOnTouchListener { _, event ->
            if (locked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(composeView, params)
                    lifecycleScope.launch {
                        settings.set(SettingsKeys.OVERLAY_X, params.x)
                        settings.set(SettingsKeys.OVERLAY_Y, params.y)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleLock() {
        locked = !locked
        params.flags = if (locked) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        windowManager.updateViewLayout(composeView, params)
    }

    // ---------- Stats loop ----------

    private fun startStatsLoop() {
        sessionStartMs = System.currentTimeMillis()
        fpsTracker = FpsTracker().also { it.start() }
        val detectedRenderApi = RenderApiDetector.detect(this)

        job = lifecycleScope.launch {
            val modePref = settings.get(SettingsKeys.ACCESS_MODE, "AUTO").first()
            provider = chooseProvider(modePref)

            val loggingOn = settings.get(SettingsKeys.LOGGING_ENABLED, false).first()
            if (loggingOn) logFile = createLogFile()

            while (isActive) {
                val refreshMs = settings.get(SettingsKeys.REFRESH_MS, 500).first()
                val enabledMetrics = enabledMetricsFromSettings()

                displayOptions = HudDisplayOptions(
                    scale = settings.get(SettingsKeys.OVERLAY_SCALE, 1f).first(),
                    opacity = settings.get(SettingsKeys.OVERLAY_OPACITY, 0.9f).first(),
                    compact = settings.get(SettingsKeys.COMPACT_MODE, false).first(),
                    showGpu = Metric.GPU in enabledMetrics,
                    showGpuMem = Metric.MEM_GPU in enabledMetrics,
                    showCpu = Metric.CPU in enabledMetrics,
                    showCpuClock = settings.get(SettingsKeys.METRIC_CPU_CLOCK, true).first(),
                    showRam = Metric.RAM in enabledMetrics,
                    showFrame = Metric.FPS_FRAMETIME in enabledMetrics,
                    showApiLabel = settings.get(SettingsKeys.SHOW_API_LABEL, true).first(),
                    showBattery = Metric.BATTERY in enabledMetrics,
                    showNetwork = Metric.NETWORK in enabledMetrics,
                    showUptime = settings.get(SettingsKeys.METRIC_UPTIME, false).first(),
                )
                uptimeSecState = (System.currentTimeMillis() - sessionStartMs) / 1000

                val rawSnap = provider?.sample(enabledMetrics) ?: StatsSnapshot()
                val snap = if (rawSnap.renderApi == null) rawSnap.copy(renderApi = detectedRenderApi) else rawSnap
                snapshotState = snap
                recomposeContent()

                snap.fps?.let { fpsSamples.add(it) }
                snap.jankCount?.let { jankTotal = it }
                snap.cpuTempC?.let { t -> cpuTempMax = maxOf(cpuTempMax ?: t, t) }
                logFile?.let { appendLogLine(it, snap) }

                delay(refreshMs.toLong())
            }
        }
    }

    private suspend fun chooseProvider(modePref: String): StatsProvider {
        val fps = fpsTracker!!
        val fgPkg = { foregroundDetector.currentForegroundPackage() }
        return when (modePref) {
            "ROOT" -> RootStatsProvider(fgPkg)
            "SHIZUKU" -> ShizukuStatsProvider(fgPkg)
            "NON_ROOT" -> NonRootStatsProvider(this, fps)
            else -> when {
                isRootAvailable() -> RootStatsProvider(fgPkg)
                ShizukuStatsProvider.isAvailable() -> ShizukuStatsProvider(fgPkg)
                else -> NonRootStatsProvider(this, fps)
            }
        }
    }

    private fun isRootAvailable(): Boolean = try {
        com.topjohnwu.superuser.Shell.getShell().isRoot
    } catch (_: Exception) { false }

    private suspend fun enabledMetricsFromSettings(): Set<Metric> {
        val m = mutableSetOf<Metric>()
        if (settings.get(SettingsKeys.METRIC_GPU, true).first()) m += Metric.GPU
        if (settings.get(SettingsKeys.METRIC_MEM_GPU, true).first()) m += Metric.MEM_GPU
        if (settings.get(SettingsKeys.METRIC_CPU, true).first()) m += Metric.CPU
        if (settings.get(SettingsKeys.METRIC_RAM, true).first()) m += Metric.RAM
        if (settings.get(SettingsKeys.METRIC_BATTERY, false).first()) m += Metric.BATTERY
        if (settings.get(SettingsKeys.METRIC_NETWORK, false).first()) m += Metric.NETWORK
        if (settings.get(SettingsKeys.METRIC_FPS, true).first()) m += Metric.FPS_FRAMETIME
        return m
    }

    // ---------- Logging ----------

    private fun createLogFile(): File {
        val dir = File(getExternalFilesDir(null), "hud_logs").apply { mkdirs() }
        val name = "session_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
        return File(dir, name).apply {
            writeText("timestamp,fps,frameTimeMs,cpuUsage,cpuTempC,gpuUsage,ramUsedMb,ramTotalMb,batteryLevel,netDownKbps,netUpKbps\n")
        }
    }

    private fun appendLogLine(file: File, s: StatsSnapshot) {
        try {
            file.appendText(
                "${System.currentTimeMillis()},${s.fps ?: ""},${s.frameTimeMs ?: ""},${s.cpuUsagePercent ?: ""}," +
                    "${s.cpuTempC ?: ""},${s.gpuUsagePercent ?: ""},${s.ramUsedMb ?: ""},${s.ramTotalMb ?: ""}," +
                    "${s.batteryLevelPercent ?: ""},${s.netDownKbps ?: ""},${s.netUpKbps ?: ""}\n"
            )
        } catch (_: Exception) { /* storage full / permission revoked mid-session: keep HUD alive */ }
    }

    fun buildSessionSummary(): SessionSummary {
        val durationSec = (System.currentTimeMillis() - sessionStartMs) / 1000
        return SessionSummary(
            durationSec = durationSec,
            fpsMin = fpsSamples.minOrNull() ?: 0,
            fpsAvg = if (fpsSamples.isNotEmpty()) fpsSamples.average().toInt() else 0,
            fpsMax = fpsSamples.maxOrNull() ?: 0,
            frameTimeAvgMs = snapshotState.frameTimeMs ?: 0f,
            jankTotal = jankTotal,
            cpuTempMaxC = cpuTempMax,
        )
    }

    // ---------- Foreground notification ----------

    private fun startForegroundNotification() {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val lockIntent = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_LOCK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_running))
            .setSmallIcon(R.drawable.ic_hud_tile)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_lock_toggle), lockIntent)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        isRunning = false
        job?.cancel()
        fpsTracker?.stop()
        runCatching { windowManager.removeView(composeView) }
        runCatching { com.fpsoverlay.hud.shizuku.ShizukuUserServiceManager.unbind() }
        overlayOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
