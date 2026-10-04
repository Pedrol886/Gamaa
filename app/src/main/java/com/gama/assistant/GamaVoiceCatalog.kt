package com.gama.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.text.Normalizer
import java.util.Locale

object GamaVoiceCatalog {
    private const val PREFS = "gama_voice_selection"
    private const val KEY_VOICE = "voice_name"

    data class Candidate(val voice: Voice, val label: String, val score: Int)

    fun savedVoiceName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE, null)

    fun saveVoiceName(context: Context, name: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (name.isNullOrBlank()) remove(KEY_VOICE) else putString(KEY_VOICE, name)
        }.apply()
    }

    fun candidates(tts: TextToSpeech): List<Candidate> {
        return runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .asSequence()
            .filter { it.locale?.language.equals("pt", ignoreCase = true) }
            .map { voice ->
                var score = 0
                val country = voice.locale?.country.orEmpty().uppercase(Locale.ROOT)
                if (country == "BR") score += 500
                else if (country == "PT") score += 350
                score += voice.quality.coerceIn(0, 500) * 2
                if (voice.isNetworkConnectionRequired) score += 180 else score += 80
                val naturalName = normalize(voice.name)
                if (listOf("natural", "neural", "premium", "enhanced", "network")
                        .any { it in naturalName }
                ) score += 300

                // Only explicit metadata/name tokens influence this hint. Android Voice has
                // no standard gender field, so no gender is invented when the engine omits it.
                val tokens = normalizedTokens(voice.name)
                if (tokens.any { it in setOf("male", "masculino", "masc", "man", "homem", "ptd") }) score += 650
                if (tokens.any { it in setOf("female", "feminino", "fem", "woman", "mulher", "afs") }) score -= 900

                val localeLabel = voice.locale?.toLanguageTag().orEmpty()
                val network = if (voice.isNetworkConnectionRequired) "rede" else "offline/engine"
                Candidate(voice, "$localeLabel • ${voice.name} • $network", score)
            }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.voice.name })
            .toList()
    }

    fun applyPreferred(context: Context, tts: TextToSpeech): Boolean {
        val saved = savedVoiceName(context)
        val savedCandidate = if (!saved.isNullOrBlank()) {
            runCatching { tts.voices.orEmpty().firstOrNull { it.name == saved } }.getOrNull()
        } else null

        fun isExplicitMasculine(voice: Voice): Boolean {
            val tokens = normalizedTokens(voice.name)
            return tokens.any { it in setOf("male", "masculino", "masc", "man", "homem", "ptd") } &&
                tokens.none { it in setOf("female", "feminino", "fem", "woman", "mulher", "afs") }
        }

        // Never let an old feminine/unknown manual choice override the automatic
        // masculine profile after this upgrade. The user can still choose again later.
        val safeSaved = savedCandidate?.takeIf(::isExplicitMasculine)
        if (savedCandidate != null && safeSaved == null) saveVoiceName(context, null)

        val explicitMasculineCandidate = candidates(tts)
            .filter { isExplicitMasculine(it.voice) }
            .sortedWith(compareBy<Candidate> { it.voice.isNetworkConnectionRequired }.thenByDescending { it.score })
            .firstOrNull()
            ?.voice
        val candidate = safeSaved ?: explicitMasculineCandidate ?: return false
        return runCatching { tts.voice = candidate; true }.getOrDefault(false)
    }

    fun applyVoiceByName(context: Context, tts: TextToSpeech, voiceName: String): Boolean {
        val candidate = runCatching { tts.voices.orEmpty().firstOrNull { it.name == voiceName } }.getOrNull()
            ?: return false
        val ok = runCatching { tts.voice = candidate; true }.getOrDefault(false)
        if (ok) saveVoiceName(context, voiceName)
        return ok
    }

    fun looksLikeSettingsCommand(raw: String): Boolean {
        val c = normalize(raw)
        return c.contains("voz do gama") || c.contains("configurar voz") ||
            c.contains("trocar voz") || c.contains("mudar voz") || c.contains("escolher voz")
    }

    private fun normalizedTokens(text: String): Set<String> =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
            .toSet()

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9_-]+"), " ")
            .trim()
}
