package com.gama.assistant

import android.content.Context
import android.content.Intent
import java.text.Normalizer
import java.util.Locale

object GamaAppRegistry {
    data class AppSpec(
        val id: String,
        val label: String,
        val packages: List<String>,
        val aliases: Set<String>,
    )

    data class LaunchResult(
        val app: AppSpec,
        val started: Boolean,
        val route: GamaReliableAppLauncher.Route? = null,
        val packageName: String? = null,
    )

    private val apps = listOf(
        AppSpec(
            id = "whatsapp",
            label = "WhatsApp",
            packages = listOf("com.whatsapp", "com.whatsapp.w4b"),
            aliases = setOf(
                "whatsapp", "whats app", "whatsap", "watsapp", "watsap",
                "watsape", "watsapi", "uatsapp", "uatsap", "uatsape",
                "whatsup", "what s up", "zap", "zap zap", "zapzap",
                "zape", "whats", "wats", "apesar", "a pesar", "o pesar"
            )
        ),
        AppSpec(
            id = "spotify",
            label = "Spotify",
            packages = listOf("com.spotify.music"),
            aliases = setOf("spotify", "spotfy", "sportfy", "sportify", "espotify", "spotifi")
        ),
        AppSpec(
            id = "instagram",
            label = "Instagram",
            packages = listOf("com.instagram.android"),
            aliases = setOf("instagram", "insta", "instagran", "istagram")
        ),
        AppSpec(
            id = "youtube",
            label = "YouTube",
            packages = listOf("com.google.android.youtube"),
            aliases = setOf("youtube", "iutube", "yutube", "yutub")
        ),
        AppSpec(
            id = "tiktok",
            label = "TikTok",
            packages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            aliases = setOf("tiktok", "tik tok", "tictok", "tictoque")
        ),
        AppSpec(
            id = "play_store",
            label = "Play Store",
            packages = listOf("com.android.vending"),
            aliases = setOf("play store", "playstore", "google play", "loja play")
        )
    )

    private val openCues = listOf(
        "abra ", "abre ", "abrir ", "abri ", "pode abrir ", "quero abrir ",
        "entre no ", "entre na ", "entra no ", "entra na ", "entrar no ", "entrar na ",
        "va para ", "vai para ", "ir para ", "va no ", "vai no ", "ir no ",
        "va na ", "vai na ", "ir na ", "acesse ", "acessar ", "inicie ", "iniciar ",
        "execute ", "executar ", "rode ", "rodar ", "me leve para ", "me leva para "
    )

    fun resolveOpenRequest(raw: String): AppSpec? {
        val c = normalize(ZevronConversationEngine.stripWakeWord(raw))
        if (c.isBlank()) return null

        if (c.startsWith("nao ") || c.contains(" nao abra ") || c.contains(" nao abre ")) {
            return null
        }

        if (WhatsAppCommandParser.looksLikeSendCommand(c) ||
            listOf("mensagem", "mande ", "envie ", "responda ").any { c.contains(it) }) {
            return null
        }

        val questionStarts = listOf(
            "o que e ", "como funciona ", "como usar ", "como abrir ",
            "por que ", "porque ", "qual ", "quem ", "quando ", "onde ",
            "me explique ", "explique ", "pesquise ", "procure "
        )
        if (questionStarts.any { c.startsWith(it) }) return null

        val direct = apps.firstOrNull { spec ->
            spec.aliases.any { normalize(it) == c }
        }
        if (direct != null) return direct

        val hasOpenCue = openCues.any { cue -> c.startsWith(cue) || c.contains(" $cue") }
        if (!hasOpenCue) return null

        val padded = " $c "
        val compact = c.replace(" ", "")

        return apps.firstOrNull { spec ->
            spec.aliases.any { alias ->
                val a = normalize(alias)
                val ac = a.replace(" ", "")
                padded.contains(" $a ") || (ac.length >= 4 && compact.contains(ac))
            }
        }
    }

    fun launch(context: Context, app: AppSpec, attempt: Int = 0): LaunchResult {
        val result = GamaReliableAppLauncher.launch(context, app, attempt)
        return LaunchResult(
            app = app,
            started = result.started,
            route = result.route,
            packageName = result.packageName,
        )
    }

    fun isExpectedPackage(app: AppSpec, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return packageName in app.packages
    }

    internal fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
