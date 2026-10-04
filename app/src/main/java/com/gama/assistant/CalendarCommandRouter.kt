package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

/** Pure parser for calendar creation requests. It never opens Android UI by itself. */
object CalendarCommandRouter {
    private val verbs = listOf(
        "marcar", "marque", "marca", "agendar", "agende",
        "adicionar", "adicione", "colocar", "coloque", "criar", "crie",
        "anotar", "anote", "registrar", "registre"
    )

    private val nouns = listOf(
        "agenda", "calendario", "compromisso", "evento"
    )

    private fun norm(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9: ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun looksLikeCreateRequest(raw: String): Boolean {
        val n = norm(raw)
        if (n.isBlank()) return false
        val hasVerb = verbs.any { n == it || n.startsWith("$it ") || n.contains(" $it ") }
        val hasNoun = nouns.any { n.contains(it) }
        return hasVerb && hasNoun
    }

    fun payload(raw: String): String {
        var n = norm(raw)
        verbs.sortedByDescending { it.length }.forEach { verb ->
            n = n.replace(Regex("(^| )${Regex.escape(verb)}(?= |$)"), " ")
        }
        listOf(
            "na minha agenda", "na agenda", "no meu calendario", "no calendario",
            "um compromisso", "uma coisa", "alguma coisa", "um evento", "algo",
            "para mim", "pra mim", "por favor"
        ).forEach { phrase -> n = n.replace(phrase, " ") }
        return n.replace(Regex("\\s+"), " ").trim().take(180)
    }

    fun canUseAsFollowUp(raw: String): Boolean {
        val n = norm(raw)
        if (n.length < 2) return false
        if (DeviceActionSafety.looksLikeUnresolvedAction(n)) return false
        if (n.endsWith("?") || n.startsWith("quem ") || n.startsWith("qual ") || n.startsWith("como ")) return false
        return true
    }
}
