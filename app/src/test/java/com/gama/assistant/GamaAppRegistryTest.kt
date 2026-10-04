package com.gama.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamaAppRegistryTest {
    @Test fun whatsappAliasesResolveToOneCanonicalApp() {
        listOf(
            "abra o whatsapp",
            "abre o zap",
            "entre no uatsap",
            "Gama, abre watsape",
            "whatsapp"
        ).forEach { command ->
            assertEquals("whatsapp", GamaAppRegistry.resolveOpenRequest(command)?.id)
        }
    }

    @Test fun whatsappMessageNeverBecomesOpenApp() {
        assertNull(GamaAppRegistry.resolveOpenRequest("mande uma mensagem pelo whatsapp para Joao"))
        assertNull(GamaAppRegistry.resolveOpenRequest("envie no zap para Maria dizendo oi"))
    }

    @Test fun questionsAboutWhatsappDoNotOpenIt() {
        assertNull(GamaAppRegistry.resolveOpenRequest("como funciona o whatsapp"))
        assertNull(GamaAppRegistry.resolveOpenRequest("como abrir o whatsapp"))
    }
}
