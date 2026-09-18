package com.fpsoverlay.hud.stats

import android.view.Choreographer

/**
 * Lightweight Choreographer-based FPS/frame-time/jank counter.
 * Jank is counted whenever a frame takes > 1.5x the target frame interval
 * (16.6ms @60Hz), a common simple threshold.
 */
class FpsTracker(private val targetHz: Int = 60) {

    var currentFps: Int = 0
        private set
    var currentFrameTimeMs: Float = 0f
        private set
    var jankCount: Int = 0
        private set

    private var frameCount = 0
    private var windowStartNs = 0L
    private var lastFrameNs = 0L
    private var running = false

    private val targetFrameMs = 1000.0 / targetHz

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNs != 0L) {
                val deltaMs = (frameTimeNanos - lastFrameNs) / 1_000_000f
                currentFrameTimeMs = deltaMs
                if (deltaMs > targetFrameMs * 1.5) jankCount++
            }
            lastFrameNs = frameTimeNanos
            frameCount++
            if (windowStartNs == 0L) windowStartNs = frameTimeNanos
            val elapsedMs = (frameTimeNanos - windowStartNs) / 1_000_000
            if (elapsedMs >= 1000) {
                currentFps = frameCount
                frameCount = 0
                windowStartNs = frameTimeNanos
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        frameCount = 0; windowStartNs = 0L; lastFrameNs = 0L; jankCount = 0
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }

    fun resetJank() { jankCount = 0 }
}
