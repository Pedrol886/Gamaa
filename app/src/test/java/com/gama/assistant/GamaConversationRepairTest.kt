package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class GamaConversationRepairTest {
    @Test fun latestCorrectionWins() {
        assertEquals("eu tô fora", GamaConversationRepair.latestClause("eu não tô, aliás eu tô fora"))
        assertEquals("às nove", GamaConversationRepair.latestClause("às oito, na verdade às nove"))
    }
}
