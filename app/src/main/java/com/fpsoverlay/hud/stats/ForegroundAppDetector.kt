package com.fpsoverlay.hud.stats

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/** Detects the current foreground app so Root/Shizuku modes can point
 *  `dumpsys gfxinfo` at the right package (the game, not our own HUD). */
class ForegroundAppDetector(private val context: Context) {

    fun hasUsageAccessPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // Cached across calls so the HUD keeps reporting the last known
    // foreground app between polls, instead of forgetting it the moment
    // the MOVE_TO_FOREGROUND event scrolls out of a short query window.
    private var lastKnownPackage: String? = null
    private var lastQueryEndMs: Long = 0L

    fun currentForegroundPackage(): String? {
        if (!hasUsageAccessPermission()) return null
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            // On the very first poll we haven't observed any events yet, so
            // look back a full hour to catch a game that was already open
            // *before* the overlay was turned on (the normal case: open the
            // game, then enable the HUD). After that, only scan the gap
            // since the last poll — a fixed trailing window (e.g. "last 10
            // seconds") would miss the switch-to-foreground event entirely
            // once more than that much time has passed since it happened,
            // which is why FPS could go permanently blank.
            val begin = if (lastQueryEndMs == 0L) end - 60 * 60 * 1000L else lastQueryEndMs
            val events = usm.queryEvents(begin, end)
            val event = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastKnownPackage = event.packageName
                }
            }
            lastQueryEndMs = end
            lastKnownPackage
        } catch (_: Exception) {
            lastKnownPackage
        }
    }
}
