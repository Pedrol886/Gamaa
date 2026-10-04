package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

/**
 * Small conversational repair layer for natural self-corrections.
 * Example: "diz que eu não tô, aliás eu tô fora" -> "eu tô fora".
 * It never invents a recipient or action; it only keeps the user's latest clause.
 */
object GamaConversationRepair {
    private val correction = Regex(
        "(?i)\\b(?:ali[aá]s|na\\s+verdade|quer\\s+dizer|melhor\\s+dizendo|corrigindo)\\b[,:;.!?\\s-]*(.+)$"
    )

    fun latestClause(raw: String): String {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return clean
        val match = correction.find(clean) ?: return clean
        val replacement = match.groupValues.getOrNull(1).orEmpty().trim().trim(',', ';', '.', '!', '?', ' ')
        return replacement.takeIf { it.length >= 2 } ?: clean
    }

    fun isResumeRequest(raw: String): Boolean {
        val n = normalize(raw)
        return n in setOf(
            "continua", "continue", "pode continuar", "retome", "retoma",
            "continua de onde parou", "continue de onde parou", "retome de onde parou",
            "volta pra tarefa", "volte para a tarefa", "segue", "pode seguir"
        )
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
