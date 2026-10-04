package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamaFaceCommandPolicyTest {
    @Test
    fun unlockPhrasesAreLocalFaceCommands() {
        assertTrue(
            GamaFaceCommandPolicy.isUnlockCommand(
                "Gama, desbloqueie meu celular"
            )
        )
        assertTrue(
            GamaFaceCommandPolicy.isUnlockCommand(
                "Gama, destrave o celular"
            )
        )
    }

    @Test
    fun privateContentRequiresFaceGateWhenLocked() {
        assertTrue(
            GamaFaceCommandPolicy.isSensitiveCommand(
                "Gama, leia minhas mensagens do WhatsApp"
            )
        )
        assertTrue(
            GamaFaceCommandPolicy.isSensitiveCommand(
                "Gama, o que eu tenho na agenda hoje?"
            )
        )
        assertTrue(
            GamaFaceCommandPolicy.isSensitiveCommand(
                "Gama, leia minha tela"
            )
        )
    }

    @Test
    fun publicInformationDoesNotRequirePrivateFaceGate() {
        assertFalse(
            GamaFaceCommandPolicy.isSensitiveCommand(
                "Gama, qual a previsão do tempo em Aracaju?"
            )
        )
        assertFalse(
            GamaFaceCommandPolicy.isSensitiveCommand(
                "Gama, quais são as notícias de hoje?"
            )
        )
    }

    @Test
    fun enrollmentPhraseIsRecognized() {
        assertTrue(
            GamaFaceCommandPolicy.isEnrollmentCommand(
                "Gama, cadastrar meu rosto"
            )
        )
    }
}
