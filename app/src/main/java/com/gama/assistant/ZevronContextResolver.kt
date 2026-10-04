package com.gama.assistant

import java.text.Normalizer
import java.util.Locale

/**
 * Short-lived conversational state shared by Zevron's deterministic tools and
 * the local brain. Nothing here is durable personal memory; it is reset when a
 * voice session ends.
 */
object ZevronContextResolver {
    data class Snapshot(
        val lastCommand: String = "",
        val lastReply: String = "",
        val lastRecipient: String = "",
        val lastMessage: String = "",
        val lastApp: String = "",
    )

    @Volatile private var state = Snapshot()

    @Synchronized
    fun reset() {
        state = Snapshot()
    }

    @Synchronized
    fun snapshot(): Snapshot = state

    @Synchronized
    fun rememberCommand(raw: String) {
        val clean = ZevronConversationEngine.stripWakeWord(raw).trim().take(1200)
        if (clean.isBlank()) return

        var recipient = state.lastRecipient
        var message = state.lastMessage
        var app = state.lastApp

        WhatsAppCommandParser.parse(clean)?.let { request ->
            recipient = request.target
            request.message?.takeIf { it.isNotBlank() }?.let { message = it }
        }

        GamaWhatsAppSendParser.parse(clean)?.let { request ->
            recipient = request.recipient
            message = request.message
        }

        openTarget(clean)?.let { app = it }

        state = state.copy(
            lastCommand = clean,
            lastRecipient = recipient,
            lastMessage = message,
            lastApp = app,
        )
    }

    @Synchronized
    fun rememberReply(raw: String) {
        val clean = raw.trim().replace(Regex("\\s+"), " ").take(1200)
        if (clean.isBlank()) return
        state = state.copy(lastReply = clean)
    }


    /** Called when Gama proactively tells the owner that a contact is trying to reach them. */
    @Synchronized
    fun rememberExternalRecipient(recipient: String, lastMessage: String = "") {
        val who = recipient.trim().replace(Regex("\\s+"), " ").take(120)
        if (who.isBlank()) return
        val message = lastMessage.trim().replace(Regex("\\s+"), " ").take(1200)
        state = state.copy(
            lastRecipient = who,
            lastMessage = message.ifBlank { state.lastMessage },
        )
    }

    /**
     * Resolves only high-confidence conversational references. Ambiguous
     * references are intentionally left untouched for the brain to clarify.
     */
    @Synchronized
    fun resolve(raw: String): String {
        val clean = ZevronConversationEngine.stripWakeWord(raw).trim()
        if (clean.isBlank()) return clean
        val n = normalize(clean)
        val current = state

        // After a proactive contact alert, the owner can answer naturally:
        // "diz que eu tô fora" / "fala que ligo depois" without repeating the contact.
        if (current.lastRecipient.isNotBlank()) {
            val implicitReply = Regex(
                "(?i)^(?:diz|diga|fala|fale|avisa|avise|responde|responda|mande|manda)\\s+(?:pra\\s+ele\\s+|para\\s+ele\\s+|pra\\s+ela\\s+|para\\s+ela\\s+)?(?:que\\s+)?(.+?)[.!?]?$"
            ).find(clean)
            if (implicitReply != null) {
                val body = GamaConversationRepair.latestClause(implicitReply.groupValues[1])
                if (body.isNotBlank()) {
                    state = current.copy(lastMessage = body)
                    return "mande mensagem pelo whatsapp para ${current.lastRecipient} dizendo $body"
                }
            }
        }

        // "manda pra ele que chego às oito" after a recipient was established.
        if (current.lastRecipient.isNotBlank()) {
            val pronoun = Regex(
                "(?i)\\b(?:para|pra|pro|ao)\\s+(?:ele|ela|essa pessoa|esse contato)\\b"
            )
            if (pronoun.containsMatchIn(clean)) {
                return clean.replaceFirst(
                    pronoun,
                    "para ${current.lastRecipient}"
                )
            }
        }

        // "avise minha mãe também" reuses the last WhatsApp body, but only
        // when both the new recipient and previous body are explicit enough.
        if (current.lastMessage.isNotBlank()) {
            val also = Regex(
                "(?i)^(?:e\\s+)?(?:avise|avisa|mande|manda|envie|envia)\\s+(.+?)\\s+tamb[eé]m[.!?]?$"
            ).find(clean)
            if (also != null) {
                val target = also.groupValues[1].trim().trim(',', '.', ':')
                if (target.length >= 2 && normalize(target) !in setOf("ele", "ela", "isso")) {
                    return "mande mensagem pelo whatsapp para $target dizendo ${current.lastMessage}"
                }
            }
        }

        // "muda para nove" / "troca para 9" can edit a message draft that
        // was just established. This is deliberately limited to a recent
        // WhatsApp recipient + message so a vague correction never mutates an
        // unrelated command.
        if (current.lastRecipient.isNotBlank() && current.lastMessage.isNotBlank()) {
            val correction = Regex(
                "(?i)^(?:na\\s+verdade\\s+)?(?:mude|muda|troque|troca|coloque|coloca|bote|bota)\\s+(?:isso\\s+)?(?:para|pra)\\s+(.+?)[.!?]?$"
            ).find(clean)
            if (correction != null) {
                val replacement = correction.groupValues[1].trim()
                val edited = replaceLastValue(current.lastMessage, replacement)
                if (edited != null && edited != current.lastMessage) {
                    state = current.copy(lastMessage = edited)
                    return "mande mensagem pelo whatsapp para ${current.lastRecipient} dizendo $edited"
                }
            }
        }

        // "e amanhã?" repeats the same local/current-information question with
        // the day changed instead of throwing away the previous subject.
        if (n in setOf("e amanha", "amanha", "e hoje", "hoje")) {
            val previous = current.lastCommand
            if (previous.isNotBlank()) {
                val wanted = if ("amanha" in n) "amanhã" else "hoje"
                val normalizedPrevious = normalize(previous)
                return when {
                    "hoje" in normalizedPrevious -> previous.replace(
                        Regex("(?i)\\bhoje\\b"), wanted
                    )
                    "amanha" in normalizedPrevious -> previous.replace(
                        Regex("(?i)\\bamanh[ãa]\\b"), wanted
                    )
                    else -> "$previous $wanted"
                }
            }
        }

        // "faz isso amanhã" is safe only as a continuation of a concrete
        // previous command. It keeps the previous wording and adds the date.
        if (n in setOf("faz isso amanha", "faca isso amanha", "faça isso amanha")) {
            val previous = current.lastCommand
            if (previous.isNotBlank()) {
                return if ("hoje" in normalize(previous)) {
                    previous.replace(Regex("(?i)\\bhoje\\b"), "amanhã")
                } else {
                    "$previous amanhã"
                }
            }
        }

        // "abre ele" / "abre isso" can reuse only a previously opened app.
        if (current.lastApp.isNotBlank() && n in setOf(
                "abre ele", "abra ele", "abre isso", "abra isso", "abre de novo", "abra de novo"
            )
        ) {
            return "abra ${current.lastApp}"
        }

        return clean
    }

    @Synchronized
    fun brainContext(): String? {
        val s = state
        if (s == Snapshot()) return null
        return buildString {
            if (s.lastCommand.isNotBlank()) append("Último pedido: ${s.lastCommand.take(420)}.")
            if (s.lastReply.isNotBlank()) append(" Última resposta: ${s.lastReply.take(420)}.")
            if (s.lastRecipient.isNotBlank()) append(" Último destinatário mencionado: ${s.lastRecipient.take(100)}.")
            if (s.lastMessage.isNotBlank()) append(" Última mensagem associada: ${s.lastMessage.take(260)}.")
            if (s.lastApp.isNotBlank()) append(" Último aplicativo mencionado: ${s.lastApp.take(100)}.")
        }.trim().takeIf { it.isNotBlank() }
    }

    private fun replaceLastValue(message: String, replacement: String): String? {
        val cleanReplacement = replacement.trim().trim(',', '.', '!', '?')
        if (cleanReplacement.isBlank()) return null

        val numberWords = "(?:zero|um|uma|dois|duas|tres|três|quatro|cinco|seis|sete|oito|nove|dez|onze|doze|treze|quatorze|catorze|quinze|dezesseis|dezessete|dezoito|dezenove|vinte)"
        val patterns = listOf(
            Regex("(?i)\\b(?:às|as)\\s+(?:\\d{1,2}(?::\\d{2})?|$numberWords)\\b"),
            Regex("(?i)\\b\\d{1,2}(?::\\d{2})?\\b"),
            Regex("(?i)\\b$numberWords\\b"),
        )

        for (pattern in patterns) {
            val matches = pattern.findAll(message).toList()
            val last = matches.lastOrNull() ?: continue
            val prefix = if (last.value.trimStart().lowercase(Locale.ROOT).startsWith("as ") ||
                last.value.trimStart().lowercase(Locale.ROOT).startsWith("às ")) {
                last.value.substringBeforeLast(' ') + " "
            } else {
                ""
            }
            val value = prefix + cleanReplacement
            return message.replaceRange(last.range, value)
        }
        return null
    }

    private fun openTarget(raw: String): String? {
        val n = normalize(raw)
        val match = Regex(
            "^(?:abra|abre|abrir|inicie|inicia|iniciar|execute|executa)\\s+(?:o\\s+|a\\s+)?(?:app\\s+|aplicativo\\s+)?(.+)$"
        ).find(n) ?: return null
        return match.groupValues[1].trim().takeIf { it.length >= 2 }?.take(100)
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
