package com.gama.assistant

import org.junit.Assert.*
import org.junit.Test

class ActionFollowUpTest {
    @Test fun confirmationIsExplicit() {
        assertTrue(ActionFollowUp.isConfirmation("pode executar"))
        assertTrue(ActionFollowUp.isConfirmation("faça isso"))
        assertTrue(ActionFollowUp.isConfirmation("sim"))
        assertTrue(ActionFollowUp.isConfirmation("confirmo"))
        assertFalse(ActionFollowUp.isConfirmation("pode chover hoje"))
        assertTrue(ActionFollowUp.isRejection("não"))
        assertTrue(ActionFollowUp.isRejection("cancele"))
        assertFalse(ActionFollowUp.isRejection("não sei"))
    }

    @Test fun mapsOnlyKnownLocalSuggestions() {
        assertEquals("abrir whatsapp", ActionFollowUp.suggestedCommand("Se quiser, posso abrir o WhatsApp para você."))
        assertEquals("ligar lanterna", ActionFollowUp.suggestedCommand("Posso ligar a lanterna."))
        assertEquals("abrir configuracoes", ActionFollowUp.suggestedCommand("Posso abrir as configurações."))
        assertEquals("bloquear tela", ActionFollowUp.suggestedCommand("Posso bloquear a tela."))
        assertNull(ActionFollowUp.suggestedCommand("Você pode descansar melhor evitando café tarde."))
    }
}
