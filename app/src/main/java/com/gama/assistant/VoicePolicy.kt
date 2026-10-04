package com.gama.assistant

import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

/** Offline voice policy. Scores are heuristic similarities, not biometric probabilities. */
object VoicePolicy {
    enum class Level { HIGH, MEDIUM, LOW }
    data class Match(val level: Level, val score: Double, val threshold: Double)

    // Gama is the product hotword. The extra spellings are conservative ASR
    // confusions for the same spoken name, not alternate assistant names.
    private val wake = Regex("\\b(?:gama|gamma)\\b")

    fun normalize(text: String): String {
        var normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        normalized = normalized.replace(
            Regex("\\b(?:zap\\s+zap|zapzap|zape|zap)\\b"),
            "whatsapp"
        )
        normalized = normalized.replace(
            Regex("\\b(?:whats\\s+app|what\\s+s\\s+up|wats\\s+app|uats\\s+app|whatsap|watsapp|watsap|uatsapp|uatsap)\\b"),
            "whatsapp"
        )
        return normalized.replace(Regex("\\s+"), " ").trim()
    }

    fun hasWake(text: String): Boolean = wake.containsMatchIn(normalize(text))
    fun wakeIndex(text: String): Int = wake.find(normalize(text))?.range?.first ?: -1

    /** Removes Gama only when it is actually being used as an address. */
    fun followUp(text: String): String {
        val normalized = normalize(text)
        val match = wake.find(normalized)
        return if (match?.range?.first == 0) normalized.substring(match.range.last + 1).trim()
            else normalized
    }

    fun command(text: String): String {
        val normalized = normalize(text)
        val match = wake.find(normalized) ?: return normalized
        return normalized.substring(match.range.last + 1).trim()
    }

    /**
     * Conservative TTS echo detector used by barge-in. If most words in the
     * microphone transcript are also a contiguous/prefix fragment of what
     * Gama is currently speaking, it is probably speaker echo rather than a
     * new user command.
     */
    fun looksLikeTtsEcho(heard: String, spoken: String?): Boolean {
        val h = normalize(heard)
        val s = normalize(spoken.orEmpty())
        if (h.isBlank() || s.isBlank()) return false
        if (hasWake(h)) return false
        val hw = h.split(' ').filter { it.isNotBlank() }
        val sw = s.split(' ').filter { it.isNotBlank() }
        if (hw.isEmpty() || sw.isEmpty()) return false
        if (hw.size == 1 && hw.first().length < 5) return false
        val joinedH = hw.joinToString(" ")
        if (s.contains(joinedH) && hw.size >= 2) return true
        val spokenSet = sw.toSet()
        val overlap = hw.count { it in spokenSet }.toDouble() / hw.size
        return hw.size >= 2 && overlap >= 0.80
    }

    /**
     * Barge-in requires either the hotword or a short but meaningful phrase.
     * With headphones there is no loudspeaker echo, so a single substantial
     * word is enough.
     */
    fun shouldBargeIn(partial: String, spoken: String?, headphones: Boolean): Boolean {
        val p = normalize(partial)
        if (p.isBlank()) return false
        if (hasWake(p)) return true
        if (looksLikeTtsEcho(p, spoken)) return false
        val words = p.split(' ').filter { it.isNotBlank() }
        if (headphones) return p.length >= 3
        return words.size >= 2 || p.length >= 8
    }

    fun looksLikePostActionNoise(text: String, confidence: Double): Boolean {
        val n = normalize(text)
        if (n.isBlank()) return true
        if (looksLikeNumericOnly(n)) return true
        val words = n.split(" ").filter { it.isNotBlank() }
        return confidence.isFinite() && confidence >= 0.0 && confidence < 0.48 && words.size <= 5
    }

    fun looksLikeNumericOnly(text: String): Boolean {
        val n = normalize(text)
        if (n.matches(Regex("\\d{1,18}"))) return true
        val numberWords = setOf(
            "zero", "um", "uma", "dois", "duas", "tres", "quatro", "cinco",
            "seis", "sete", "oito", "nove", "dez", "onze", "doze", "treze",
            "quatorze", "catorze", "quinze", "dezesseis", "dezessete", "dezoito",
            "dezenove", "vinte", "trinta", "quarenta", "cinquenta", "sessenta",
            "setenta", "oitenta", "noventa", "cem", "cento", "duzentos",
            "trezentos", "quatrocentos", "quinhentos", "seiscentos", "setecentos",
            "oitocentos", "novecentos", "mil", "milhao", "milhoes", "e"
        )
        val words = n.split(" ").filter { it.isNotBlank() }
        return words.isNotEmpty() && words.size <= 12 &&
            words.all { it in numberWords || it.matches(Regex("\\d+")) }
    }

    fun unit(vector: FloatArray): FloatArray? {
        if (vector.size < 16 || vector.any { !it.isFinite() }) return null
        val length = sqrt(vector.sumOf { it.toDouble() * it })
        if (!length.isFinite() || length < 1e-9) return null
        return FloatArray(vector.size) { (vector[it] / length).toFloat() }
    }

    fun similarity(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return -1.0
        val x = unit(a) ?: return -1.0
        val y = unit(b) ?: return -1.0
        return x.indices.sumOf { x[it].toDouble() * y[it] }.coerceIn(-1.0, 1.0)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val v = values.sorted()
        return (v[(v.size - 1) / 2] + v[v.size / 2]) / 2.0
    }

    fun threshold(references: List<FloatArray>): Double {
        val pairs = references.indices.flatMap { i ->
            (i + 1 until references.size).map { j -> similarity(references[i], references[j]) }
        }
        if (pairs.isEmpty()) return 0.70
        val middle = median(pairs)
        val dispersion = median(pairs.map { kotlin.math.abs(it - middle) })
        return (middle - 0.13 - dispersion * 0.5).coerceIn(0.62, 0.78)
    }

    fun assess(sample: FloatArray?, frames: Int, references: List<FloatArray>): Match {
        val anchors = references.take(7).mapNotNull(::unit)
        val threshold = threshold(anchors)
        if (anchors.isEmpty()) return Match(Level.LOW, -1.0, threshold)
        val candidate = sample?.let(::unit)
            ?: return Match(Level.MEDIUM, -1.0, threshold)
        if (frames < 45) return Match(Level.MEDIUM, -1.0, threshold)
        val scores = references.filter { it.size == candidate.size }
            .map { similarity(it, candidate) }.sortedDescending()
        if (scores.isEmpty()) return Match(Level.LOW, -1.0, threshold)
        val best = scores.take(3).average()
        val score = best * 0.75 + median(scores) * 0.25
        val support = scores.count { it >= threshold - 0.07 }
        val level = when {
            score >= threshold && support >= minOf(2, scores.size) -> Level.HIGH
            score >= maxOf(0.50, threshold - 0.12) -> Level.MEDIUM
            else -> Level.LOW
        }
        return Match(level, score, threshold)
    }

    fun challengeMatches(heard: String, expected: String): Boolean {
        val numbers = mapOf("1" to "um", "2" to "dois", "3" to "tres", "4" to "quatro",
            "5" to "cinco", "6" to "seis", "7" to "sete", "8" to "oito", "9" to "nove")
        fun canonical(s: String) = normalize(s).split(" ").joinToString(" ") { numbers[it] ?: it }
        return expected.isNotBlank() && canonical(heard) == canonical(expected)
    }
}

/** Clock supplied by the audio loop: a start deadline never truncates active speech. */
class CommandWindow {
    private var deadline = 0L
    var started = false
        private set
    fun arm(now: Long, startMs: Long = 2000L) { deadline = now + startMs; started = false }
    fun speech(now: Long): Boolean {
        if (deadline == 0L || (!started && now > deadline)) return false
        started = true
        return true
    }
    fun expired(now: Long): Boolean = deadline != 0L && !started && now > deadline
    fun clear() { deadline = 0L; started = false }
}
