package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class ZevronCalendarFlowTest {

    @Test
    fun incompleteRequestNeedsDateAndTime() {

        assertEquals(
            ZevronCalendarFlow
                .Missing.DATE_AND_TIME,
            ZevronCalendarFlow
                .missing(
                    "marque uma prova"
                )
        )
    }

    @Test
    fun dateWithoutTimeNeedsTime() {

        assertEquals(
            ZevronCalendarFlow
                .Missing.TIME,
            ZevronCalendarFlow
                .missing(
                    "marque prova amanhã"
                )
        )
    }

    @Test
    fun numericTimeWithHorasIsRecognized() {
        assertEquals(
            ZevronCalendarFlow.Missing.NONE,
            ZevronCalendarFlow.missing("marque dentista amanhã às 15 horas")
        )
    }

    @Test
    fun spokenTimeBecomesNumeric() {

        assertEquals(
            ZevronCalendarFlow
                .Missing.NONE,
            ZevronCalendarFlow
                .missing(
                    "marque prova amanhã às dez horas"
                )
        )
    }
}
