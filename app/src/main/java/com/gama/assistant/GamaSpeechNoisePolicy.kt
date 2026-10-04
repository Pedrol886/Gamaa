package com.gama.assistant

import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale

data class GamaNoiseDecision(
    val accept: Boolean,
    val reply: String? = null,
)

object GamaSpeechNoisePolicy {
    private const val WINDOW_MS = 20_000L
    private const val NOISY_MODE_MS = 90_000L
    private const val REPLY_COOLDOWN_MS = 7_000L

    private val rejectedAt = ArrayDeque<Long>()
    @Volatile private var noisyUntil = 0L
    @Volatile private var lastReplyAt = 0L

    @Synchronized
    fun evaluate(raw: String, now: Long = System.currentTimeMillis()): GamaNoiseDecision {
        val text = normalize(raw)
        if (text.isBlank()) return reject(now)

        val safeShort = setOf(
            "sim", "nao", "não", "pode", "confirmo", "isso", "faca", "faça",
            "pare", "cancele", "oi", "ola", "olá", "valeu", "obrigado",
            "obrigada", "continue",
        )
        if (text in safeShort) return accept(now)

        val tokens = text.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return reject(now)

        val lettersDigits = text.count { it.isLetterOrDigit() || it.isWhitespace() }
        val strangeRatio = 1.0 - lettersDigits.toDouble() / text.length.coerceAtLeast(1)
        if (strangeRatio > 0.22) return reject(now)

        val repeated = tokens.groupingBy { it }.eachCount().values.maxOrNull() ?: 1
        if (tokens.size >= 4 && repeated >= 3) return reject(now)

        val uniqueRatio = tokens.toSet().size.toDouble() / tokens.size
        if (tokens.size >= 7 && uniqueRatio < 0.40) return reject(now)

        if (tokens.any { token ->
                token.length >= 6 &&
                    token.none { it in "aeiouáàâãéêíóôõúü" } &&
                    token.all { it.isLetter() }
            }) return reject(now)

        if (text.length <= 2) return reject(now)

        val noisy = now < noisyUntil
        val actionVerb = tokens.firstOrNull() in setOf(
            "abra", "abrir", "envie", "enviar", "mande", "mandar", "apague",
            "apagar", "ligue", "desligue", "marque", "adicione", "pesquise",
        )
        if (noisy && actionVerb && tokens.size < 2) return reject(now)

        return accept(now)
    }

    @Synchronized
    private fun accept(now: Long): GamaNoiseDecision {
        prune(now)
        return GamaNoiseDecision(true)
    }

    @Synchronized
    private fun reject(now: Long): GamaNoiseDecision {
        prune(now)
        rejectedAt.addLast(now)
        if (rejectedAt.size >= 2) noisyUntil = maxOf(noisyUntil, now + NOISY_MODE_MS)
        // Random fragments from wind/crowd should be ignored, not rewarded with
        // an endless "não entendi / repita" loop. After several fragments Gama
        // gives one neutral listening cue and keeps the session open.
        val reply = if (rejectedAt.size >= 3 && now - lastReplyAt >= REPLY_COOLDOWN_MS) {
            lastReplyAt = now
            "Estou ouvindo. Continue normalmente."
        } else null
        return GamaNoiseDecision(false, reply)
    }

    private fun prune(now: Long) {
        while (rejectedAt.isNotEmpty() && now - rejectedAt.first() > WINDOW_MS) {
            rejectedAt.removeFirst()
        }
    }

    private fun normalize(raw: String): String =
        Normalizer.normalize(
            raw.trim().lowercase(Locale("pt", "BR")).replace(Regex("\\s+"), " "),
            Normalizer.Form.NFC,
        )
}
