package com.gama.assistant

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ZevronContextResolverTest {
    @Before fun reset() = ZevronContextResolver.reset()

    @Test fun remembersWhatsappRecipientForPronounFollowUp() {
        ZevronContextResolver.rememberCommand(
            "mande mensagem pelo whatsapp para João dizendo chego às oito"
        )
        assertTrue(
            ZevronContextResolver.resolve("manda pra ele que vou atrasar")
                .contains("João", ignoreCase = true)
        )
    }

    @Test fun reusesPreviousAppOnlyForExplicitAppReference() {
        ZevronContextResolver.rememberCommand("abra o Spotify")
        assertEquals("abra spotify", ZevronContextResolver.resolve("abra ele"))
        assertEquals("o que ele faz", ZevronContextResolver.resolve("o que ele faz"))
    }

    @Test fun tomorrowCanContinuePreviousSubject() {
        ZevronContextResolver.rememberCommand("qual a previsão do tempo hoje")
        val resolved = ZevronContextResolver.resolve("e amanhã?")
        assertTrue(resolved.contains("amanhã"))
        assertFalse(resolved.contains("hoje"))
    }

    @Test fun editsRecentWhatsappDraftByContext() {
        ZevronContextResolver.rememberCommand(
            "mande mensagem pelo whatsapp para João dizendo chego às oito"
        )
        val resolved = ZevronContextResolver.resolve("muda para nove")
        assertTrue(resolved.contains("João", ignoreCase = true))
        assertTrue(resolved.contains("nove", ignoreCase = true))
        assertFalse(resolved.contains("oito", ignoreCase = true))
    }

    @Test fun sessionResetClearsReferents() {
        ZevronContextResolver.rememberCommand("abra o Spotify")
        ZevronContextResolver.reset()
        assertEquals("abra ele", ZevronContextResolver.resolve("abra ele"))
    }

    @Test fun proactiveContactCanBeAnsweredWithoutRepeatingRecipient() {
        ZevronContextResolver.rememberExternalRecipient("Coulson", "Preciso falar com você")
        val resolved = ZevronContextResolver.resolve("diz que eu não tô, aliás eu tô fora")
        assertTrue(resolved.contains("Coulson", ignoreCase = true))
        assertTrue(resolved.contains("eu tô fora", ignoreCase = true))
        assertFalse(resolved.contains("não tô", ignoreCase = true))
    }
}
