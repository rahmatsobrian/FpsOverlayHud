package com.fpsoverlay.hud.stats

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Best-effort GPU/render-backend label for the API row (replaces the
 * hardcoded "GFX" placeholder), e.g. "Adreno Vulkan" or "Mali GLES3.2".
 * Uses only public PackageManager/ActivityManager info plus a reflective
 * read of a couple of long-standing greylisted system properties; every
 * step degrades gracefully if a device/ROM blocks it.
 */
object RenderApiDetector {

    private var cached: String? = null

    fun detect(context: Context): String {
        cached?.let { return it }
        val vendor = gpuVendorFromProps()
        val api = if (supportsVulkan(context)) "Vulkan" else glEsLabel(context)
        val label = if (vendor != null) "$vendor $api" else api
        cached = label
        return label
    }

    private fun supportsVulkan(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
    }

    private fun glEsLabel(context: Context): String = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val raw = am.deviceConfigurationInfo.glEsVersion ?: "?"
        "GLES$raw"
    } catch (_: Exception) {
        "GLES"
    }

    private fun gpuVendorFromProps(): String? {
        val keys = listOf("ro.hardware.egl", "ro.hardware.vulkan", "ro.board.platform", "ro.chipname")
        for (key in keys) {
            val value = getSystemProperty(key)?.lowercase() ?: continue
            when {
                value.contains("adreno") || value.contains("qcom") -> return "Adreno"
                value.contains("mali") -> return "Mali"
                value.contains("powervr") || value.contains("img") -> return "PowerVR"
                value.contains("xclipse") -> return "Xclipse"
                value.contains("apple") -> return "Apple"
            }
        }
        return null
    }

    /** android.os.SystemProperties#get is hidden API, historically greylisted;
     *  reflection here is best-effort and silently returns null if blocked. */
    private fun getSystemProperty(key: String): String? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("get", String::class.java)
        method.invoke(null, key) as? String
    } catch (_: Exception) {
        null
    }
}
