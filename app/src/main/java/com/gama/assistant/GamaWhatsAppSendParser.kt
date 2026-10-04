package com.gama.assistant

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

object GamaWhatsAppSendParser {
    data class Request(val recipient: String, val message: String) {
        fun toPayload(): String = JSONObject()
            .put("recipient", recipient)
            .put("message", message)
            .toString()

        fun confirmation(): String =
            "Vou enviar para $recipient: ${message.take(220)}. Posso enviar?"
    }

    fun parse(raw: String): Request? {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        val n = normalize(clean).removePrefix("gama ").trim()
        if (!Regex("\\b(envie|enviar|mande|manda|mandar|mandar uma|envia)\\b").containsMatchIn(n)) return null
        if (!Regex("\\b(mensagem|whatsapp|zap|watsap|whats)\\b").containsMatchIn(n)) return null

        val patterns = listOf(
            Regex("""(?i)(?:envie|enviar|mande|manda|mandar|envia)(?: uma)? mensagem(?: pelo| no)?(?: whatsapp| zap| watsap| whats)? para (.+?) dizendo (.+)$"""),
            Regex("""(?i)(?:envie|enviar|mande|manda|mandar|envia)(?: uma)? mensagem(?: pelo| no)?(?: whatsapp| zap| watsap| whats)? para (.+?) com a mensagem (.+)$"""),
            Regex("""(?i)(?:envie|enviar|mande|manda|mandar|envia)(?: uma)? mensagem(?: pelo| no)?(?: whatsapp| zap| watsap| whats)? para ([^:]{2,80}):\s*(.+)$"""),
            Regex("""(?i)(?:envie|mande|manda|envia)(?: no| pelo)?(?: whatsapp| zap| watsap| whats) para (.+?) dizendo (.+)$"""),
        )
        for (pattern in patterns) {
            val m = pattern.find(clean) ?: continue
            val recipient = m.groupValues.getOrNull(1).orEmpty().trim().trim(',', '.', ':')
            val message = GamaConversationRepair.latestClause(
                m.groupValues.getOrNull(2).orEmpty().trim()
            )
            if (recipient.length >= 2 && message.isNotBlank()) {
                return Request(recipient.take(120), message.take(4000))
            }
        }
        return null
    }

    fun fromPayload(payload: String): Request? = runCatching {
        val json = JSONObject(payload)
        val recipient = json.optString("recipient").trim()
        val message = json.optString("message").trim()
        if (recipient.isBlank() || message.isBlank()) null else Request(recipient, message)
    }.getOrNull()

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
