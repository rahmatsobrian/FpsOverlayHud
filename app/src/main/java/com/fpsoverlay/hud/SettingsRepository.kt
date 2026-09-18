package com.fpsoverlay.hud

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "hud_settings")

/** All user-adjustable HUD/app options, persisted across restarts. */
object SettingsKeys {
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val DARK_THEME = booleanPreferencesKey("dark_theme")
    val ACCESS_MODE = stringPreferencesKey("access_mode") // "ROOT" | "SHIZUKU" | "NON_ROOT" | "AUTO"
    val OVERLAY_X = intPreferencesKey("overlay_x")
    val OVERLAY_Y = intPreferencesKey("overlay_y")
    val OVERLAY_SCALE = floatPreferencesKey("overlay_scale") // 0.7 - 1.5
    val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity") // 0.3 - 1.0
    val OVERLAY_LOCKED = booleanPreferencesKey("overlay_locked")
    val REFRESH_MS = intPreferencesKey("refresh_ms") // 250 - 2000
    val METRIC_GPU = booleanPreferencesKey("metric_gpu")
    val METRIC_MEM_GPU = booleanPreferencesKey("metric_mem_gpu")
    val METRIC_CPU = booleanPreferencesKey("metric_cpu")
    val METRIC_CPU_CLOCK = booleanPreferencesKey("metric_cpu_clock")
    val METRIC_RAM = booleanPreferencesKey("metric_ram")
    val METRIC_BATTERY = booleanPreferencesKey("metric_battery")
    val METRIC_NETWORK = booleanPreferencesKey("metric_network")
    val METRIC_FPS = booleanPreferencesKey("metric_fps")
    val METRIC_UPTIME = booleanPreferencesKey("metric_uptime")
    val SHOW_API_LABEL = booleanPreferencesKey("show_api_label")
    val COMPACT_MODE = booleanPreferencesKey("compact_mode")
    val AUTO_SHOW_FOR_GAMES = booleanPreferencesKey("auto_show_for_games")
    val LOGGING_ENABLED = booleanPreferencesKey("logging_enabled")
    val TOUCH_FPS_COUNTER = booleanPreferencesKey("touch_fps_counter")
    val ACCENT_ARGB = intPreferencesKey("accent_argb")
}

class SettingsRepository(private val context: Context) {
    val flow: Flow<Preferences> = context.dataStore.data

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    fun <T> get(key: Preferences.Key<T>, default: T): Flow<T> =
        flow.map { it[key] ?: default }
}
