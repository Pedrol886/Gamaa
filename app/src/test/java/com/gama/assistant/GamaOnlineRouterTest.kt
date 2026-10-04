package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class GamaOnlineRouterTest {
    @Test fun weatherGoesOnline() = assertEquals(GamaOnlineRouter.Mode.WEATHER, GamaOnlineRouter.classify("Gama, qual a previsão do tempo em Aracaju?"))
    @Test fun researchGoesOnline() = assertEquals(GamaOnlineRouter.Mode.RESEARCH, GamaOnlineRouter.classify("Gama, pesquise na internet sobre computação quântica"))
    @Test fun currencyGoesOnline() = assertEquals(GamaOnlineRouter.Mode.CURRENCY, GamaOnlineRouter.classify("Gama, quanto está o dólar?"))
    @Test fun calendarNeverGoesOnline() = assertEquals(GamaOnlineRouter.Mode.NONE, GamaOnlineRouter.classify("Gama, marque dentista amanhã às 15h na agenda"))
    @Test fun messagesNeverGoOnline() = assertEquals(GamaOnlineRouter.Mode.NONE, GamaOnlineRouter.classify("Gama, tenho alguma mensagem no WhatsApp?"))
    @Test fun electionScheduleUsesLiveSearch() = assertEquals(GamaOnlineRouter.Mode.LIVE_SEARCH, GamaOnlineRouter.classify("Gama, que horas começa a votação amanhã?"))
}
