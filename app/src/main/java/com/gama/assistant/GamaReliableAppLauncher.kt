package com.gama.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager

/**
 * Multi-route application launcher for modern Android/Samsung devices.
 *
 * Android package visibility and background-activity rules are not identical on
 * every device. Gama therefore tries independent official routes instead of
 * assuming getLaunchIntentForPackage() is enough everywhere.
 */
object GamaReliableAppLauncher {

    enum class Route {
        INTENT_SENDER,
        PACKAGE_LAUNCH_INTENT,
        EXPLICIT_LAUNCHER_ACTIVITY,
        APP_DEEP_LINK,
    }

    data class Result(
        val started: Boolean,
        val route: Route? = null,
        val packageName: String? = null,
    )

    /**
     * `attempt` selects the route family. This lets the verifier retry using a
     * different mechanism when the screen did not actually change.
     */
    fun launch(
        context: Context,
        app: GamaAppRegistry.AppSpec,
        attempt: Int = 0,
    ): Result {
        val routes = listOf(
            Route.INTENT_SENDER,
            Route.PACKAGE_LAUNCH_INTENT,
            Route.EXPLICIT_LAUNCHER_ACTIVITY,
            Route.APP_DEEP_LINK,
        )
        val start = attempt.coerceAtLeast(0) % routes.size

        for (offset in routes.indices) {
            val route = routes[(start + offset) % routes.size]
            for (packageName in app.packages) {
                val opened = when (route) {
                    Route.INTENT_SENDER -> openWithIntentSender(context, packageName)
                    Route.PACKAGE_LAUNCH_INTENT -> openWithPackageLaunchIntent(context, packageName)
                    Route.EXPLICIT_LAUNCHER_ACTIVITY -> openWithExplicitLauncher(context, packageName)
                    Route.APP_DEEP_LINK -> openWithDeepLink(context, app.id, packageName)
                }
                if (opened) return Result(true, route, packageName)
            }
        }
        return Result(false)
    }

    private fun openWithIntentSender(context: Context, packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) if (GamaLaunchFallback.launch(context, packageName)) return true
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            val sender = context.packageManager.getLaunchIntentSenderForPackage(packageName)
            sender.sendIntent(context, 0, null, null, null)
            true
        }.getOrDefault(false)
    }

    private fun openWithPackageLaunchIntent(context: Context, packageName: String): Boolean {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull() ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return start(context, intent)
    }

    private fun openWithExplicitLauncher(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)

        @Suppress("DEPRECATION")
        val activities = runCatching {
            pm.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())

        for (match in activities) {
            val info = match.activityInfo ?: continue
            val explicit = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(info.packageName, info.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            if (start(context, explicit)) return true
        }
        return false
    }

    private fun openWithDeepLink(context: Context, appId: String, packageName: String): Boolean {
        val uri = when (appId) {
            // Opens WhatsApp itself without selecting a contact or sending data.
            "whatsapp" -> Uri.parse("whatsapp://send")
            "spotify" -> Uri.parse("spotify://")
            "instagram" -> Uri.parse("instagram://")
            "youtube" -> Uri.parse("vnd.youtube://")
            "tiktok" -> Uri.parse("snssdk1233://")
            else -> return false
        }
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return start(context, intent)
    }

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
}
