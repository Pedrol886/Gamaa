package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZevronJarvisCorePolicyTest {
    @Test
    fun brainNeverClaimsToolSuccessByInstruction() {
        val prompt = ZevronJarvisCore.brainInstruction("mande mensagem para João")
        assertTrue(prompt.contains("Não diga que abriu, enviou"))
        assertTrue(prompt.contains("ferramenta Android não confirmou"))
    }

    @Test
    fun brainInstructionDoesNotExposeTechnicalFailureLanguage() {
        val prompt = ZevronJarvisCore.brainInstruction("explique buracos negros")
        assertTrue(prompt.contains("Não mencione modelo .task"))
        assertFalse(prompt.contains("limpar cache", ignoreCase = true))
    }
}
