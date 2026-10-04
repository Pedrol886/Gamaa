package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamaTimePolicyTest {
    @Test fun periodsUseDeviceHour() {
        assertEquals(GamaTimePolicy.Period.NIGHT, GamaTimePolicy.periodForHour(4))
        assertEquals(GamaTimePolicy.Period.MORNING, GamaTimePolicy.periodForHour(8))
        assertEquals(GamaTimePolicy.Period.AFTERNOON, GamaTimePolicy.periodForHour(15))
        assertEquals(GamaTimePolicy.Period.NIGHT, GamaTimePolicy.periodForHour(20))
    }

    @Test fun greetingsAreDetectedWithoutTrustingTheirPeriod() {
        assertTrue(GamaTimePolicy.isGreeting("Gama, bom dia"))
        assertTrue(GamaTimePolicy.isGreeting("boa tarde"))
        assertTrue(GamaTimePolicy.isGreeting("boa noite gama"))
        assertFalse(GamaTimePolicy.isGreeting("bom dia, abra o WhatsApp"))
    }

    @Test fun sleepRequiresAnExplicitSleepIntent() {
        assertTrue(GamaTimePolicy.isExplicitSleep("Gama, vou dormir"))
        assertFalse(GamaTimePolicy.isExplicitSleep("boa noite"))
    }
}
