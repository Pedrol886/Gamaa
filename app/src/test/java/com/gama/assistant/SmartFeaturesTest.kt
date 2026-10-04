package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartFeaturesTest {
    @Test fun routesCalendarWeatherAndDailyBriefing() {
        assertTrue(SmartFeatures.isAsync("minha agenda"))
        assertTrue(SmartFeatures.isAsync("agenda de amanha"))
        assertTrue(SmartFeatures.isAsync("eu tenho algum evento nos proximos dias"))
        assertTrue(SmartFeatures.isAsync("tenho compromissos essa semana"))
        assertTrue(SmartFeatures.isAsync("qual e meu proximo evento"))
        assertTrue(SmartFeatures.isAsync("tem algo marcado para hoje"))
        assertTrue(SmartFeatures.isAsync("alguma coisa marcada para hoje"))
        assertTrue(SmartFeatures.isAsync("tenho algo importante por esses dias"))
        assertTrue(SmartFeatures.isAsync("tenho alguma coisa importante essa semana"))
        assertTrue(SmartFeatures.isAsync("tempo em aracaju"))
        assertTrue(SmartFeatures.isAsync("vai chover hoje"))
        assertTrue(SmartFeatures.isAsync("resumo do dia"))
        assertFalse(SmartFeatures.isAsync("ligar lanterna"))
    }

    @Test fun routesExplicitCityAndCalendarForm() {
        assertTrue(SmartFeatures.isLocal("defina minha cidade como aracaju"))
        assertTrue(SmartFeatures.isLocal("apague minha cidade"))
        assertTrue(SmartFeatures.isLocal("marque compromisso consulta"))
        assertFalse(SmartFeatures.isLocal("abra aplicativo"))
    }
}
