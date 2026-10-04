package com.gama.assistant

import org.junit.Assert.*
import org.junit.Test

class WhatsAppMessagingTest {
    @Test fun parsesNaturalMessageCommands() {
        val a = WhatsAppCommandParser.parse("Mande uma mensagem pelo zap para meu amigo João dizendo chego em dez minutos")
        assertNotNull(a)
        assertEquals("joao", a!!.target)
        assertEquals("chego em dez minutos", a.message)
        val b = WhatsAppCommandParser.parse("envie mensagem no whatsapp para Maria falando bom dia")
        assertEquals("maria", b!!.target)
        assertEquals("bom dia", b.message)
    }

    @Test fun parsesAvisarVariant() {
        val r = WhatsAppCommandParser.parse("avise uma mensagem para João dizendo vou chegar mais tarde")
        assertNotNull(r)
        assertEquals("joao", r!!.target)
        assertEquals("vou chegar mais tarde", r.message)
    }

    @Test fun asksForBodyWhenMissing() {
        val r = WhatsAppCommandParser.parse("mande mensagem para João")
        assertEquals("joao", r!!.target)
        assertNull(r.message)
    }
}
