package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamaModelInstallPolicyTest {

    @Test
    fun detectsStalledDownload() {
        val now = 1_000_000L

        assertFalse(
            GamaModelInstallPolicy.isStalled(
                now,
                now - 30_000L,
                0L,
                0L,
            )
        )

        assertTrue(
            GamaModelInstallPolicy.isStalled(
                now,
                now - GamaModelInstallPolicy.STALL_TIMEOUT_MS,
                0L,
                0L,
            )
        )

        assertFalse(
            GamaModelInstallPolicy.isStalled(
                now,
                now - GamaModelInstallPolicy.STALL_TIMEOUT_MS,
                10L,
                0L,
            )
        )
    }
}
