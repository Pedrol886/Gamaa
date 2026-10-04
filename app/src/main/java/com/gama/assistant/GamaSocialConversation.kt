package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

/** Fast local social lane so casual conversation does not depend on brain IPC. */
object GamaSocialConversation {
    fun reply(raw: String): String? {
        val c = normalize(raw).removePrefix("gama ").trim()
        if (c.isBlank()) return null

        if (listOf(
                "parabens", "muito bom", "bom trabalho", "mandou bem",
                "voce ta funcionando muito bem", "voce esta funcionando muito bem",
                "ta funcionando muito bem", "esta funcionando muito bem",
                "gostei de voce", "ficou muito bom",
            ).any { it in c }
        ) {
            return "Obrigado. Pode mandar a próxima."
        }

        if (c in setOf("valeu", "obrigado", "obrigada", "muito obrigado", "agradecido")) {
            return "Por nada. Estou por aqui."
        }

        if (listOf("tudo bem com voce", "como voce esta", "como voce ta").any { it in c }) {
            return "Tudo certo por aqui. Estou ouvindo."
        }

        return null
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
