package com.gama.assistant

import android.content.Context
import android.content.Intent

/** User-controlled listening; never restarts the microphone from a boot receiver. */
object BackgroundMode {
    const val ENABLE = "com.gama.assistant.ENABLE_BACKGROUND"
    private fun prefs(context: Context) = context.getSharedPreferences("gama_background", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    /** Default economy mode avoids a permanent CPU wake lock. Opt in if an OEM suspends hotword listening on a dark screen. */
    fun keepCpuAwake(context: Context) = prefs(context).getBoolean("keep_cpu_awake", true)
    fun setKeepCpuAwake(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("keep_cpu_awake", enabled).apply()
    }
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("enabled", enabled).apply() }
    fun overlayExplained(context: Context) = prefs(context).getBoolean("overlay_explained", false)
    fun markOverlayExplained(context: Context) { prefs(context).edit().putBoolean("overlay_explained", true).apply() }
    fun pause(context: Context) {
        setEnabled(context, false)
        context.stopService(Intent(context, GamaService::class.java))
    }
}

/** Available official routes for a command initiated by the user. */
object BackgroundLaunchPolicy {
    enum class Route { ACTIVITY, ASSISTANT, NOTIFICATION }
    fun route(foreground: Boolean, overlayGranted: Boolean, assistantReady: Boolean): Route = when {
        foreground || overlayGranted -> Route.ACTIVITY
        assistantReady -> Route.ASSISTANT
        else -> Route.NOTIFICATION
    }
}
