package com.gama.assistant

import org.junit.Assert.*
import org.junit.Test

class AutomationDuePolicyTest {
    @Test fun dailyFiresOnlyOncePerDay() {
        val s = AutomationSnapshot("2026-09-28", 420, 80, false, false)
        assertTrue(AutomationDuePolicy.dailyDue(420, "2026-09-27", s))
        assertFalse(AutomationDuePolicy.dailyDue(420, "2026-09-28", s))
    }

    @Test fun batteryRequiresCrossing() {
        assertTrue(AutomationDuePolicy.batteryCrossedBelow(21, 19, 20))
        assertFalse(AutomationDuePolicy.batteryCrossedBelow(19, 18, 20))
        assertFalse(AutomationDuePolicy.batteryCrossedBelow(null, 10, 20))
    }

    @Test fun edgesOnlyFireOnce() {
        assertTrue(AutomationDuePolicy.becameConnected(false, true))
        assertFalse(AutomationDuePolicy.becameConnected(true, true))
        assertTrue(AutomationDuePolicy.chargingStarted(false, true))
    }
}
