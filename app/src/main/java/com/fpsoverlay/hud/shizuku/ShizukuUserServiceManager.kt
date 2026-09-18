package com.fpsoverlay.hud.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.fpsoverlay.hud.IUserService
import kotlinx.coroutines.delay

/**
 * Binds once, then reuses the same remote IUserService for every sample
 * so we don't re-bind (slow, and briefly re-prompts) on every tick.
 */
object ShizukuUserServiceManager {

    private const val PACKAGE_NAME = "com.fpsoverlay.hud"

    private var service: IUserService? = null
    private var binding = false

    private val args by lazy {
        rikka.shizuku.Shizuku.UserServiceArgs(ComponentName(PACKAGE_NAME, UserService::class.java.name))
            .daemon(false)
            .processNameSuffix("hud_shell")
            .debuggable(false)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder?.pingBinder() == true) IUserService.Stub.asInterface(binder) else null
            binding = false
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    suspend fun exec(cmd: String): String {
        ensureBound()
        return try {
            service?.execCommand(cmd) ?: ""
        } catch (e: Exception) {
            // Remote process died mid-call; drop the stale binder so the
            // next sample re-binds instead of failing forever.
            service = null
            ""
        }
    }

    private suspend fun ensureBound() {
        if (service != null) return
        if (!binding) {
            binding = true
            runCatching { rikka.shizuku.Shizuku.bindUserService(args, connection) }
        }
        var waitedMs = 0
        while (service == null && waitedMs < 1500) {
            delay(50)
            waitedMs += 50
        }
    }

    fun unbind() {
        runCatching { rikka.shizuku.Shizuku.unbindUserService(args, connection, true) }
        service = null
    }
}
