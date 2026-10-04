package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamaProactivePolicyTest {
    @Test fun urgentMessageIsHighPriority() {
        assertEquals(GamaEventHub.Priority.HIGH, GamaProactivePolicy.messagePriority("É urgente, me liga agora", 0))
    }

    @Test fun repeatedContactDoesNotBecomeUrgentByItself() {
        assertEquals(
            GamaEventHub.Priority.NORMAL,
            GamaProactivePolicy.messagePriority("Você está aí?", 1)
        )
    }

    @Test fun ordinaryMessageDoesNotInterruptByItself() {
        assertEquals(GamaEventHub.Priority.NORMAL, GamaProactivePolicy.messagePriority("Cheguei em casa", 0))
        assertFalse(GamaProactivePolicy.shouldSpeakNow(GamaEventHub.Priority.NORMAL, false, false, 60_000))
        assertTrue(GamaProactivePolicy.shouldSpeakNow(GamaEventHub.Priority.HIGH, false, false, 60_000))
    }
}
