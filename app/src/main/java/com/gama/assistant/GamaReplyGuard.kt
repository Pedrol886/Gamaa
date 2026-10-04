package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

/** Final user-facing guard. Internal implementation details must never be spoken. */
object GamaReplyGuard {
    fun sanitize(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return "Não consegui concluir essa resposta agora. Pode repetir de outro jeito."
        val n = normalize(text)
        val cacheAdvice = "cache" in n && listOf("limpe", "limpar", "apague", "apagar", "dados", "reinstal").any { it in n }
        val internalFailure = listOf(
            "brain ipc", "isolated process", "isolated-process", "modelo local reinici",
            "processo isolado", ".task", "stack trace", "pid ", "llminference",
            "mediapipe", "native crash", "falha de inferencia local"
        ).any { it in n }
        return when {
            cacheAdvice || internalFailure -> "Não consegui concluir isso agora. Posso tentar de outra forma."
            else -> text.replace(Regex("(?i)\\bGama\\b"), "Gama")
        }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
