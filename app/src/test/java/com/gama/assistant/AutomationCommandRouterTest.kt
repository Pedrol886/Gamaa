package com.gama.assistant

import org.junit.Assert.*
import org.junit.Test

class AutomationCommandRouterTest {
    @Test fun dailySpotifyNumeric() {
        val d = AutomationCommandRouter.parseCreate("todo dia às 7 abra o Spotify")!!
        assertEquals(AutomationTriggerKind.DAILY_TIME, d.triggerKind)
        assertEquals("420", d.triggerValue)
        assertEquals(AutomationActionKind.COMMAND, d.actionKind)
        assertTrue(d.action.contains("spotify"))
    }

    @Test fun dailyAgendaWordTime() {
        val d = AutomationCommandRouter.parseCreate("todos os dias às sete e meia leia minha agenda")!!
        assertEquals("450", d.triggerValue)
        assertEquals("minha agenda", d.action)
    }

    @Test fun headsetSpotify() {
        val d = AutomationCommandRouter.parseCreate("quando eu conectar o fone abra o spotify")!!
        assertEquals(AutomationTriggerKind.HEADSET_CONNECTED, d.triggerKind)
    }

    @Test fun batteryDefaultAlert() {
        val d = AutomationCommandRouter.parseCreate("quando a bateria ficar abaixo de 20 por cento")!!
        assertEquals(AutomationTriggerKind.BATTERY_BELOW, d.triggerKind)
        assertEquals(AutomationActionKind.SPEAK, d.actionKind)
    }

    @Test fun blocksUnattendedMessaging() {
        assertNull(AutomationCommandRouter.parseCreate("todo dia as 8 mande mensagem para joao"))
    }

    @Test fun managementCommands() {
        assertTrue(AutomationCommandRouter.isListRequest("quais automações"))
        assertTrue(AutomationCommandRouter.isPauseAll("pause as automações"))
        assertTrue(AutomationCommandRouter.isResumeAll("ative as automações"))
    }
}
