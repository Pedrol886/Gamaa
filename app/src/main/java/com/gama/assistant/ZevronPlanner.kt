package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

object ZevronPlanner {
    data class Plan(val steps: List<String>) {
        val size: Int get() = steps.size
    }

    private val separators = Regex(
        "(?i)\\s*(?:,|;)?\\s+(?:e\\s+depois|depois\\s+disso|depois|em\\s+seguida|" +
            "logo\\s+depois|na\\s+sequencia|na\\s+sequência|e\\s+entao|e\\s+então|" +
            "e\\s+ai|e\\s+aí|ai|aí|entao|então|por\\s+ultimo|por\\s+último|" +
            "finalmente|quando\\s+terminar|quando\\s+acabar)\\s+"
    )

    private val punctuation = Regex("\\s*[,;]\\s*")

    private val actionVerbs = setOf(
        "abra", "abre", "abrir", "inicie", "inicia", "iniciar",
        "mande", "manda", "envie", "envia", "enviar",
        "marque", "marca", "agende", "agenda", "adicione", "adiciona",
        "ligue", "liga", "desligue", "desliga", "acenda", "apague",
        "aumente", "aumenta", "diminua", "diminui",
        "pause", "pausa", "continue", "continua", "retome", "retoma",
        "leia", "le", "resuma", "resumo", "analise", "analisa",
        "pesquise", "pesquisa", "procure", "busque", "diga", "fale", "liste",
        "volte", "volta", "role", "rola", "toque", "reproduza",
        "bloqueie", "bloqueia", "trave", "trava",
        "descanse", "descansa", "descansar", "pare", "encerre", "encerra"
    )

    fun plan(raw: String): Plan? {
        var command = ZevronConversationEngine.stripWakeWord(raw).trim()
        command = command.replace(Regex("(?i)^primeiro\\s+"), "")
        if (command.length < 8 || command.length > 1400) return null

        val explicit = separators.containsMatchIn(command)
        val punctuated = command.contains(',') || command.contains(';')
        if (!explicit && !punctuated) return null

        val firstPass = separators.split(command)
            .map(::cleanStep)
            .filter(String::isNotBlank)

        val parts = if (firstPass.size >= 2) {
            firstPass
        } else {
            punctuation.split(command)
                .map(::cleanStep)
                .filter(String::isNotBlank)
        }

        if (parts.size !in 2..12) return null
        if (parts.any { it.length > 260 }) return null
        if (!parts.all(::looksLikeAction)) return null

        return Plan(parts)
    }

    internal fun looksLikeAction(raw: String): Boolean {
        val c = normalize(raw)
        if (c.isBlank()) return false
        val first = c.substringBefore(' ')
        if (first in actionVerbs) return true
        if (ConversationSession.isGoodbye(c)) return true
        if (GamaCriticalCommandPolicy.isLockScreen(c)) return true
        if (WhatsAppCommandParser.looksLikeSendCommand(c)) return true
        if (CommandRouter.looksLikeOpenRequest(c)) return true
        if (CalendarCommandRouter.looksLikeCreateRequest(c)) return true
        if (DeviceExtras.known(c)) return true
        if (ZevronSystemControl.recognizes(c)) return true
        if (SmartFeatures.isLocal(c) || SmartFeatures.isCalendar(c)) return true
        if (GamaVisionCore.recognizes(c)) return true
        if (MessageCommandRouter.isReadRequest(c)) return true
        return false
    }

    private fun cleanStep(value: String): String =
        value.trim()
            .trim(',', ';', '.', ' ')
            .replace(Regex("(?i)^primeiro\\s+"), "")
            .replace(Regex("(?i)^por\\s+fim\\s+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
