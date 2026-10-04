package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZevronSystemControlTest {
    @Test fun recognizesNavigationAndUiCommands() {
        assertTrue(ZevronSystemControl.recognizes("Gama, volte"))
        assertTrue(ZevronSystemControl.recognizes("toque em enviar"))
        assertTrue(ZevronSystemControl.recognizes("digite Pedro"))
        assertTrue(ZevronSystemControl.recognizes("role para baixo"))
        assertTrue(ZevronSystemControl.recognizes("role a tela"))
        assertFalse(ZevronSystemControl.recognizes("leia a tela"))
        assertFalse(ZevronSystemControl.recognizes("qual a capital do Brasil"))
    }

    @Test fun parsesTargets() {
        assertEquals("enviar", ZevronSystemControl.clickTarget("toque em enviar"))
        assertEquals("ola mundo", ZevronSystemControl.textToType("digite olá mundo"))
        assertEquals(true, ZevronSystemControl.scrollDirection("desça"))
        assertEquals(false, ZevronSystemControl.scrollDirection("suba"))
    }
}
