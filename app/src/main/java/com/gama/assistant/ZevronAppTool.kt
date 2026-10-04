package com.gama.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import java.text.Normalizer
import java.util.Locale

object ZevronAppTool {

    data class Request(
        val target: String
    )

    private val openVerbs =
        setOf(
            "abra",
            "abre",
            "abrir",
            "inicie",
            "inicia",
            "iniciar",
            "execute",
            "executa"
        )

    private val whatsAppAliases =
        setOf(
            "whatsapp",
            "zap",
            "zap zap",
            "watsap",
            "watsape",
            "uatsap",
            "uatsape",
            "whats",
            "whats app"
        )

    fun parse(
        raw: String
    ): Request? {

        var command =
            normalize(raw)

        command =
            command
                .removePrefix(
                    "gama "
                )
                .trim()

        val verb =
            command
                .substringBefore(" ")

        if (
            verb !in openVerbs
        ) {
            return null
        }

        var target =
            command
                .substringAfter(
                    " ",
                    ""
                )
                .trim()

        target =
            target
                .removePrefix("o ")
                .removePrefix("a ")
                .removePrefix(
                    "app "
                )
                .removePrefix(
                    "aplicativo "
                )
                .trim()

        if (
            target.isBlank()
        ) {
            return null
        }

        return Request(target)
    }

    fun handle(
        context: Context,
        raw: String
    ): String? {

        GamaAppRegistry.resolveOpenRequest(raw)?.let { app ->
            val result = GamaAppRegistry.launch(context, app)
            return if (result.started) {
                ZevronConversationEngine.rememberSubject(context, app.label)
                "Abrindo ${app.label}."
            } else {
                "Não encontrei o ${app.label} instalado."
            }
        }

        val request =
            parse(raw)
                ?: return null

        val target =
            normalize(
                request.target
            )

        if (
            target in
            whatsAppAliases
        ) {

            return openKnown(
                context,
                listOf(
                    "com.whatsapp",
                    "com.whatsapp.w4b"
                ),
                "WhatsApp"
            )
        }

        val pm =
            context.packageManager

        val query =
            Intent(
                Intent.ACTION_MAIN
            )
                .addCategory(
                    Intent.CATEGORY_LAUNCHER
                )

        @Suppress("DEPRECATION")
        val activities =
            runCatching {
                pm.queryIntentActivities(
                    query,
                    0
                )
            }
                .getOrDefault(
                    emptyList()
                )

        val match =
            bestMatch(
                pm,
                activities,
                target
            )
                ?: return null

        val activity =
            match.activityInfo
                ?: return null

        val packageName =
            activity.packageName

        val launch =
            pm.getLaunchIntentForPackage(
                packageName
            )
                ?: Intent(
                    Intent.ACTION_MAIN
                )
                    .addCategory(
                        Intent.CATEGORY_LAUNCHER
                    )
                    .setClassName(
                        packageName,
                        activity.name
                    )

        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
        )

        val opened =
            runCatching {
                context.startActivity(
                    launch
                )
            }
                .isSuccess

        if (!opened) {
            return null
        }

        ZevronConversationEngine
            .rememberSubject(
                context,
                request.target
            )

        val label =
            runCatching {
                match.loadLabel(pm)
                    .toString()
            }
                .getOrDefault(
                    request.target
                )

        return "Abrindo $label."
    }

    private fun openKnown(
        context: Context,
        packages: List<String>,
        label: String
    ): String {

        val pm =
            context.packageManager

        for (
            packageName in packages
        ) {

            val intent =
                pm.getLaunchIntentForPackage(
                    packageName
                )
                    ?: continue

            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

            if (
                runCatching {
                    context.startActivity(
                        intent
                    )
                }.isSuccess
            ) {

                ZevronConversationEngine
                    .rememberSubject(
                        context,
                        label
                    )

                return "Abrindo $label."
            }
        }

        return "Não encontrei o $label instalado."
    }

    private fun bestMatch(
        pm: PackageManager,
        items: List<ResolveInfo>,
        target: String
    ): ResolveInfo? {

        val targetCompact =
            target.replace(
                " ",
                ""
            )

        return items
            .mapNotNull {

                val info =
                    it.activityInfo
                        ?: return@mapNotNull null

                val label =
                    normalize(
                        runCatching {
                            it.loadLabel(pm)
                                .toString()
                        }
                            .getOrDefault(
                                ""
                            )
                    )

                val packageName =
                    normalize(
                        info.packageName
                            .orEmpty()
                    )

                val score =
                    when {

                        label ==
                        target ->
                            100

                        packageName ==
                        target ->
                            95

                        label.startsWith(
                            target
                        ) ->
                            85

                        target.startsWith(
                            label
                        ) &&
                        label.length >= 3 ->
                            80

                        label.contains(
                            target
                        ) ->
                            70

                        packageName.contains(
                            targetCompact
                        ) ->
                            60

                        else ->
                            0
                    }

                if (
                    score == 0
                ) {
                    null
                } else {
                    score to it
                }
            }
            .maxByOrNull {
                it.first
            }
            ?.second
    }

    internal fun normalize(
        value: String
    ): String {

        return Normalizer.normalize(
            value.lowercase(
                Locale.ROOT
            ),
            Normalizer.Form.NFD
        )
            .replace(
                Regex("\\p{M}+"),
                ""
            )
            .replace(
                Regex(
                    "[^a-z0-9 ]+"
                ),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }
}
