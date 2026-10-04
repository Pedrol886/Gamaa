package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GamaSuperAssistantCoreTest {
    @Test
    fun understandsPositiveConfirmation() {
        assertEquals(
            GamaConversationLanguage.Confirmation.YES,
            GamaConversationLanguage.confirmation("pode fazer"),
        )
    }

    @Test
    fun understandsNegativeConfirmation() {
        assertEquals(
            GamaConversationLanguage.Confirmation.NO,
            GamaConversationLanguage.confirmation("melhor não"),
        )
    }

    @Test
    fun proposesPriceResearch() {
        val suggestion = GamaProposalEngine.suggest(
            "Gama, quero comprar um notebook Ryzen 7",
            hasScreenContext = false,
        )
        assertNotNull(suggestion)
        assertEquals("price_search", suggestion?.tool)
    }

    @Test
    fun proposesRoute() {
        val suggestion = GamaProposalEngine.suggest(
            "Gama, preciso ir para o shopping amanhã",
            hasScreenContext = false,
        )
        assertNotNull(suggestion)
        assertEquals("route", suggestion?.tool)
    }

    @Test
    fun screenSuggestionRequiresScreenContext() {
        assertNull(
            GamaProposalEngine.suggest(
                "Gama, não sei o que é isso",
                hasScreenContext = false,
            )
        )
        assertEquals(
            "screen_research",
            GamaProposalEngine.suggest(
                "Gama, não sei o que é isso",
                hasScreenContext = true,
            )?.tool,
        )
    }
}
