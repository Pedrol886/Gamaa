package com.gama.assistant

/**
 * Deterministic routing for notification/message queries.
 *
 * The local AI must never decide whether the user asked to read notifications.
 * Vosk may turn "tenho" into "tinha", "tem" or omit a short word, so the
 * classifier intentionally accepts several natural Portuguese variants while
 * avoiding generic questions such as "como funciona uma notificação?".
 */
object MessageCommandRouter {
    private val messageNouns = listOf(
        "mensagem", "mensagens", "notificacao", "notificacoes", "notificac"
    )

    private val readSignals = listOf(
        "tenho", "tinha", "tem ", "ha ", "há ", "alguma", "algum", "quais",
        "minha", "minhas", "meu", "meus", "recebi", "recebeu", "chegou",
        "chegaram", "nova", "novas", "novo", "novos", "ler", "leia",
        "mostrar", "mostre", "ver ", "veja", "pendente", "pendentes",
        "diga", "fale", "liste", "listar", "conte", "quero saber"
    )

    private val informationalSignals = listOf(
        "como funciona", "o que e", "o que é", "explique", "significa",
        "para que serve", "porque existe", "por que existe"
    )

    fun isNotificationRequest(raw: String): Boolean {
        val c = VoicePolicy.normalize(raw)
        return isReadRequest(c) && (c.contains("notificacao") || c.contains("notificacoes") || c.contains("notificac"))
    }

    fun isMessageRequest(raw: String): Boolean =
        isReadRequest(raw) && !isNotificationRequest(raw)

    fun isReadRequest(raw: String): Boolean {
        val c = VoicePolicy.normalize(raw)
        if (c.isBlank()) return false
        val hasNoun = messageNouns.any { c.contains(it) }
        if (!hasNoun) return false
        if (informationalSignals.any { c.contains(it) }) return false

        if (c in setOf(
                "mensagem", "mensagens", "notificacao", "notificacoes",
                "minhas mensagens", "minhas notificacoes"
            )) return true

        return readSignals.any { c.contains(it) }
    }
}
