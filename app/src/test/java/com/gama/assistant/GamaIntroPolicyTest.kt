package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamaIntroPolicyTest {
    @Test
    fun presentationWaitsUntilEveryRequiredSetupPieceIsReady() {
        assertFalse(GamaIntroPolicy.shouldLaunch(false, true, true, true, false))
        assertFalse(GamaIntroPolicy.shouldLaunch(true, false, true, true, false))
        assertFalse(GamaIntroPolicy.shouldLaunch(true, true, false, true, false))
        assertFalse(GamaIntroPolicy.shouldLaunch(true, true, true, false, false))
        assertTrue(GamaIntroPolicy.shouldLaunch(true, true, true, true, false))
    }

    @Test
    fun presentationOnlyRunsOnce() {
        assertFalse(GamaIntroPolicy.shouldLaunch(true, true, true, true, true))
    }
}
