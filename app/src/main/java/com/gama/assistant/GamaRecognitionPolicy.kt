package com.gama.assistant

/**
 * Small, deterministic speech-reliability policy kept outside Android APIs so it
 * can be regression-tested. It deliberately separates "feed the decoder" from
 * "trust an action": noisy frames may still be decoded, while sensitive actions
 * can require confirmation.
 */
object GamaRecognitionPolicy {
    fun hasVoiceEvidence(metrics: GamaAudioMetrics): Boolean {
        if (metrics.speechRms < 28.0) return false
        val relative = metrics.speechRms / metrics.noiseRms.coerceAtLeast(1.0)
        val speechBand = metrics.zeroCrossingRate in 0.015..0.48

        if (metrics.snrDb >= 0.8) return true
        if (speechBand && relative >= 1.07) return true

        // Wind can dominate low frequencies while intelligible consonants remain
        // after the high-pass front-end. Do not classify those frames as silence.
        if (metrics.windLikely && speechBand &&
            metrics.speechRms >= 120.0 && metrics.snrDb >= -1.5) return true

        // In a crowd, near-field speech can sit only slightly above the floor.
        return metrics.severeNoise && speechBand && relative >= 1.10
    }

    fun shouldFeedDecoder(
        sessionListening: Boolean,
        decoderActive: Boolean,
        inSpeechHold: Boolean,
        voiceEvidence: Boolean,
    ): Boolean = sessionListening || decoderActive || inSpeechHold || voiceEvidence

    fun bestTranscript(
        finalText: String,
        recentPartial: String,
        partialAgeMs: Long,
    ): String {
        val finalClean = finalText.trim().replace(Regex("\\s+"), " ")
        val partialClean = recentPartial.trim().replace(Regex("\\s+"), " ")
        val recent = partialAgeMs in 0..2600L

        if (finalClean.isBlank()) {
            return if (recent && partialClean.length >= 2) partialClean else ""
        }
        if (!recent || partialClean.isBlank()) return finalClean

        val finalWords = finalClean.split(' ').filter { it.isNotBlank() }
        val partialWords = partialClean.split(' ').filter { it.isNotBlank() }
        val finalNorm = VoicePolicy.normalize(finalClean)
        val partialNorm = VoicePolicy.normalize(partialClean)

        // Endpoint truncation sometimes turns "abra o whatsapp" into "abra".
        // Prefer the recent partial only when it clearly contains more of the
        // same utterance, not merely because it is longer.
        val sameUtterance =
            partialNorm.startsWith(finalNorm) ||
            finalNorm.startsWith(partialNorm) ||
            finalWords.any { it.length >= 3 && partialWords.contains(it) }

        if (sameUtterance && partialWords.size >= finalWords.size + 1 &&
            partialClean.length >= finalClean.length + 3) {
            return partialClean
        }
        return finalClean
    }

    fun isShortControl(text: String): Boolean =
        VoicePolicy.normalize(text) in setOf(
            "sim", "nao", "pode", "confirmo", "pare", "cancele", "cancelar",
            "continue", "continua"
        )

    fun shouldAcceptSessionFinal(
        text: String,
        commandSpeechStarted: Boolean,
        recentVoiceEvidence: Boolean,
    ): Boolean {
        if (text.isBlank()) return false
        if (ConversationSession.isGoodbye(text)) {
            return commandSpeechStarted && recentVoiceEvidence
        }
        if (GamaCriticalCommandPolicy.isLockScreen(text)) {
            return commandSpeechStarted || recentVoiceEvidence
        }
        return commandSpeechStarted || recentVoiceEvidence || isShortControl(text)
    }



    fun shouldForceEndpoint(
        decoderActive: Boolean,
        utteranceStartedAt: Long,
        lastVoiceEvidenceAt: Long,
        lastUsefulPartialAt: Long,
        lastSpeechAt: Long,
        now: Long,
        silenceMs: Long = 1550L,
    ): Boolean {
        if (!decoderActive || utteranceStartedAt <= 0L || lastSpeechAt <= 0L) return false
        val heardCurrentUtterance =
            lastVoiceEvidenceAt >= utteranceStartedAt ||
                lastUsefulPartialAt >= utteranceStartedAt
        return heardCurrentUtterance && now - lastSpeechAt >= silenceMs
    }

    fun needsNoiseConfirmation(
        crowdedOrWindy: Boolean,
        sensitiveAction: Boolean,
        confidence: Double,
    ): Boolean {
        if (!crowdedOrWindy || !sensitiveAction) return false
        if (!confidence.isFinite() || confidence < 0.0) return false
        return confidence < 0.58
    }
}
