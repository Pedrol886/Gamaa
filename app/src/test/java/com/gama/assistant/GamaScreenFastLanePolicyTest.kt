package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamaScreenFastLanePolicyTest {
    @Test fun onlyExplicitScreenRequestsEnterTheScreenLane() {
        assertTrue(GamaScreenFastLane.isExplicitScreenCommand("Gama, leia a tela"))
        assertTrue(GamaScreenFastLane.isExplicitScreenCommand("resuma o que está na tela"))
        assertTrue(GamaScreenFastLane.isExplicitScreenCommand("o que tem na tela?"))
        assertFalse(GamaScreenFastLane.isExplicitScreenCommand("veja isso"))
        assertFalse(GamaScreenFastLane.isExplicitScreenCommand("bom dia"))
        assertFalse(GamaScreenFastLane.isExplicitScreenCommand("parabéns cara, você está funcionando muito bem"))
        assertFalse(GamaScreenFastLane.isExplicitScreenCommand("mande uma mensagem para João"))
        assertFalse(GamaScreenFastLane.isExplicitScreenCommand("qual o clima hoje?"))
        assertFalse(GamaScreenFastLane.isExplicitScreenCommand("não leia a tela"))
    }
}
