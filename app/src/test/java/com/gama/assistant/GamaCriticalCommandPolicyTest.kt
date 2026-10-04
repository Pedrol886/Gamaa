package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamaCriticalCommandPolicyTest {
    @Test fun lockSurvivesSmallAsrErrors() {
        assertTrue(GamaCriticalCommandPolicy.isLockScreen("bloque a tela"))
        assertTrue(GamaCriticalCommandPolicy.isLockScreen("bloquei a tela"))
        assertTrue(GamaCriticalCommandPolicy.isLockScreen("trava tela"))
    }

    @Test fun unlockIsNeverRecoveredAsLock() {
        assertFalse(GamaCriticalCommandPolicy.isLockScreen("desbloqueie a tela"))
        assertFalse(GamaCriticalCommandPolicy.isLockScreen("destrave o celular"))
    }
}
