package com.gama.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable mission memory for multi-step work.
 * A user interruption pauses the remaining steps instead of erasing them.
 * Nothing executes itself after a restart; the user can say "continue de onde parou".
 */
object GamaMissionStore {
    private const val PREFS = "gama_mission_store"
    private const val KEY = "mission"
    private const val MAX_AGE_MS = 72L * 60L * 60L * 1000L

    data class Mission(
        val goal: String,
        val remaining: List<String>,
        val lastStep: String = "",
        val lastResult: String = "",
        val updatedAt: Long = System.currentTimeMillis(),
        val paused: Boolean = false,
    )

    fun start(context: Context, goal: String, steps: List<String>) {
        val clean = steps.map { it.trim() }.filter { it.isNotBlank() }.take(12)
        if (clean.isEmpty()) return
        save(context, Mission(goal.trim().take(900), clean))
    }

    fun advance(context: Context, step: String, remaining: List<String>) {
        val current = load(context) ?: Mission(step, emptyList())
        save(
            context,
            current.copy(
                remaining = remaining.map { it.trim() }.filter { it.isNotBlank() }.take(12),
                lastStep = step.trim().take(500),
                updatedAt = System.currentTimeMillis(),
                paused = false,
            )
        )
    }

    fun pause(context: Context, remaining: List<String>) {
        val current = load(context) ?: return
        if (remaining.isEmpty()) return
        save(
            context,
            current.copy(
                remaining = remaining.map { it.trim() }.filter { it.isNotBlank() }.take(12),
                updatedAt = System.currentTimeMillis(),
                paused = true,
            )
        )
    }

    fun rememberResult(context: Context, result: String) {
        val current = load(context) ?: return
        val clean = result.trim().replace(Regex("\\s+"), " ").take(700)
        if (clean.isBlank()) return
        save(context, current.copy(lastResult = clean, updatedAt = System.currentTimeMillis()))
    }

    fun complete(context: Context) {
        val current = load(context) ?: return
        save(context, current.copy(remaining = emptyList(), paused = false, updatedAt = System.currentTimeMillis()))
    }

    fun cancel(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun resumable(context: Context, now: Long = System.currentTimeMillis()): Mission? {
        val mission = load(context) ?: return null
        if (now - mission.updatedAt > MAX_AGE_MS || mission.remaining.isEmpty()) return null
        return mission
    }

    fun contextSummary(context: Context): String? {
        val m = load(context) ?: return null
        if (System.currentTimeMillis() - m.updatedAt > MAX_AGE_MS) return null
        return buildString {
            append("Missão atual: ").append(m.goal)
            if (m.lastStep.isNotBlank()) append(". Última etapa: ").append(m.lastStep)
            if (m.lastResult.isNotBlank()) append(". Último resultado verificado: ").append(m.lastResult)
            if (m.remaining.isNotEmpty()) {
                append(". Etapas restantes: ")
                append(m.remaining.take(4).joinToString(" | "))
            }
            if (m.paused) append(". Missão pausada por uma interrupção do usuário")
        }.take(2200)
    }

    private fun load(context: Context): Mission? {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null)
                ?: return null
            val o = JSONObject(raw)
            val arr = o.optJSONArray("remaining") ?: JSONArray()
            val remaining = buildList {
                for (i in 0 until arr.length()) {
                    arr.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            Mission(
                goal = o.optString("goal"),
                remaining = remaining,
                lastStep = o.optString("lastStep"),
                lastResult = o.optString("lastResult"),
                updatedAt = o.optLong("updatedAt"),
                paused = o.optBoolean("paused"),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun save(context: Context, mission: Mission) {
        val arr = JSONArray().apply { mission.remaining.forEach { put(it) } }
        val o = JSONObject()
            .put("goal", mission.goal)
            .put("remaining", arr)
            .put("lastStep", mission.lastStep)
            .put("lastResult", mission.lastResult)
            .put("updatedAt", mission.updatedAt)
            .put("paused", mission.paused)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }
}
