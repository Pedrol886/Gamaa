package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

/** Pure policy gate: local/private Android commands never fall through to Gemma. */
object LocalCommandIsolationPolicy {
    enum class Kind {
        NONE, MESSAGE, TIME, BATTERY, NOTIFICATION, CALENDAR, APP, WHATSAPP,
        AUTOMATION, DIAGNOSTIC, SETTINGS, CAMERA, FLASHLIGHT, MEDIA, LOCK
    }

    fun kind(raw: String): Kind {
        val c = normalize(raw)
        if (c.isBlank()) return Kind.NONE
        if (MessageCommandRouter.isReadRequest(c)) {
            return if (MessageCommandRouter.isNotificationRequest(c)) Kind.NOTIFICATION else Kind.MESSAGE
        }
        if (Regex("\\b(que horas|qual a hora|horas sao|hora agora)\\b").containsMatchIn(c)) return Kind.TIME
        if (Regex("\\b(minha bateria|nivel da bateria|porcentagem da bateria|quanto de bateria|como esta a bateria)\\b").containsMatchIn(c)) return Kind.BATTERY
        if (Regex("\\b(agenda|calendario|compromisso|evento)\\b").containsMatchIn(c) && Regex("\\b(minha|abrir|abra|ler|leia|criar|crie|marcar|marque|adicionar|adicione|hoje|amanha)\\b").containsMatchIn(c)) return Kind.CALENDAR
        if (Regex("\\b(whatsapp|zap zap|zap|watsap|uatsap|uatsape|watsape|whats)\\b").containsMatchIn(c)) return Kind.WHATSAPP
        if (Regex("\\b(todo dia|todos os dias|quando conectar|quando comecar a carregar|bateria ficar abaixo|rotina|automacao|automatize)\\b").containsMatchIn(c)) return Kind.AUTOMATION
        if (Regex("\\b(diagnostico|diagnosticar)\\b").containsMatchIn(c)) return Kind.DIAGNOSTIC
        if (Regex("\\b(configuracao|configuracoes|ajustes)\\b").containsMatchIn(c)) return Kind.SETTINGS
        if (Regex("\\b(camera|foto)\\b").containsMatchIn(c) && Regex("\\b(abra|abrir|ligue|tirar|tire)\\b").containsMatchIn(c)) return Kind.CAMERA
        if (Regex("\\b(lanterna)\\b").containsMatchIn(c)) return Kind.FLASHLIGHT
        if (Regex("\\b(pause|pausar|continue|continuar|proxima|anterior|musica|midia|volume)\\b").containsMatchIn(c)) return Kind.MEDIA
        if (Regex("\\b(bloqueie|bloquear|bloqueia)\\b.*\\b(tela|celular|aparelho)\\b").containsMatchIn(c)) return Kind.LOCK
        if (Regex("\\b(abra|abrir|abre)\\b\\s+.+").containsMatchIn(c)) return Kind.APP
        return Kind.NONE
    }

    fun mustNeverUseBrain(raw: String): Boolean = kind(raw) != Kind.NONE

    fun isMessageRead(raw: String): Boolean = kind(raw) == Kind.MESSAGE || kind(raw) == Kind.NOTIFICATION

    private fun normalize(text: String): String {
        val noMarks = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return noMarks.replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}
