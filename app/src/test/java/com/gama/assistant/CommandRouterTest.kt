package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRouterTest {
    @Test fun opensWhatsAppAcrossNaturalPhrasesAndRecognitionVariants() {
        assertEquals("whatsapp", CommandRouter.appToOpen("abra o whatsapp"))
        assertEquals("whatsapp", CommandRouter.appToOpen("abre o zap pra mim"))
        assertEquals("whatsapp", CommandRouter.appToOpen("pode abrir o uats app"))
        assertEquals("whatsapp", CommandRouter.appToOpen("vai no whats up"))
        assertEquals("whatsapp", CommandRouter.appToOpen("quero abrir watsapp agora"))
        assertEquals("whatsapp", CommandRouter.appToOpen("abre apesar"))
        assertEquals("whatsapp", CommandRouter.appToOpen("pode abrir a pesar"))
        assertEquals("whatsapp", CommandRouter.appToOpen("whatsapp"))
        assertNull(CommandRouter.appToOpen("apesar"))
    }

    @Test fun doesNotConfuseQuestionsOrMessagesWithOpenApp() {
        assertNull(CommandRouter.appToOpen("o que e whatsapp"))
        assertNull(CommandRouter.appToOpen("como funciona o whatsapp"))
        assertNull(CommandRouter.appToOpen("mande mensagem no whatsapp para joao"))
        assertNull(CommandRouter.appToOpen("envie uma mensagem pelo zap"))
        assertNull(CommandRouter.appToOpen("nao abra o whatsapp"))
    }

    @Test fun recognizesOtherKnownApps() {
        assertEquals("instagram", CommandRouter.appToOpen("entra no insta"))
        assertEquals("youtube", CommandRouter.appToOpen("abre o iutube"))
        assertEquals("spotify", CommandRouter.appToOpen("pode abrir spotify"))
        assertTrue(CommandRouter.looksLikeOpenRequest("quero abrir um aplicativo"))
    }

    @Test fun timeQuestionIsNotAnAppRequest() {
        org.junit.Assert.assertNull(CommandRouter.appToOpen("que horas são?"))
        org.junit.Assert.assertFalse(CommandRouter.looksLikeOpenRequest("que horas são?"))
    }

}
