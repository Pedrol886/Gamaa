package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

object GamaConversationLanguage {
    enum class Confirmation { YES, NO, UNKNOWN }

    fun normalize(value: String): String =
        Normalizer.normalize(
            value.lowercase(Locale.ROOT),
            Normalizer.Form.NFD,
        )
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9:/?&.=_%+\\- ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun confirmation(raw: String): Confirmation {
        val c = normalize(raw)
            .removePrefix("gama ")
            .trim()

        if (c in setOf(
                "sim", "pode", "pode sim", "pode fazer", "faca", "faz",
                "execute", "executa", "manda ver", "quero", "isso", "isso mesmo",
                "confirmo", "confirmado", "ok", "okay", "claro",
            )
        ) return Confirmation.YES

        if (c in setOf(
                "nao", "nao quero", "cancela", "cancelar", "deixa", "deixa pra la",
                "esquece", "melhor nao", "nao faca", "nao faz", "pare",
            )
        ) return Confirmation.NO

        return Confirmation.UNKNOWN
    }

    fun looksLikeContextQuestion(raw: String): Boolean {
        val c = normalize(raw)
        return listOf(
            "o que a gente estava fazendo",
            "o que estavamos fazendo",
            "onde paramos",
            "qual era a tarefa",
            "o que voce ia fazer",
            "o que voce sugeriu",
            "qual foi sua ideia",
        ).any { it in c }
    }
}
