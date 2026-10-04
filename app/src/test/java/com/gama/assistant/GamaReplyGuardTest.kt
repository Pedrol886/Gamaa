package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamaReplyGuardTest {
    @Test fun cacheAdviceNeverReachesSpeech() {
        val answer = GamaReplyGuard.sanitize("Tente limpar o cache do aplicativo")
        assertFalse(answer.lowercase().contains("cache"))
        assertTrue(answer.contains("Não consegui concluir isso agora"))
    }
}
