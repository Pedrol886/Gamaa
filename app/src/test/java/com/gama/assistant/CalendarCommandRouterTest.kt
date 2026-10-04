package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarCommandRouterTest {
    @Test fun recognizesNaturalCalendarCreationRequests() {
        assertTrue(CalendarCommandRouter.looksLikeCreateRequest("marque uma coisa na agenda"))
        assertTrue(CalendarCommandRouter.looksLikeCreateRequest("agende um compromisso dentista"))
        assertTrue(CalendarCommandRouter.looksLikeCreateRequest("adicione um evento no calendario"))
        assertTrue(CalendarCommandRouter.looksLikeCreateRequest("crie um compromisso na agenda"))
    }

    @Test fun extractsUsefulPayloadWithoutActionWords() {
        assertEquals(
            "dentista amanha as 15",
            CalendarCommandRouter.payload("marque uma coisa na agenda dentista amanha as 15")
        )
    }

    @Test fun doesNotClassifyOrdinaryCalendarQuestionsAsCreation() {
        assertFalse(CalendarCommandRouter.looksLikeCreateRequest("o que tenho na agenda hoje"))
        assertFalse(CalendarCommandRouter.looksLikeCreateRequest("qual meu proximo evento"))
    }
}
