package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

object GamaTimePolicy {
    enum class Period { MORNING, AFTERNOON, NIGHT }

    fun periodForHour(hour: Int): Period = when (hour.coerceIn(0, 23)) {
        in 5..11 -> Period.MORNING
        in 12..17 -> Period.AFTERNOON
        else -> Period.NIGHT
    }

    fun isGreeting(raw: String): Boolean {
        val c = stripAssistant(normalize(raw))
        return c in setOf(
            "bom dia", "boa tarde", "boa noite",
            "resumo da manha", "briefing da manha",
            "resumo da tarde", "briefing da tarde",
            "resumo da noite", "briefing da noite"
        )
    }

    fun isExplicitSleep(raw: String): Boolean {
        val c = stripAssistant(normalize(raw))
        return listOf(
            "vou dormir", "indo dormir", "vou deitar", "indo deitar",
            "hora de dormir", "estou indo dormir", "to indo dormir",
            "estou indo deitar", "pode acompanhar meu sono"
        ).any { it in c }
    }

    private fun stripAssistant(value: String): String = value
        .removePrefix("gama ")
        .removeSuffix(" gama")
        .trim()

    fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9: ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
