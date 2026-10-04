package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ZevronMathToolTest {

    @Test
    fun multiplicationWorks() {

        assertEquals(
            "666",
            ZevronMathTool
                .solve(
                    "Gama, quanto é 18 vezes 37?"
                )
                ?.spoken
        )
    }

    @Test
    fun linearEquationWorks() {

        assertEquals(
            "x é igual a 6.",
            ZevronMathTool
                .solve(
                    "resolva 2x + 7 = 19"
                )
                ?.spoken
        )
    }

    @Test
    fun quadraticEquationWorks() {

        assertNotNull(
            ZevronMathTool
                .solve(
                    "resolva x² - 5x + 6 = 0"
                )
        )
    }
}
