package com.gama.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Gama voice profile.
 *
 * Android does not expose a standard gender field for every TTS engine. We use
 * explicit engine metadata/name hints when available, prefer a local pt-BR
 * masculine voice, and only then fall back to the current system voice with a
 * slightly lower pitch. This never clones or stores a person's voice.
 */
object GamaVoice {
    val modes = listOf("Cinemática", "Formal", "Natural", "Cadenciada")

    private val maleHints = listOf(
        "male", "mascul", "homem", "masc", "male_1", "male_2", "ptd"
    )
    private val femaleHints = listOf(
        "female", "feminin", "mulher", "female_1", "female_2", "afs"
    )

    fun selected(context: Context): String =
        context.getSharedPreferences("gama_voice_style", 0)
            .getString("mode", "Cinemática")
            ?.takeIf { it in modes }
            ?: "Cinemática"

    fun select(context: Context, mode: String) {
        require(mode in modes)
        context.getSharedPreferences("gama_voice_style", 0)
            .edit()
            .putString("mode", mode)
            .apply()
    }

    fun apply(context: Context, engine: TextToSpeech?) {
        // GAMA76_VOICE_STYLE
        GamaVoiceStyle.apply(engine)

        // GAMA68_PREFERRED_VOICE_GATE
        // Select an explicit masculine voice when the TTS engine exposes one,
        // but always continue so the masculine pitch/rate profile is applied too.
        engine?.let { GamaVoiceCatalog.applyPreferred(context, it) }

        if (engine == null) return

        try {
            if (engine.language?.language?.equals("pt", true) != true) {
                engine.language = Locale("pt", "BR")
            }
        } catch (_: Exception) {}

        trySelectMasculinePortugueseVoice(engine)

        val (pitch, rate) = when (selected(context)) {
            "Natural" -> 0.82f to 0.96f
            "Cadenciada" -> 0.72f to 0.84f
            "Formal" -> 0.75f to 0.90f
            else -> 0.74f to 0.90f
        }

        try {
            engine.setPitch(pitch)
            engine.setSpeechRate(rate)
        } catch (_: Exception) {}
    }

    private fun searchable(voice: Voice): String = buildString {
        append(voice.name.lowercase(Locale.ROOT))
        append(' ')
        append(voice.locale.toLanguageTag().lowercase(Locale.ROOT))
        for (feature in voice.features.orEmpty()) {
            append(' ')
            append(feature.lowercase(Locale.ROOT))
        }
    }

    internal fun masculineHintScore(text: String): Int {
        val q = text.lowercase(Locale.ROOT)
        var score = 0
        val tokens = q.split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
            .toSet()
        if (maleHints.any { it in tokens }) score += 240
        if (femaleHints.any { it in tokens }) score -= 320
        if (q.contains("pt-br") || q.contains("pt_br")) score += 45
        if (q.contains("local") || q.contains("embedded")) score += 35
        return score
    }

    private fun voiceScore(voice: Voice): Int {
        var score = masculineHintScore(searchable(voice))
        if (voice.locale.language.equals("pt", true)) score += 80
        if (voice.locale.country.equals("BR", true)) score += 70
        if (!voice.isNetworkConnectionRequired) score += 90 else score -= 120
        score += voice.quality.coerceIn(0, 500) / 8
        score -= voice.latency.coerceIn(0, 500) / 12
        return score
    }

    private fun trySelectMasculinePortugueseVoice(engine: TextToSpeech) {
        try {
            val voices = engine.voices.orEmpty()
                .filter { it.locale.language.equals("pt", ignoreCase = true) }
                .filter { !it.isNetworkConnectionRequired }
            if (voices.isEmpty()) return

            val ranked = voices.sortedByDescending(::voiceScore)
            val best = ranked.firstOrNull() ?: return
            val bestHint = masculineHintScore(searchable(best))

            // Do not replace the system voice with a clearly feminine candidate.
            if (bestHint > 0 && best != engine.voice) {
                engine.voice = best
            }
        } catch (_: Exception) {}
    }
}
