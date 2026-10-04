package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamaContinuityMemoryTest {
    @Test
    fun ordinaryContextCanBeRemembered() {
        assertTrue(GamaContinuityMemory.shouldRemember("Abra o WhatsApp e depois o Spotify"))
    }

    @Test
    fun obviousSecretsAreNotRemembered() {
        assertFalse(GamaContinuityMemory.shouldRemember("minha senha é 123456"))
        assertFalse(GamaContinuityMemory.shouldRemember("meu CVV é 999"))
    }
}
