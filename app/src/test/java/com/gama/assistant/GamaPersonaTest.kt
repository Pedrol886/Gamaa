package com.gama.assistant

import org.junit.Assert.*
import org.junit.Test

class GamaPersonaTest {
    @Test fun normalConversationDoesNotForceNameOrTitle() {
        assertEquals("Feito.", GamaPersona.address("Feito.", "Senhor", "Pedro"))
        assertEquals("estou ouvindo", GamaPersona.address("Sim senhor, estou ouvindo", "Senhor", "Pedro"))
        assertEquals("o presidente foi verificado.", GamaPersona.address("Gama, Gama, o presidente foi verificado.", "Senhor", "Pedro"))
    }

    @Test fun formalAddressIsAvailableForPrivateSecurityMoments() {
        assertEquals("Senhor Pedro, preciso confirmar sua identidade.",
            GamaPersona.formalAddress("preciso confirmar sua identidade.", "Senhor", "Pedro"))
    }

    @Test fun legacyTwoArgumentHelperStillWorks() {
        assertEquals("Senhor, Feito.", GamaPersona.address("Feito.", "Senhor"))
        assertEquals("Senhora, Certo.", GamaPersona.address("Certo.", "Senhora"))
    }

    @Test fun batteryAdviceUsesOnlyMeasuredLevel() {
        assertTrue(GamaPersona.batteryAdvice(9).contains("carregador"))
        assertEquals("", GamaPersona.batteryAdvice(75))
    }

    @Test fun offlinePromptIsNaturalAndNeverFakesTools() {
        val result = GamaPersona.prompt("Qual a agenda?", "", "Senhor", listOf("Prefere café"), true)
        assertTrue(result.contains("Você é Gama"))
        assertTrue(result.contains("não repita nome ou título em toda resposta", ignoreCase = true))
        assertTrue(result.contains("Prefere café"))
        assertTrue(result.contains("Modo foco local ativado"))
        assertTrue(result.contains("Não finja ter executado ações"))
        assertTrue(result.contains("autenticação oficial do Android"))
    }
}

class FreshnessPolicyTest {
    @Test fun currentOfficeholdersRequireFreshSource() {
        assertTrue(FreshnessPolicy.requiresFreshSource("qual o presidente atual do brasil"))
        assertTrue(FreshnessPolicy.requiresFreshSource("quem e o presidente do brasil"))
        assertTrue(FreshnessPolicy.requiresFreshSource("quais as últimas notícias?"))
        assertFalse(FreshnessPolicy.requiresFreshSource("quem foi presidente do brasil em 1990"))
        assertEquals("Presidente do Brasil", FreshnessPolicy.researchQuery("qual o presidente atual do brasil"))
    }
}
