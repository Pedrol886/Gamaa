package com.gama.assistant

import org.junit.Assert.*
import org.junit.Test

class GamaIdentityTest {
    @Test fun creatorAndOwnerAreDistinct() {
        assertEquals(IdentityIntent.Kind.CREATOR, IdentityIntent.kind("Quem criou você?"))
        assertEquals(IdentityIntent.Kind.OWNER, IdentityIntent.kind("Quem é seu dono?"))
        assertNotNull(IdentityIntent.publicReply("Quem criou o Gama?"))
        assertNull(IdentityIntent.publicReply("Quem é seu dono?"))
        assertFalse(IdentityIntent.requiresDeviceProof("Quem criou você?"))
        assertTrue(IdentityIntent.requiresDeviceProof("Quem é seu dono?"))
    }
    @Test fun sensitiveDataIsNeverPublicByDefault() {
        listOf("minha agenda", "meus compromissos", "resumo do dia",
            "leia as mensagens", "o que você lembra de mim", "lembre que gosto de café",
            "minhas notas", "qual meu nome", "apague minha memória")
            .forEach { assertTrue("Falhou: $it", IdentityIntent.requiresDeviceProof(it)) }
        assertTrue(IdentityIntent.requiresDeviceProof("Qual é meu nome?"))
        assertTrue(IdentityIntent.requiresDeviceProof("Tem algo marcado para hoje?"))
        assertTrue(IdentityIntent.requiresDeviceProof("Tenho algo importante por esses dias?"))
        assertTrue(IdentityIntent.requiresDeviceProof("Mande mensagem para Joao dizendo chego em dez minutos"))
        assertFalse(IdentityIntent.requiresDeviceProof("Abra a calculadora"))
        assertFalse(IdentityIntent.requiresDeviceProof("Como está o tempo em Aracaju?"))
    }
}
