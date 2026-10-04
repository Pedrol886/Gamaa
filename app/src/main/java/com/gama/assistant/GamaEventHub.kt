package com.gama.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local event memory used to fuse notifications and verified actions into conversation context. */
object GamaEventHub {
    enum class Kind { MESSAGE, ACTION, SYSTEM }
    enum class Priority { LOW, NORMAL, HIGH }

    data class Event(
        val kind: Kind,
        val source: String,
        val summary: String,
        val priority: Priority,
        val occurredAt: Long = System.currentTimeMillis(),
    )

    private const val PREFS = "gama_event_hub"
    private const val KEY = "events"
    private const val MAX_EVENTS = 40

    @Synchronized
    fun record(context: Context, event: Event) {
        val items = recent(context, Long.MAX_VALUE, MAX_EVENTS - 1).toMutableList()
        items.add(0, event.copy(summary = event.summary.replace(Regex("\\s+"), " ").trim().take(360)))
        save(context, items.take(MAX_EVENTS))
        GamaProactiveBridge.listener?.invoke(event)
    }

    fun recent(context: Context, maxAgeMs: Long = 30L * 60L * 1000L, limit: Int = 8): List<Event> {
        val now = System.currentTimeMillis()
        return load(context)
            .filter { maxAgeMs == Long.MAX_VALUE || now - it.occurredAt <= maxAgeMs }
            .take(limit)
    }

    fun recentFromSource(context: Context, source: String, maxAgeMs: Long): Int {
        val wanted = source.trim().lowercase()
        return recent(context, maxAgeMs, MAX_EVENTS).count { it.source.trim().lowercase() == wanted }
    }

    fun contextSummary(context: Context): String? {
        val items = recent(context, 20L * 60L * 1000L, 6)
        if (items.isEmpty()) return null
        return buildString {
            append("Eventos recentes do aparelho: ")
            append(items.joinToString(" | ") { e ->
                val p = if (e.priority == Priority.HIGH) "importante" else "normal"
                "${e.kind.name.lowercase()}[$p] ${e.source}: ${e.summary}"
            })
        }.take(1800)
    }

    private fun load(context: Context): List<Event> = try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.SYSTEM)
                val priority = runCatching { Priority.valueOf(o.optString("priority")) }.getOrDefault(Priority.NORMAL)
                add(Event(kind, o.optString("source").take(120), o.optString("summary").take(360), priority, o.optLong("at")))
            }
        }.sortedByDescending { it.occurredAt }.take(MAX_EVENTS)
    } catch (_: Exception) { emptyList() }

    private fun save(context: Context, items: List<Event>) {
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(JSONObject().apply {
                put("kind", e.kind.name); put("source", e.source); put("summary", e.summary)
                put("priority", e.priority.name); put("at", e.occurredAt)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

object GamaProactiveBridge {
    @Volatile var listener: ((GamaEventHub.Event) -> Unit)? = null
}
