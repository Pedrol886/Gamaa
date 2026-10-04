package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamaSpeechNoisePolicyTest {
    @Test fun keepsShortConversationReplies() {
        assertTrue(GamaSpeechNoisePolicy.evaluate("sim", 1_000L).accept)
        assertTrue(GamaSpeechNoisePolicy.evaluate("não", 2_000L).accept)
        assertTrue(GamaSpeechNoisePolicy.evaluate("valeu", 3_000L).accept)
    }

    @Test fun acceptsNormalCommands() {
        assertTrue(GamaSpeechNoisePolicy.evaluate("abra o whatsapp", 40_000L).accept)
        assertTrue(GamaSpeechNoisePolicy.evaluate("qual a previsão do tempo hoje", 41_000L).accept)
    }

    @Test fun rejectsTypicalNoiseGarble() {
        assertFalse(GamaSpeechNoisePolicy.evaluate("x", 80_000L).accept)
        assertFalse(GamaSpeechNoisePolicy.evaluate("rrrrrr", 81_000L).accept)
        assertFalse(GamaSpeechNoisePolicy.evaluate("oi oi oi oi oi", 82_000L).accept)
    }
}
