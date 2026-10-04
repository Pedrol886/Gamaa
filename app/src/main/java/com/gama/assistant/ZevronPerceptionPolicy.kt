package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

/** Decides when a deictic phrase explicitly refers to what is on screen. */
object ZevronPerceptionPolicy {
    fun wantsVisibleContext(raw: String): Boolean {
        val c = normalize(raw)
            .replaceFirst(Regex("^(?:gama|gama|gama|gama)\\s+"), "")
            .trim()
        return c in setOf(
            "o que e isso",
            "que e isso",
            "que coisa e essa",
            "me explica isso",
            "explique isso",
            "o que eu estou vendo",
            "o que estou vendo",
            "o que significa isso"
        )
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
