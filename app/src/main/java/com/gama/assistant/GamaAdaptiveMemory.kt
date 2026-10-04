package com.gama.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

object GamaAdaptiveMemory {
    private const val PREFS = "gama_adaptive_memory_v1"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 60

    enum class Kind { PREFERENCE, PERSON, DECISION, STYLE }

    data class Entry(
        val kind: Kind,
        val value: String,
        val normalized: String,
        val createdAt: Long,
    )

    fun observe(context: Context, raw: String) {
        val clean = ZevronConversationEngine.stripWakeWord(raw)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(420)

        if (clean.length < 5 || looksSensitive(clean)) return

        val candidates = mutableListOf<Pair<Kind, String>>()

        Regex("(?i)^(?:eu )?(prefiro|gosto de|nao gosto de|não gosto de)\\s+(.+)$")
            .find(clean)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf { it.length >= 2 }
            ?.let { candidates += Kind.PREFERENCE to clean }

        Regex("(?i)^(?:de agora em diante|daqui pra frente|decidi que|eu decidi que)\\s+(.+)$")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.length >= 3 }
            ?.let { candidates += Kind.DECISION to clean }

        Regex("(?i)^(?:quero que voce|quero que você)\\s+(sempre|nunca)\\s+(.+)$")
            .find(clean)
            ?.takeIf { it.groupValues.getOrNull(2)?.trim()?.length ?: 0 >= 3 }
            ?.let { candidates += Kind.STYLE to clean }

        Regex("(?i)^(.{2,80})\\s+(?:e|é)\\s+(?:meu|minha)\\s+(amigo|amiga|irmao|irmão|irma|irmã|mae|mãe|pai|primo|prima|colega|professor|professora)\\b.*$")
            .find(clean)
            ?.let { candidates += Kind.PERSON to clean }

        candidates.forEach { (kind, value) -> add(context, kind, value) }
    }

    fun replyForQuery(context: Context, raw: String): String? {
        val n = normalize(raw)
        val asks = n in setOf(
            "o que voce sabe sobre mim",
            "o que voce aprendeu sobre mim",
            "quais sao minhas preferencias",
            "quais minhas preferencias",
            "o que voce lembra das minhas preferencias"
        )
        if (!asks) return null

        val entries = load(context)
        if (entries.isEmpty()) return "Ainda não tenho preferências pessoais suficientes guardadas localmente."

        return buildString {
            append("Localmente eu lembro que: ")
            entries.takeLast(8).forEachIndexed { index, entry ->
                if (index > 0) append("; ")
                append(entry.value)
            }
            append('.')
        }.take(1200)
    }

    fun contextSummary(context: Context): String? {
        val entries = load(context).takeLast(12)
        if (entries.isEmpty()) return null
        return buildString {
            append("Preferências e decisões locais do usuário: ")
            append(entries.joinToString(" | ") { it.value.take(180) })
        }.take(1900)
    }

    @Synchronized
    private fun add(context: Context, kind: Kind, value: String) {
        val clean = value.replace(Regex("\\s+"), " ").trim().take(360)
        if (clean.isBlank()) return
        val n = normalize(clean)
        val current = load(context)
            .filterNot { it.normalized == n }
            .toMutableList()
        current += Entry(kind, clean, n, System.currentTimeMillis())
        save(context, current.takeLast(MAX_ENTRIES))
    }

    fun load(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val value = o.optString("value").trim()
                    if (value.isBlank()) continue
                    val kind = runCatching {
                        Kind.valueOf(o.optString("kind"))
                    }.getOrDefault(Kind.PREFERENCE)
                    add(
                        Entry(
                            kind = kind,
                            value = value,
                            normalized = o.optString("normalized").ifBlank { normalize(value) },
                            createdAt = o.optLong("createdAt", 0L),
                        )
                    )
                }
            }.takeLast(MAX_ENTRIES)
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject()
                .put("kind", e.kind.name)
                .put("value", e.value)
                .put("normalized", e.normalized)
                .put("createdAt", e.createdAt))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    private fun looksSensitive(raw: String): Boolean {
        val n = normalize(raw)
        return listOf(
            "senha", "password", "pin", "token", "cvv", "cartao",
            "cpf", "rg", "chave privada", "codigo de seguranca",
            "endereco completo", "diagnostico", "doenca", "religiao",
            "partido politico", "orientacao sexual"
        ).any { it in n }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
