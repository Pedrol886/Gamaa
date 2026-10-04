package com.gama.assistant

import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

/**
 * Repairs a very small set of safety-relevant device commands after speech recognition.
 * It intentionally does not perform general fuzzy command matching, which would make
 * background speech more likely to trigger an action. The caller still requires an
 * active Gama conversation before execution.
 */
object GamaCriticalCommandPolicy {
    fun isLockScreen(raw: String): Boolean {
        val c = normalize(raw)
        if (c.isBlank()) return false
        if ("desbloque" in c || "destrave" in c || "destranc" in c) return false

        val words = c.split(' ').filter { it.isNotBlank() }
        val hasTarget = words.any { word ->
            word in setOf("tela", "celular", "telefone", "aparelho") ||
                distanceAtMost(word, "tela", 1) ||
                distanceAtMost(word, "celular", 2)
        }
        if (!hasTarget) return false

        return words.any { word ->
            word.startsWith("bloque") ||
                word.startsWith("bloqui") ||
                word.startsWith("bloke") ||
                word.startsWith("trave") ||
                word.startsWith("trava") ||
                word.startsWith("tranc") ||
                distanceAtMost(word, "bloqueie", 2) ||
                distanceAtMost(word, "bloquear", 2)
        }
    }

    fun recover(raw: String): String = if (isLockScreen(raw)) "bloqueie a tela" else raw.trim()

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun distanceAtMost(a: String, b: String, max: Int): Boolean {
        if (kotlin.math.abs(a.length - b.length) > max) return false
        if (a == b) return true
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            var rowMin = current[0]
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = min(
                    min(current[j] + 1, previous[j + 1] + 1),
                    previous[j] + cost,
                )
                rowMin = min(rowMin, current[j + 1])
            }
            if (rowMin > max) return false
            previous = current
        }
        return previous[b.length] <= max
    }
}
