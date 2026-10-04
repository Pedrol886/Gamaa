package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZevronPlannerTest {

    @Test
    fun explicitSequenceBecomesPlan() {
        val plan = ZevronPlanner.plan(
            "Gama, abra o WhatsApp e depois abra o Spotify"
        )

        requireNotNull(plan)

        assertEquals(
            2,
            plan.steps.size
        )

        assertTrue(
            plan.steps[0].contains(
                "WhatsApp",
                ignoreCase = true
            )
        )

        assertTrue(
            plan.steps[1].contains(
                "Spotify",
                ignoreCase = true
            )
        )
    }

    @Test
    fun lockThenRestBecomesPlan() {
        val plan = ZevronPlanner.plan(
            "Gama, bloqueie a tela e depois pode descansar"
        )

        requireNotNull(plan)

        assertEquals(
            2,
            plan.steps.size
        )

        assertTrue(
            GamaCriticalCommandPolicy.isLockScreen(
                plan.steps[0]
            )
        )

        assertTrue(
            ConversationSession.isGoodbye(
                plan.steps[1]
            )
        )
    }

    @Test
    fun naturalJarvisSequenceWithAiBecomesPlan() {
        val plan = ZevronPlanner.plan(
            "Gama, abra o WhatsApp, aí abra o Spotify"
        )

        requireNotNull(plan)

        assertEquals(
            2,
            plan.steps.size
        )
    }

    @Test
    fun commaSeparatedActionsBecomePlanOnlyWhenAllAreActions() {
        val plan = ZevronPlanner.plan(
            "abra o WhatsApp, abra o Spotify"
        )

        requireNotNull(plan)

        assertEquals(
            2,
            plan.steps.size
        )

        assertNull(
            ZevronPlanner.plan(
                "gosto de café, música e filmes"
            )
        )
    }

    @Test
    fun ordinaryConversationIsNotSplit() {
        assertNull(
            ZevronPlanner.plan(
                "gosto de café e música"
            )
        )
    }

    @Test
    fun maximumPlanIsConservative() {
        val twelve = (1..12).joinToString(" e depois ") { "abra app$it" }
        val supported = ZevronPlanner.plan(twelve)
        requireNotNull(supported)
        assertEquals(12, supported.steps.size)

        val thirteen = (1..13).joinToString(" e depois ") { "abra app$it" }
        assertNull(ZevronPlanner.plan(thirteen))
    }

    @Test
    fun naturalLongTaskSeparatorIsUnderstood() {
        val plan = ZevronPlanner.plan(
            "abra o WhatsApp e depois abra o Spotify quando terminar abra o YouTube"
        )
        requireNotNull(plan)
        assertEquals(3, plan.steps.size)
    }

    @Test
    fun notificationReadingCanParticipateInPlan() {
        val plan = ZevronPlanner.plan(
            "diga quais notificações eu tenho e depois abra o WhatsApp"
        )
        requireNotNull(plan)
        assertEquals(2, plan.steps.size)
        assertTrue(MessageCommandRouter.isNotificationRequest(plan.steps.first()))
    }

}
