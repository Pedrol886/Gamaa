package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamaDayBriefingPolicyTest {
    @Test fun afternoonGreetingRoutesLocally() {
        assertEquals(GamaDayBriefing.Period.AFTERNOON, GamaDayBriefing.classify("Gama, boa tarde"))
    }

    @Test fun nightGreetingRoutesToNightBriefing() {
        assertEquals(GamaDayBriefing.Period.NIGHT, GamaDayBriefing.classify("boa noite"))
    }

    @Test fun unrelatedCommandDoesNotBecomeBriefing() {
        assertNull(GamaDayBriefing.classify("abra o WhatsApp"))
    }
}
