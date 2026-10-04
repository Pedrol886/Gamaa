
package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ZevronFix101RegressionTest {

    @Test
    fun multiplicationWithWakeWordWorks() {

        val answer =
            ZevronMathTool.solve(
                "Gama, quanto é 18 vezes 37?"
            )

        assertNotNull(
            answer
        )

        assertEquals(
            "666",
            answer?.spoken
        )
    }

    @Test
    fun multiplicationWithoutWakeWordWorks() {

        val answer =
            ZevronMathTool.solve(
                "quanto é 18 vezes 37?"
            )

        assertEquals(
            "666",
            answer?.spoken
        )
    }

    @Test
    fun bareZevronIsWakeOnly() {

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
    fun acordadoIsNormalConversation() {

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
    fun commandAfterZevronIsNormal() {

        assertEquals(
            ZevronConversationEngine
                .Control.NORMAL,
            ZevronConversationEngine
                .control(
                    "Gama, abra o WhatsApp"
                )
        )
    }

    @Test
    fun restEndsConversation() {

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
