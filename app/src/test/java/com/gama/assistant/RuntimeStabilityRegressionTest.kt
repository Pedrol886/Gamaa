package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStabilityRegressionTest {
    @Test fun messagesNeverUseBrain() {
        assertTrue(LocalCommandIsolationPolicy.mustNeverUseBrain("tenho alguma mensagem?"))
        assertTrue(LocalCommandIsolationPolicy.mustNeverUseBrain("tem mensagem pra mim?"))
        assertTrue(LocalCommandIsolationPolicy.isMessageRead("recebi alguma mensagem?"))
        assertTrue(LocalCommandIsolationPolicy.mustNeverUseBrain("quais notificações eu tenho?"))
    }

    @Test fun timeNeverUsesBrain() {
        assertEquals(LocalCommandIsolationPolicy.Kind.TIME, LocalCommandIsolationPolicy.kind("que horas são?"))
    }

    @Test fun batteryNeverUsesBrain() {
        assertEquals(LocalCommandIsolationPolicy.Kind.BATTERY, LocalCommandIsolationPolicy.kind("como está minha bateria?"))
    }

    @Test fun calendarNeverUsesBrain() {
        assertEquals(LocalCommandIsolationPolicy.Kind.CALENDAR, LocalCommandIsolationPolicy.kind("leia minha agenda hoje"))
    }

    @Test fun whatsappNeverUsesBrain() {
        assertEquals(LocalCommandIsolationPolicy.Kind.WHATSAPP, LocalCommandIsolationPolicy.kind("abra o zap"))
        assertEquals(LocalCommandIsolationPolicy.Kind.WHATSAPP, LocalCommandIsolationPolicy.kind("abra o watsap"))
    }

    @Test fun automationsNeverUseBrain() {
        assertEquals(LocalCommandIsolationPolicy.Kind.AUTOMATION, LocalCommandIsolationPolicy.kind("todo dia às 7 abra o Spotify"))
    }

    @Test fun restCommandEndsSessionPolicy() {
        assertTrue(SessionExitPolicy.shouldEnd("pode descansar"))
        assertTrue(SessionExitPolicy.shouldEnd("Gama, pode descansar"))
        assertFalse(SessionExitPolicy.shouldEnd("pode continuar"))
    }

    @Test fun failuresAreFailSoft() {
        assertFalse(FailSoftPolicy.mayTerminateMainProcess("brain IPC"))
        assertFalse(FailSoftPolicy.mayTerminateMainProcess("TTS"))
        assertFalse(FailSoftPolicy.mayTerminateMainProcess("automation"))
        assertEquals(FailSoftPolicy.Recovery.RESTART_COMPONENT, FailSoftPolicy.recovery("TTS"))
        assertEquals(FailSoftPolicy.Recovery.RESTART_COMPONENT, FailSoftPolicy.recovery(" tTs "))
        assertEquals(FailSoftPolicy.Recovery.RETRY_LATER, FailSoftPolicy.recovery("brain IPC"))
        assertEquals(FailSoftPolicy.Recovery.RETRY_LATER, FailSoftPolicy.recovery("BRAIN IPC"))
        assertEquals(FailSoftPolicy.Recovery.DISABLE_EXECUTION, FailSoftPolicy.recovery("automation"))
    }
}
