package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

/** Conservative initiative policy: important or repeated contact attempts may interrupt; ordinary noise never does. */
object GamaProactivePolicy {
    private val urgentTerms = setOf(
        "urgente", "emergencia", "emergência", "importante", "agora", "me liga", "me ligue",
        "preciso falar", "responde", "responda", "hospital", "acidente"
    )

    fun messagePriority(text: String, recentSameSender: Int): GamaEventHub.Priority {
        val n = normalize(text)
        if (urgentTerms.any { normalize(it) in n }) return GamaEventHub.Priority.HIGH
        return GamaEventHub.Priority.NORMAL
    }

    fun shouldSpeakNow(
        priority: GamaEventHub.Priority,
        speaking: Boolean,
        busy: Boolean,
        millisSinceLastProactive: Long,
    ): Boolean = priority == GamaEventHub.Priority.HIGH && !speaking && !busy && millisSinceLastProactive >= 45_000L

    fun spokenSummary(source: String, text: String, locked: Boolean, repeated: Boolean = false): String {
        val who = source.trim().ifBlank { "um contato" }.take(80)
        if (repeated) {
            return if (locked) {
                "Senhor, $who está tentando falar com você novamente."
            } else {
                "Senhor, $who está tentando falar com você de novo."
            }
        }
        if (locked) return "Senhor, chegou uma mensagem importante de $who."
        val clean = text.replace(Regex("\\s+"), " ").trim().take(180)
        return if (clean.isBlank()) {
            "Senhor, chegou uma mensagem importante de $who."
        } else {
            "Senhor, $who mandou uma mensagem importante: $clean"
        }
    }

    internal fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
