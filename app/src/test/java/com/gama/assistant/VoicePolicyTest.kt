package com.gama.assistant

import org.junit.Assert.*
import org.junit.Test

class VoicePolicyTest {
    private fun vector(angle: Double): FloatArray = FloatArray(128).apply {
        this[0] = kotlin.math.cos(angle).toFloat()
        this[1] = kotlin.math.sin(angle).toFloat()
    }
    private val refs = listOf(-0.35, -0.22, -0.10, 0.0, 0.15, 0.28, 0.38).map(::vector)

    @Test fun wakeVariantsHaveWordBoundaries() {
        listOf("Gama", "Gamma").forEach { assertTrue(VoicePolicy.hasWake(it)) }
        listOf("zevron", "sevron", "kavus", "linx", "atlas", "nada").forEach { assertFalse(VoicePolicy.hasWake(it)) }
    }
    @Test fun inlineTimeCommandIsPreserved() { assertEquals("que horas sao", VoicePolicy.command("Gama, que horas são?")) }
    @Test fun inlineFlashlightCommandIsPreserved() { assertEquals("ligue a lanterna", VoicePolicy.command("Gama, ligue a lanterna")) }
    @Test fun inSessionMentionOfNameIsNotDiscarded() {
        assertEquals("me explique a letra gama", VoicePolicy.followUp("Me explique a letra gama"))
        assertEquals("que horas sao", VoicePolicy.followUp("Gama, que horas são?"))
        assertEquals("e depois", VoicePolicy.followUp("E depois?"))
    }
    @Test fun wakeOnlyHasNoCommand() { assertEquals("", VoicePolicy.command("Gama")) }
    @Test fun commandWithoutWakeIsPreserved() { assertEquals("desligue a lanterna", VoicePolicy.command("Desligue a lanterna")) }
    @Test fun scalingDoesNotChangeSpeakerScore() {
        val normal = VoicePolicy.assess(vector(0.1), 150, refs)
        val quiet = VoicePolicy.assess(vector(0.1).map { it * 0.02f }.toFloatArray(), 150, refs)
        assertEquals(normal.score, quiet.score, 0.00001)
    }
    @Test fun enrolledVariationsAreAccepted() { refs.forEach { assertEquals(VoicePolicy.Level.HIGH, VoicePolicy.assess(it, 150, refs).level) } }
    @Test fun moderateUnseenVariationRequestsChallenge() { assertEquals(VoicePolicy.Level.MEDIUM, VoicePolicy.assess(vector(0.88), 150, refs).level) }
    @Test fun clearlyDifferentVectorIsRejected() { assertEquals(VoicePolicy.Level.LOW, VoicePolicy.assess(vector(2.2), 150, refs).level) }
    @Test fun shortSpeechNeedsChallenge() { assertEquals(VoicePolicy.Level.MEDIUM, VoicePolicy.assess(vector(0.1), 10, refs).level) }
    @Test fun noProfileDoesNotAuthorize() { assertEquals(VoicePolicy.Level.LOW, VoicePolicy.assess(vector(0.0), 150, emptyList()).level) }
    @Test fun malformedEmbeddingsCannotAuthorize() {
        assertNull(VoicePolicy.unit(FloatArray(128)))
        assertNull(VoicePolicy.unit(FloatArray(128) { Float.NaN }))
        assertNull(VoicePolicy.unit(FloatArray(128) { Float.POSITIVE_INFINITY }))
    }
    @Test fun oneOutlierReferenceDoesNotOverrideMajority() {
        val poisoned = refs.take(6) + listOf(vector(2.3))
        assertEquals(VoicePolicy.Level.LOW, VoicePolicy.assess(vector(2.3), 150, poisoned).level)
    }
    @Test fun diversityThresholdHasSecurityFloor() {
        assertTrue(VoicePolicy.threshold(listOf(vector(0.0), vector(2.0), vector(4.0))) >= 0.62)
        assertTrue(VoicePolicy.threshold(refs) <= 0.78)
    }
    @Test fun challengeAcceptsEquivalentNumberSpelling() { assertTrue(VoicePolicy.challengeMatches("azul 7 porta 9", "azul sete porta nove")) }
    @Test fun challengeRejectsReorderedOrOldPhrase() {
        assertFalse(VoicePolicy.challengeMatches("azul nove porta sete", "azul sete porta nove"))
        assertFalse(VoicePolicy.challengeMatches("azul sete", "azul sete porta nove"))
        assertFalse(VoicePolicy.challengeMatches("", ""))
    }
    @Test fun silentWindowExpiresAfterTwoSeconds() {
        val window = CommandWindow(); window.arm(1000)
        assertFalse(window.expired(2999)); assertTrue(window.expired(3001))
        assertFalse(window.speech(3001))
    }
    @Test fun longCommandStartingInTimeIsNotCut() {
        val window = CommandWindow(); window.arm(1000)
        assertTrue(window.speech(2800)); assertFalse(window.expired(90000))
    }
    @Test fun closedWindowIgnoresBackgroundSpeech() {
        val window = CommandWindow(); window.arm(1000); window.clear()
        assertFalse(window.speech(1100)); assertFalse(window.expired(90000))
    }
    @Test fun windowCannotStartBeforeTtsArmsIt() { assertFalse(CommandWindow().speech(1000)) }

    @Test fun bargeInRejectsLikelyTtsEchoButAcceptsNewSpeech() {
        val spoken = "Certo, vou abrir o WhatsApp e verificar a conversa."
        assertTrue(VoicePolicy.looksLikeTtsEcho("vou abrir o whatsapp", spoken))
        assertFalse(VoicePolicy.shouldBargeIn("vou abrir o whatsapp", spoken, false))
        assertTrue(VoicePolicy.shouldBargeIn("não, abre o spotify", spoken, false))
        assertTrue(VoicePolicy.shouldBargeIn("Gama", spoken, false))
    }

    @Test fun zapAliasesAreCanonicalizedToWhatsapp() {
        assertEquals("abre o whatsapp", VoicePolicy.command("Gama, abre o zap"))
        assertEquals("manda mensagem no whatsapp para joao", VoicePolicy.followUp("manda mensagem no zap para Joao"))
        assertEquals("whatsapp", VoicePolicy.normalize("zap zap"))
        assertEquals("whatsapp", VoicePolicy.normalize("zape"))
    }

    @Test fun postActionNumericArtifactsAreRejected() {
        assertTrue(VoicePolicy.looksLikeNumericOnly("7667"))
        assertTrue(VoicePolicy.looksLikeNumericOnly("sete seis seis sete"))
        assertTrue(VoicePolicy.looksLikeNumericOnly("sete mil seiscentos e sessenta e sete"))
        assertTrue(VoicePolicy.looksLikePostActionNoise("7667", 0.92))
        assertTrue(VoicePolicy.looksLikePostActionNoise("algum ruido", 0.30))
        assertFalse(VoicePolicy.looksLikeNumericOnly("abre o whatsapp"))
        assertFalse(VoicePolicy.looksLikePostActionNoise("abre o whatsapp", 0.92))
        assertFalse(VoicePolicy.looksLikePostActionNoise("qual a bateria", 0.92))
    }
}
