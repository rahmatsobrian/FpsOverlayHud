package com.fpsoverlay.hud.shizuku

import com.fpsoverlay.hud.IUserService
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Instantiated by Shizuku itself inside its own host process (shell or
 * system UID depending on how the user activated Shizuku), NOT inside our
 * app's process. Because of that, Shizuku requires either a no-arg
 * constructor or a single-Context constructor - keep this class minimal and
 * dependency-free.
 */
class UserService : IUserService.Stub {
    constructor() : super()

    override fun execCommand(cmd: String): String = try {
        val process = ProcessBuilder("sh", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
        process.waitFor()
        output
    } catch (e: Exception) {
        ""
    }

    override fun destroy() {
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
