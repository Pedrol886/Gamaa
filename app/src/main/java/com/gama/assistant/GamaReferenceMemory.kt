package com.gama.assistant

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/**
 * Small durable reference memory for natural follow-ups across voice sessions.
 * It stores only the latest app/contact/message reference and never executes anything by itself.
 */
object GamaReferenceMemory {
    private const val PREFS = "gama_reference_memory_v1"
    private const val APP = "app"
    private const val RECIPIENT = "recipient"
    private const val UPDATED = "updated"
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    fun observe(context: Context, raw: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        var changed = false

        GamaAppRegistry.resolveOpenRequest(raw)?.let { app ->
            edit.putString(APP, app.label)
            changed = true
        }

        GamaWhatsAppSendParser.parse(raw)?.let { request ->
            edit.putString(RECIPIENT, request.recipient)
            changed = true
        }

        WhatsAppCommandParser.parse(raw)?.let { request ->
            edit.putString(RECIPIENT, request.target)
            changed = true
        }

        if (changed) edit.putLong(UPDATED, System.currentTimeMillis()).apply()
    }

    fun resolve(context: Context, raw: String): String {
        val clean = ZevronConversationEngine.stripWakeWord(raw).trim()
        if (clean.isBlank()) return clean
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = prefs.getLong(UPDATED, 0L)
        if (updated <= 0L || System.currentTimeMillis() - updated > MAX_AGE_MS) return clean

        val app = prefs.getString(APP, "").orEmpty().trim()
        val recipient = prefs.getString(RECIPIENT, "").orEmpty().trim()
        val n = normalize(clean)

        if (app.isNotBlank() && n in setOf(
                "abre ele", "abra ele", "abre isso", "abra isso", "abre de novo", "abra de novo",
                "volte para ele", "volta para ele"
            )) {
            return "abra $app"
        }

        if (recipient.isNotBlank()) {
            val implicit = Regex(
                "(?i)^(?:diz|diga|fala|fale|avisa|avise|responde|responda|mande|manda)\\s+(?:pra\\s+ele\\s+|para\\s+ele\\s+|pra\\s+ela\\s+|para\\s+ela\\s+)?(?:que\\s+)?(.+?)[.!?]?$"
            ).find(clean)
            if (implicit != null) {
                val body = GamaConversationRepair.latestClause(implicit.groupValues[1])
                if (body.isNotBlank()) return "mande mensagem pelo whatsapp para $recipient dizendo $body"
            }

            val pronoun = Regex("(?i)\\b(?:para|pra|pro|ao)\\s+(?:ele|ela|essa pessoa|esse contato)\\b")
            if (pronoun.containsMatchIn(clean)) {
                return clean.replaceFirst(pronoun, "para $recipient")
            }
        }

        return clean
    }

    fun contextSummary(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = prefs.getLong(UPDATED, 0L)
        if (updated <= 0L || System.currentTimeMillis() - updated > MAX_AGE_MS) return null
        val app = prefs.getString(APP, "").orEmpty().trim()
        val recipient = prefs.getString(RECIPIENT, "").orEmpty().trim()
        if (app.isBlank() && recipient.isBlank()) return null
        return buildString {
            if (app.isNotBlank()) append("Último app referenciado: $app.")
            if (recipient.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append("Último contato referenciado: $recipient.")
            }
        }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
