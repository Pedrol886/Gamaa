package com.gama.assistant

import org.junit.Assert.assertTrue
import org.junit.Test

class GamaVoiceHintTest {
    @Test
    fun masculineHintsOutrankFeminineHints() {
        val male = GamaVoice.masculineHintScore("pt-BR male_1 local ptd")
        val female = GamaVoice.masculineHintScore("pt-BR female_1 local afs")
        assertTrue(male > female)
        assertTrue(male > 0)
        assertTrue(female < 0)
    }
}
