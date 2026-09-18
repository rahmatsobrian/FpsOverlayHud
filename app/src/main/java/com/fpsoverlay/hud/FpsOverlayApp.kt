package com.fpsoverlay.hud

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class FpsOverlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                OverlayService.CHANNEL_ID,
                "Performance HUD",
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Keeps the floating FPS/CPU/GPU HUD running" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }
}
