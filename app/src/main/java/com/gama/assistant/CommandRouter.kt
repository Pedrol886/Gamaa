package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

/** Roteia ações locais antes da IA e tolera pequenas variações da transcrição do Vosk. */
object CommandRouter {
    private val appAliases = linkedMapOf(
        "whatsapp" to setOf(
            "whatsapp", "whats app", "whatsap", "watsapp", "wats app", "watsap",
            "uatsapp", "uats app", "uatsap", "whatsup", "what s up", "zap", "zap zap",
            "zapzap", "zape", "wats", "whats"
        ),
        "instagram" to setOf("instagram", "insta", "instagran", "istagram"),
        "spotify" to setOf("spotify", "spotfy", "sportfy", "sportify", "espotify", "spotifi"),
        "youtube" to setOf("youtube", "iutube", "yutube", "yutub"),
        "tiktok" to setOf("tiktok", "tictoque", "tictok", "tik tok"),
        "play store" to setOf("play store", "playstore", "loja play", "google play")
    )

    // Erros observados do Vosk que só devem virar WhatsApp quando a intenção de abrir um app já está clara.
    private val whatsappOpenOnlyAliases = setOf(
        "apesar", "a pesar", "o pesar", "uatsape", "uatsapi", "watsape", "watsapi"
    )

    fun normalize(raw: String): String {
        var n = Normalizer.normalize(raw.lowercase(Locale("pt", "BR")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        n = n.replace(Regex("\\b(?:zap\\s+zap|zapzap|zape|zap)\\b"), "whatsapp")
        n = n.replace(
            Regex("\\b(?:whats\\s+app|what\\s+s\\s+up|wats\\s+app|uats\\s+app|whatsap|watsapp|watsap|uatsapp|uatsap)\\b"),
            "whatsapp"
        )
        return n.replace(Regex("\\s+"), " ").trim()
    }

    private fun isQuestionAboutApp(c: String): Boolean {
        val starts = listOf(
            "o que e ", "o que eh ", "que e ", "como funciona ", "como usar ", "como abrir ",
            "por que ", "porque ", "qual ", "quem ", "quando ", "onde ", "me explique ",
            "explique ", "pesquise ", "procure ", "quero saber "
        )
        return starts.any { c.startsWith(it) }
    }

    private fun looksLikeMessaging(c: String): Boolean =
        listOf("mensag", "mande ", "manda ", "envie ", "enviar ", "escreva ", "responda ")
            .any { c.contains(it) }

    private fun openCue(c: String): Boolean {
        val cues = listOf(
            "abra ", "abre ", "abrir ", "abri ", "pode abrir ", "quero abrir ",
            "entre no ", "entre na ", "entra no ", "entra na ", "entrar no ", "entrar na ",
            "va para ", "vai para ", "ir para ", "va no ", "vai no ", "ir no ",
            "va na ", "vai na ", "ir na ", "acesse ", "acessar ", "inicie ", "iniciar ",
            "execute ", "executar ", "rode ", "rodar ", "me leve para ", "me leva para ",
            "coloque ", "coloca ", "mostre ", "mostra "
        )
        return cues.any { c.startsWith(it) || c.contains(" $it") }
    }

    private fun aliasPresent(c: String, aliases: Set<String>): Boolean {
        val padded = " $c "
        val compact = c.replace(" ", "")
        return aliases.any { alias ->
            val a = normalize(alias)
            val ac = a.replace(" ", "")
            padded.contains(" $a ") || (ac.length >= 4 && compact.contains(ac))
        }
    }

    private fun openOnlyWhatsAppRecovery(c: String): Boolean {
        if (!openCue(c)) return false
        val padded = " $c "
        return whatsappOpenOnlyAliases.any { alias ->
            val a = normalize(alias)
            padded.contains(" $a ") || c.endsWith(" $a")
        }
    }

    /** Nome canônico quando a intenção de abrir um app é clara. */
    fun appToOpen(raw: String): String? {
        val c = normalize(raw)
        if (c.isBlank() || looksLikeMessaging(c) || isQuestionAboutApp(c)) return null
        if (c.startsWith("nao ") || c.contains(" nao quero ") || c.contains(" nao abra ") || c.contains(" nao abre ")) return null
        if (openOnlyWhatsAppRecovery(c)) return "whatsapp"
        val entry = appAliases.entries.firstOrNull { aliasPresent(c, it.value) } ?: return null
        val directForms = entry.value.map(::normalize)
        val directCompact = directForms.map { it.replace(" ", "") }.toSet()
        val direct = c in directForms || c.replace(" ", "") in directCompact
        return if (direct || openCue(c)) entry.key else null
    }

    /** Ainda é uma ação local mesmo se o nome do aplicativo não foi entendido. */
    fun looksLikeOpenRequest(raw: String): Boolean {
        val c = normalize(raw)
        if (c.isBlank() || isQuestionAboutApp(c) || looksLikeMessaging(c)) return false
        return openCue(c) || appToOpen(c) != null
    }
}
