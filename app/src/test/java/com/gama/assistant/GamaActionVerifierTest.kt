package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamaActionVerifierTest {
    @Test
    fun onlyVerifiedAndroidSuccessIsConfirmed() {
        assertEquals(
            GamaActionVerifier.Outcome.UNKNOWN,
            GamaActionVerifier.assess("Abrindo WhatsApp.")
        )
        assertEquals(
            GamaActionVerifier.Outcome.CONFIRMED,
            GamaActionVerifier.assess("WhatsApp aberto e confirmado na tela.")
        )
        assertTrue(GamaActionVerifier.mayContinuePlan("Toquei em Enviar."))
    }

    @Test
    fun failureStopsAutomaticPlan() {
        val reply = "Tentei abrir o Spotify, mas o Android não confirmou a troca de tela."
        assertEquals(GamaActionVerifier.Outcome.FAILED, GamaActionVerifier.assess(reply))
        assertFalse(GamaActionVerifier.mayContinuePlan(reply))
    }

    @Test
    fun neutralAnswerIsNotInventedAsVerifiedSuccess() {
        val reply = "O resultado da pesquisa fala sobre o assunto solicitado."
        assertEquals(GamaActionVerifier.Outcome.UNKNOWN, GamaActionVerifier.assess(reply))
        assertFalse(GamaActionVerifier.isVerifiedSuccess(reply))
        assertTrue(GamaActionVerifier.mayContinuePlan(reply))
    }
}
