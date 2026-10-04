package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZevronAppToolTest {

    @Test
    fun whatsappCommandIsLocal() {

        assertEquals(
            "whatsapp",
            ZevronAppTool
                .parse(
                    "Gama, abra o WhatsApp"
                )
                ?.target
        )
    }

    @Test
    fun zapAliasWorks() {

        assertEquals(
            "zap",
            ZevronAppTool
                .parse(
                    "abra o zap"
                )
                ?.target
        )
    }

    @Test
    fun normalConversationDoesNotOpenApp() {

        assertNull(
            ZevronAppTool
                .parse(
                    "o WhatsApp é popular?"
                )
        )
    }
}
