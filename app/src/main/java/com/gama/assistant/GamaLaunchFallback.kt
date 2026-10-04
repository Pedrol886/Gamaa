package com.gama.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process

/**
 * Última rota para abertura de aplicativos.
 *
 * O GamaReliableAppLauncher continua tentando:
 * 1. IntentSender
 * 2. LaunchIntent normal
 * 3. Activity explícita
 * 4. deep-link
 *
 * Essa classe adiciona:
 * 5. LauncherApps
 * 6. ACTION_MAIN + CATEGORY_LAUNCHER
 * 7. deep-link final para WhatsApp
 */
object GamaLaunchFallback {

    fun launch(
        context: Context,
        packageName: String
    ): Boolean {

        // Rota LauncherApps.
        runCatching {
            val launcherApps =
                context.getSystemService(LauncherApps::class.java)

            val user = Process.myUserHandle()

            val activities =
                launcherApps?.getActivityList(
                    packageName,
                    user
                ).orEmpty()

            val activity =
                activities.firstOrNull()

            if (
                launcherApps != null &&
                activity != null
            ) {
                launcherApps.startMainActivity(
                    activity.componentName,
                    user,
                    null,
                    null
                )

                return true
            }
        }

        // Rota launcher genérica.
        runCatching {
            val intent =
                Intent(Intent.ACTION_MAIN)
                    .addCategory(
                        Intent.CATEGORY_LAUNCHER
                    )
                    .setPackage(packageName)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )

            val resolved =
                context.packageManager
                    .resolveActivity(intent, 0)

            if (resolved != null) {
                context.startActivity(intent)
                return true
            }
        }

        // Última tentativa para WhatsApp.
        if (
            packageName == "com.whatsapp" ||
            packageName == "com.whatsapp.w4b"
        ) {
            runCatching {
                val whatsapp =
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("whatsapp://send")
                    ).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )

                context.startActivity(whatsapp)

                return true
            }
        }

        return false
    }
}
