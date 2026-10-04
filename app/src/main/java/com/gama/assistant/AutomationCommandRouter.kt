package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

enum class AutomationTriggerKind {
    DAILY_TIME,
    BATTERY_BELOW,
    HEADSET_CONNECTED,
    CHARGING_STARTED
}

enum class AutomationActionKind {
    COMMAND,
    SPEAK
}

data class AutomationDraft(
    val triggerKind: AutomationTriggerKind,
    val triggerValue: String,
    val actionKind: AutomationActionKind,
    val action: String,
    val description: String
)

/**
 * Deterministic automation parser. It never asks the LLM what Android action to run.
 * A routine is only saved after explicit user confirmation in GamaService.
 */
object AutomationCommandRouter {
    private val createMarkers = listOf(
        "todo dia", "todos os dias", "diariamente", "sempre que",
        "quando eu conectar", "quando conectar", "quando eu colocar", "quando colocar",
        "quando a bateria", "quando comecar a carregar", "quando o carregamento",
        "automatize", "automatiza",
        "crie uma rotina", "criar uma rotina", "crie uma automacao", "criar uma automacao"
    )

    fun normalize(raw: String): String {
        var s = raw.lowercase(Locale("pt", "BR"))
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        return s.replace("[^a-z0-9: ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    fun looksLikeAutomationCommand(raw: String): Boolean {
        val n = normalize(raw)
        return createMarkers.any { n.startsWith(it) || n.contains(" $it") } ||
            isListRequest(n) || isPauseAll(n) || isResumeAll(n) || isDeleteAll(n) || isDeleteLast(n)
    }

    fun isListRequest(raw: String): Boolean {
        val n = normalize(raw)
        return n in setOf(
            "quais automacoes", "minhas automacoes", "liste automacoes", "listar automacoes",
            "quais rotinas", "minhas rotinas", "liste rotinas", "listar rotinas",
            "o que esta automatizado", "o que voce automatizou"
        )
    }

    fun isPauseAll(raw: String): Boolean {
        val n = normalize(raw)
        return n in setOf(
            "pause as automacoes", "pausar automacoes", "pause minhas automacoes",
            "desative as automacoes", "desativar automacoes", "pause as rotinas", "pausar rotinas"
        )
    }

    fun isResumeAll(raw: String): Boolean {
        val n = normalize(raw)
        return n in setOf(
            "retome as automacoes", "retomar automacoes", "ative as automacoes",
            "ativar automacoes", "retome as rotinas", "ativar rotinas", "ative as rotinas"
        )
    }

    fun isDeleteAll(raw: String): Boolean {
        val n = normalize(raw)
        return n in setOf(
            "apague todas as automacoes", "apagar todas as automacoes", "limpe as automacoes",
            "apague todas as rotinas", "apagar todas as rotinas", "limpe as rotinas"
        )
    }

    fun isDeleteLast(raw: String): Boolean {
        val n = normalize(raw)
        return n in setOf(
            "apague a ultima automacao", "apagar a ultima automacao", "remova a ultima automacao",
            "apague a ultima rotina", "apagar a ultima rotina", "remova a ultima rotina"
        )
    }

    fun isConfirmation(raw: String): Boolean {
        val n = normalize(raw)
        return n in setOf(
            "sim", "pode", "pode sim", "pode fazer", "pode salvar", "salve", "salva",
            "confirmo", "confirma", "ative", "ativa", "pode ativar", "pode automatizar"
        )
    }

    fun isCancellation(raw: String): Boolean {
        val n = normalize(raw)
        return n in setOf(
            "nao", "nao pode", "cancela", "cancelar", "cancele", "esquece", "deixa pra la", "deixa para la"
        )
    }

    fun parseCreate(raw: String): AutomationDraft? {
        val n = normalize(raw)
        parseDaily(n)?.let { return it }
        parseBattery(n)?.let { return it }
        parseHeadset(n)?.let { return it }
        parseCharging(n)?.let { return it }
        return null
    }

    private fun parseDaily(n: String): AutomationDraft? {
        val prefix = when {
            n.startsWith("todo dia ") -> "todo dia "
            n.startsWith("todos os dias ") -> "todos os dias "
            n.startsWith("diariamente ") -> "diariamente "
            else -> return null
        }
        var rest = n.removePrefix(prefix).trim()
        rest = rest.removePrefix("as ").removePrefix("a ").trim()
        val actionStart = findActionStart(rest) ?: return null
        val timePart = rest.substring(0, actionStart).trim().removeSuffix(" e").trim()
        val actionPart = rest.substring(actionStart).trim()
        val minutes = parseTimeOfDay(timePart) ?: return null
        val action = parseAction(actionPart) ?: return null
        val hh = minutes / 60
        val mm = minutes % 60
        val timeText = "%02d:%02d".format(Locale.US, hh, mm)
        return AutomationDraft(
            triggerKind = AutomationTriggerKind.DAILY_TIME,
            triggerValue = minutes.toString(),
            actionKind = action.first,
            action = action.second,
            description = "todos os dias às $timeText: ${actionDescription(action)}"
        )
    }

    private fun parseBattery(n: String): AutomationDraft? {
        if (!n.contains("bateria")) return null
        if (!(n.startsWith("quando ") || n.startsWith("sempre que "))) return null
        if (!(n.contains("abaixo") || n.contains("cair") || n.contains("ficar") || n.contains("chegar"))) return null
        val value = Regex("(?:abaixo de |abaixo dos |em |a )?(\\d{1,2})(?: por cento|%)?").findAll(n)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .firstOrNull { it in 1..95 } ?: return null
        val actionStart = findActionStart(n)
        val action = if (actionStart != null) parseAction(n.substring(actionStart)) else null
        val resolved = action ?: Pair(
            AutomationActionKind.SPEAK,
            "A bateria caiu abaixo de $value por cento."
        )
        return AutomationDraft(
            triggerKind = AutomationTriggerKind.BATTERY_BELOW,
            triggerValue = value.toString(),
            actionKind = resolved.first,
            action = resolved.second,
            description = "quando a bateria cair abaixo de $value%: ${actionDescription(resolved)}"
        )
    }

    private fun parseHeadset(n: String): AutomationDraft? {
        if (!(n.startsWith("quando ") || n.startsWith("sempre que "))) return null
        if (!(n.contains("fone") || n.contains("headset") || n.contains("auricular"))) return null
        if (!(n.contains("conectar") || n.contains("conecto") || n.contains("colocar") || n.contains("ligar"))) return null
        val actionStart = findActionStart(n) ?: return null
        val action = parseAction(n.substring(actionStart)) ?: return null
        return AutomationDraft(
            triggerKind = AutomationTriggerKind.HEADSET_CONNECTED,
            triggerValue = "connected",
            actionKind = action.first,
            action = action.second,
            description = "quando o fone for conectado: ${actionDescription(action)}"
        )
    }

    private fun parseCharging(n: String): AutomationDraft? {
        if (!(n.startsWith("quando ") || n.startsWith("sempre que "))) return null
        if (!(n.contains("carregar") || n.contains("carregador") || n.contains("carregando"))) return null
        if (!(n.contains("comec") || n.contains("colocar") || n.contains("conectar") || n.contains("ligar"))) return null
        val actionStart = findActionStart(n)
        val action = if (actionStart != null) parseAction(n.substring(actionStart)) else null
        val resolved = action ?: Pair(AutomationActionKind.SPEAK, "O carregador foi conectado.")
        return AutomationDraft(
            triggerKind = AutomationTriggerKind.CHARGING_STARTED,
            triggerValue = "charging",
            actionKind = resolved.first,
            action = resolved.second,
            description = "quando o carregamento começar: ${actionDescription(resolved)}"
        )
    }

    private fun findActionStart(text: String): Int? {
        val markers = listOf(
            "abra ", "abre ", "abrir ", "leia ", "ler ", "me avise", "avise ", "diga ",
            "me lembre", "lembre ", "ligue a lanterna", "acenda a lanterna", "desligue a lanterna",
            "aumente o volume", "diminua o volume", "pause ", "continue ", "retome ",
            "toque ", "reproduza ", "mostre minha agenda", "consulte minha agenda"
        )
        return markers.map { text.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
    }

    private fun parseAction(raw: String): Pair<AutomationActionKind, String>? {
        val n = normalize(raw).trim()
        if (n.isBlank()) return null

        // Consequential actions are never armed as unattended routines.
        if (n.contains("mande mensagem") || n.contains("envie mensagem") ||
            n.contains("ligue para") || n.contains("desbloque") ||
            n.contains("bloqueie a tela") || n.contains("apague ") ||
            n.contains("desinstal") || n.contains("instale ") || n.contains("compre ")) {
            return null
        }

        val reminderPrefixes = listOf("me avise que ", "me avise ", "avise que ", "avise ", "diga que ", "diga ", "me lembre de ", "me lembre ", "lembre de ", "lembre ")
        reminderPrefixes.firstOrNull { n.startsWith(it) }?.let { prefix ->
            val body = n.removePrefix(prefix).trim().take(220)
            if (body.isNotBlank()) {
                val text = if (prefix.contains("lembre")) "Lembrete: $body" else body
                return AutomationActionKind.SPEAK to text
            }
        }

        if (n.contains("minha agenda") || n.contains("meus compromissos") || n.contains("meus eventos")) {
            return AutomationActionKind.COMMAND to "minha agenda"
        }

        val allowedCommand =
            n.startsWith("abra ") || n.startsWith("abre ") || n.startsWith("abrir ") ||
            n.contains("lanterna") || n.contains("volume") ||
            n.startsWith("pause") || n.startsWith("continue") || n.startsWith("retome") ||
            n.startsWith("toque ") || n.startsWith("reproduza ") || n.contains("bateria")

        return if (allowedCommand) AutomationActionKind.COMMAND to n.take(180) else null
    }

    private fun actionDescription(action: Pair<AutomationActionKind, String>): String =
        when (action.first) {
            AutomationActionKind.SPEAK -> "dizer \"${action.second}\""
            AutomationActionKind.COMMAND -> action.second
        }

    fun parseTimeOfDay(raw: String): Int? {
        var n = normalize(raw)
        n = n.removePrefix("as ").removePrefix("a ").trim()
        Regex("^(\\d{1,2})(?::(\\d{2}))?$").matchEntire(n)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return null
            val minute = m.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
            if (h in 0..23 && minute in 0..59) return h * 60 + minute
        }

        val half = n.endsWith(" e meia")
        val quarter = n.endsWith(" e quinze")
        val fortyFive = n.endsWith(" e quarenta e cinco")
        val hourText = when {
            half -> n.removeSuffix(" e meia")
            quarter -> n.removeSuffix(" e quinze")
            fortyFive -> n.removeSuffix(" e quarenta e cinco")
            else -> n
        }.trim()
        val h = portugueseHour(hourText) ?: return null
        val minute = when {
            half -> 30
            quarter -> 15
            fortyFive -> 45
            else -> 0
        }
        return h * 60 + minute
    }

    private fun portugueseHour(text: String): Int? {
        val values = mapOf(
            "zero" to 0, "meia noite" to 0,
            "uma" to 1, "um" to 1,
            "duas" to 2, "dois" to 2,
            "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6, "sete" to 7,
            "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11, "doze" to 12,
            "meio dia" to 12, "treze" to 13, "quatorze" to 14, "catorze" to 14,
            "quinze" to 15, "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18,
            "dezenove" to 19, "vinte" to 20, "vinte e uma" to 21, "vinte e um" to 21,
            "vinte e duas" to 22, "vinte e dois" to 22, "vinte e tres" to 23
        )
        return values[text]
    }
}
