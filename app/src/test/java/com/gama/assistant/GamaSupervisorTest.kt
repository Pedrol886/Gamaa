package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamaSupervisorTest {
    @Test fun idleWakeNeedsRealVoiceEvidence() {
        assertFalse(GamaSupervisor.acceptWake("gama", false, false))
        assertTrue(GamaSupervisor.acceptWake("gama", false, true))
        assertFalse(GamaSupervisor.acceptWake("gama", true, true))
    }

    @Test fun goodbyeNeedsActiveSessionFinalEndpointAndVoice() {
        assertFalse(GamaSupervisor.acceptGoodbye("pode descansar", false, true, true))
        assertFalse(GamaSupervisor.acceptGoodbye("pode descansar", true, false, true))
        assertFalse(GamaSupervisor.acceptGoodbye("pode descansar", true, true, false))
        assertTrue(GamaSupervisor.acceptGoodbye("pode descansar", true, true, true))
        assertFalse(GamaSupervisor.acceptGoodbye("preciso descansar amanhã", true, true, true))
    }

    @Test fun proactiveSpeechNeverStartsAnIdleConversation() {
        assertFalse(GamaSupervisor.maySpeakProactively(false, 1_000L))
        assertTrue(GamaSupervisor.maySpeakProactively(true, 1_000L))
        assertFalse(GamaSupervisor.maySpeakProactively(true, 30_000L))
    }
}
