package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamaRecognitionPolicyTest {
    @Test fun openConversationNeverStarvesDecoder() {
        assertTrue(
            GamaRecognitionPolicy.shouldFeedDecoder(
                sessionListening = true,
                decoderActive = false,
                inSpeechHold = false,
                voiceEvidence = false,
            )
        )
    }

    @Test fun recentPartialRecoversEmptyFinal() {
        assertEquals(
            "abra o whatsapp",
            GamaRecognitionPolicy.bestTranscript("", "abra o whatsapp", 300L),
        )
        assertEquals(
            "",
            GamaRecognitionPolicy.bestTranscript("", "abra o whatsapp", 3000L),
        )
    }

    @Test fun recentLongerPartialRepairsTruncatedEndpoint() {
        assertEquals(
            "abra o whatsapp",
            GamaRecognitionPolicy.bestTranscript("abra", "abra o whatsapp", 250L),
        )
    }

    @Test fun noisySensitiveActionUsesConfirmationInsteadOfRepeat() {
        assertTrue(GamaRecognitionPolicy.needsNoiseConfirmation(true, true, 0.42))
        assertFalse(GamaRecognitionPolicy.needsNoiseConfirmation(true, false, 0.20))
        assertFalse(GamaRecognitionPolicy.needsNoiseConfirmation(false, true, 0.20))
    }

    @Test fun shortConversationControlsAreAcceptedWithoutVadEvidence() {
        assertTrue(GamaRecognitionPolicy.shouldAcceptSessionFinal("sim", false, false))
        assertFalse(GamaRecognitionPolicy.shouldAcceptSessionFinal("pode descansar", false, false))
        assertFalse(GamaRecognitionPolicy.shouldAcceptSessionFinal("pode descansar", true, false))
        assertTrue(GamaRecognitionPolicy.shouldAcceptSessionFinal("pode descansar", true, true))
        assertFalse(GamaRecognitionPolicy.shouldAcceptSessionFinal("ruido qualquer", false, false))
    }


    @Test fun endpointDoesNotFlushBeforeAnySpeech() {
        assertFalse(
            GamaRecognitionPolicy.shouldForceEndpoint(
                decoderActive = true,
                utteranceStartedAt = 1_000L,
                lastVoiceEvidenceAt = 0L,
                lastUsefulPartialAt = 0L,
                lastSpeechAt = 0L,
                now = 10_000L,
            )
        )
        assertTrue(
            GamaRecognitionPolicy.shouldForceEndpoint(
                decoderActive = true,
                utteranceStartedAt = 1_000L,
                lastVoiceEvidenceAt = 1_200L,
                lastUsefulPartialAt = 0L,
                lastSpeechAt = 1_300L,
                now = 3_000L,
            )
        )
    }
}
