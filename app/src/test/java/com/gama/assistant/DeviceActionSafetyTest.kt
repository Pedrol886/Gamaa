package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionSafetyTest {
    @Test fun recognizesUnresolvedDeviceActions() {
        assertTrue(DeviceActionSafety.looksLikeUnresolvedAction("abra alguma coisa"))
        assertTrue(DeviceActionSafety.looksLikeUnresolvedAction("mande isso para ele"))
        assertTrue(DeviceActionSafety.looksLikeUnresolvedAction("marque isso na agenda"))
    }

    @Test fun leavesNormalQuestionsForConversation() {
        assertFalse(DeviceActionSafety.looksLikeUnresolvedAction("como funciona o whatsapp"))
        assertFalse(DeviceActionSafety.looksLikeUnresolvedAction("por que o ceu e azul"))
    }
}
