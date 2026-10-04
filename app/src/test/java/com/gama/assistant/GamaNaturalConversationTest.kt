package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GamaNaturalConversationTest {
    @Test fun praiseIsAnsweredLocally() {
        assertNotNull(GamaSocialConversation.reply("Parabéns cara, você tá funcionando muito bem"))
    }

    @Test fun parsesWhatsAppSendWithDizendo() {
        val request = GamaWhatsAppSendParser.parse("Gama, envie uma mensagem para João dizendo chego às oito")
        assertNotNull(request)
        assertEquals("João", request?.recipient)
        assertEquals("chego às oito", request?.message)
    }

    @Test fun parsesWhatsAppSendWithColon() {
        val request = GamaWhatsAppSendParser.parse("mande uma mensagem no zap para Grupo Família: estou chegando")
        assertNotNull(request)
        assertEquals("Grupo Família", request?.recipient)
    }
}
