package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZevronPerceptionPolicyTest {
    @Test fun explicitDeicticQuestionUsesVisibleContext() {
        assertTrue(ZevronPerceptionPolicy.wantsVisibleContext("Gama, o que é isso?"))
        assertTrue(ZevronPerceptionPolicy.wantsVisibleContext("me explica isso"))
    }

    @Test fun ordinaryPronounDoesNotReadScreen() {
        assertFalse(ZevronPerceptionPolicy.wantsVisibleContext("ele disse isso ontem"))
        assertFalse(ZevronPerceptionPolicy.wantsVisibleContext("veja isso"))
    }
}
