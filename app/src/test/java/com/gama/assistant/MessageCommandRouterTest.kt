package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCommandRouterTest {
    @Test
    fun routesNaturalMessageQuestionsLocally() {
        assertTrue(MessageCommandRouter.isReadRequest("tenho alguma mensagem"))
        assertTrue(MessageCommandRouter.isReadRequest("eu tinha alguma mensagem"))
        assertTrue(MessageCommandRouter.isReadRequest("tem alguma mensagem pra mim"))
        assertTrue(MessageCommandRouter.isReadRequest("recebi mensagens novas"))
        assertTrue(MessageCommandRouter.isReadRequest("quais notificações eu tenho"))
        assertTrue(MessageCommandRouter.isReadRequest("minhas notificações"))
    }

    @Test
    fun doesNotStealGeneralQuestionsAboutNotifications() {
        assertFalse(MessageCommandRouter.isReadRequest("como funciona uma notificação"))
        assertFalse(MessageCommandRouter.isReadRequest("o que é uma notificação push"))
        assertFalse(MessageCommandRouter.isReadRequest("explique mensagens instantâneas"))
    }
    @Test
    fun naturalPermissionFollowUpReadsNotifications() {
        val phrase = "Certo, agora que dei as permissões, me diga as notificações que eu tenho"
        assertTrue(MessageCommandRouter.isReadRequest(phrase))
        assertTrue(MessageCommandRouter.isNotificationRequest(phrase))
        assertFalse(MessageCommandRouter.isMessageRequest(phrase))
    }

}
