package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class GamaCalendarCommandParserTest {
    private val base = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 29, 19, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }

    @Test fun parsesTomorrowWithTime() {
        val draft = requireNotNull(
            GamaCalendarCommandParser.parseCreate(
                "Gama, marque dentista amanhã às 15h",
                base.timeInMillis,
            )
        )
        val start = Calendar.getInstance().apply { timeInMillis = draft.startMillis }
        assertEquals(30, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(15, start.get(Calendar.HOUR_OF_DAY))
        assertEquals("dentista", draft.title.lowercase())
    }

    @Test fun parsesAddToAgendaVariant() {
        val draft = requireNotNull(
            GamaCalendarCommandParser.parseCreate(
                "Gama, adicione reunião na agenda amanhã às 10 horas",
                base.timeInMillis,
            )
        )
        assertEquals("reunião", draft.title.lowercase())
    }

    @Test fun parsesColoqueVariant() {
        val draft = requireNotNull(
            GamaCalendarCommandParser.parseCreate(
                "coloque prova na agenda dia 30 às 8h",
                base.timeInMillis,
            )
        )
        val start = Calendar.getInstance().apply { timeInMillis = draft.startMillis }
        assertEquals(30, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, start.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun parsesReminderAndAfternoonTime() {
        val draft = requireNotNull(
            GamaCalendarCommandParser.parseCreate(
                "Gama, reserve consulta amanhã às 3 da tarde",
                base.timeInMillis,
            )
        )
        val start = Calendar.getInstance().apply { timeInMillis = draft.startMillis }
        assertEquals(30, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(15, start.get(Calendar.HOUR_OF_DAY))
        assertEquals("consulta", draft.title.lowercase())
    }

    @Test fun incompleteCalendarCreateDoesNotInventTime() {
        assertNull(
            GamaCalendarCommandParser.parseCreate(
                "Gama, adicione dentista na agenda",
                base.timeInMillis,
            )
        )
        val normalized = GamaCalendarManager.normalize("Gama, adicione dentista na agenda")
        assertTrue(GamaCalendarManager.looksCalendarRelated(normalized))
        assertTrue(GamaCalendarManager.looksLikeCreate(normalized))
    }
}
