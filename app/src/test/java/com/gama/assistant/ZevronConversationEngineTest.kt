package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class ZevronConversationEngineTest {

    @Test
    fun bareWakeWordIsWakeOnly() {

        assertEquals(
            ZevronConversationEngine
                .Control.WAKE_ONLY,
            ZevronConversationEngine
                .control(
                    "Gama"
                )
        )
    }

    @Test
    fun wakeWordPlusCommandIsNormalConversation() {

        assertEquals(
            ZevronConversationEngine
                .Control.NORMAL,
            ZevronConversationEngine
                .control(
                    "Gama, bom dia"
                )
        )
    }

    @Test
    fun acordadoIsOnlyNormalConversation() {

        assertEquals(
            ZevronConversationEngine
                .Control.NORMAL,
            ZevronConversationEngine
                .control(
                    "Gama, acordado?"
                )
        )
    }

    @Test
    fun restCommandEndsSession() {

        assertEquals(
            ZevronConversationEngine
                .Control.SLEEP,
            ZevronConversationEngine
                .control(
                    "pode descansar"
                )
        )
    }
}
