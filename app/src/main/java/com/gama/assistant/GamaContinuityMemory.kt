package com.gama.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Short durable conversational continuity. It is intentionally small, local-only
 * and refuses obvious secrets/sensitive profile data. This is not an unlimited log.
 */
object GamaContinuityMemory {
    enum class Role { USER, ASSISTANT, ACTION }

    data class Turn(
        val role: Role,
        val text: String,
        val at: Long,
    )

    private const val PREFS = "gama_continuity_memory_v1"
    private const val KEY = "turns"
    private const val MAX_TURNS = 36
    private const val MAX_AGE_MS = 72L * 60L * 60L * 1000L

    fun recordUser(context: Context, raw: String) = record(context, Role.USER, raw)
    fun recordAssistant(context: Context, raw: String) = record(context, Role.ASSISTANT, raw)

    fun recordAction(context: Context, goal: String, result: String) {
        val cleanGoal = clean(goal, 220)
        val cleanResult = clean(result, 320)
        if (cleanGoal.isBlank() && cleanResult.isBlank()) return
        record(context, Role.ACTION, "${cleanGoal.ifBlank { "Ação" }} -> $cleanResult")
    }

    fun replyForQuery(context: Context, raw: String): String? {
        val n = normalize(raw)
        val asks = n in setOf(
            "onde paramos", "onde a gente parou", "o que eu estava fazendo",
            "o que eu tava fazendo", "qual foi a ultima coisa", "o que aconteceu por ultimo",
            "retome o contexto", "lembra onde paramos"
        )
        if (!asks) return null

        val recent = recent(context, 8)
        if (recent.isEmpty()) return "Não tenho contexto recente suficiente salvo para retomar."

        return buildString {
            append("O contexto recente que tenho localmente é: ")
            recent.takeLast(6).forEachIndexed { index, turn ->
                if (index > 0) append("; ")
                append(
                    when (turn.role) {
                        Role.USER -> "você pediu ${turn.text}"
                        Role.ASSISTANT -> "eu respondi ${turn.text}"
                        Role.ACTION -> turn.text
                    }
                )
            }
            append('.')
        }.take(1500)
    }

    fun contextSummary(context: Context): String? {
        val items = recent(context, 10)
        if (items.isEmpty()) return null
        return buildString {
            append("Continuidade local recente: ")
            items.forEachIndexed { index, turn ->
                if (index > 0) append(" | ")
                append(
                    when (turn.role) {
                        Role.USER -> "usuário: "
                        Role.ASSISTANT -> "Gama: "
                        Role.ACTION -> "ação: "
                    }
                )
                append(turn.text.take(260))
            }
        }.take(2600)
    }

    internal fun shouldRemember(raw: String): Boolean {
        val clean = raw.trim()
        return clean.length >= 2 && !looksSensitive(clean)
    }

    @Synchronized
    private fun record(context: Context, role: Role, raw: String) {
        if (!shouldRemember(raw)) return
        val value = clean(raw, if (role == Role.ACTION) 520 else 360)
        if (value.isBlank()) return
        val now = System.currentTimeMillis()
        val items = load(context)
            .filter { now - it.at <= MAX_AGE_MS }
            .toMutableList()
        if (items.lastOrNull()?.role == role && items.lastOrNull()?.text == value) return
        items += Turn(role, value, now)
        save(context, items.takeLast(MAX_TURNS))
    }

    private fun recent(context: Context, limit: Int): List<Turn> {
        val now = System.currentTimeMillis()
        return load(context)
            .filter { now - it.at <= MAX_AGE_MS }
            .takeLast(limit)
    }

    private fun load(context: Context): List<Turn> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text").trim()
                if (text.isBlank()) continue
                val role = runCatching { Role.valueOf(o.optString("role")) }
                    .getOrDefault(Role.USER)
                add(Turn(role, text, o.optLong("at", 0L)))
            }
        }.takeLast(MAX_TURNS)
    }.getOrDefault(emptyList())

    private fun save(context: Context, items: List<Turn>) {
        val arr = JSONArray()
        items.forEach { turn ->
            arr.put(
                JSONObject()
                    .put("role", turn.role.name)
                    .put("text", turn.text)
                    .put("at", turn.at)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun clean(value: String, max: Int): String =
        value.replace(Regex("\\s+"), " ").trim().take(max)

    private fun looksSensitive(raw: String): Boolean {
        val n = normalize(raw)
        return listOf(
            "senha", "password", "meu pin", "codigo pin", "token", "cvv",
            "numero do cartao", "cartao de credito", "chave privada", "codigo de seguranca",
            "cpf", "rg", "diagnostico medico", "minha doenca", "minha religiao",
            "meu partido", "orientacao sexual"
        ).any { it in n }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
