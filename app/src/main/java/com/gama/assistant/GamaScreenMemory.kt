package com.gama.assistant

import android.app.KeyguardManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Stores only coarse unlocked-screen transitions so references survive a voice-session reset. */
object GamaScreenMemory {
    data class Screen(val packageName: String, val title: String, val at: Long)

    private const val PREFS = "gama_screen_memory_v1"
    private const val KEY = "screens"
    private const val MAX_ITEMS = 10
    private const val MAX_AGE_MS = 6L * 60L * 60L * 1000L
    private const val SAME_APP_MIN_WRITE_MS = 5_000L

    @Volatile private var lastObservedPackage = ""
    @Volatile private var lastObservedKey = ""
    @Volatile private var lastObservedAt = 0L

    @Synchronized
    fun observe(context: Context, snapshot: GamaScreenSnapshot) {
        if (snapshot.packageName.isBlank()) return
        val locked = runCatching {
            context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        }.getOrDefault(true)
        if (locked) return

        val cleanTitle = snapshot.title.replace(Regex("\\s+"), " ").trim().take(120)
        val now = System.currentTimeMillis()
        val key = "${snapshot.packageName}|$cleanTitle"
        if (key == lastObservedKey) return
        if (snapshot.packageName == lastObservedPackage && now - lastObservedAt < SAME_APP_MIN_WRITE_MS) return

        val items = load(context)
            .filter { now - it.at <= MAX_AGE_MS }
            .toMutableList()
        val last = items.lastOrNull()
        if (last?.packageName == snapshot.packageName && last.title == cleanTitle) return
        items += Screen(snapshot.packageName.take(160), cleanTitle, now)
        save(context, items.takeLast(MAX_ITEMS))
        lastObservedPackage = snapshot.packageName
        lastObservedKey = key
        lastObservedAt = now
    }

    fun contextSummary(context: Context): String? {
        val items = recent(context, 5)
        if (items.isEmpty()) return null
        return buildString {
            append("Histórico recente de telas desbloqueadas: ")
            items.forEachIndexed { index, screen ->
                if (index > 0) append(" -> ")
                append(screen.packageName)
                if (screen.title.isNotBlank()) append(" [${screen.title.take(80)}]")
            }
        }.take(1200)
    }

    fun replyForQuery(context: Context, raw: String): String? {
        val n = normalize(raw)
        if (n !in setOf(
                "em que app eu estava", "qual app eu estava usando", "qual aplicativo eu estava usando",
                "o que eu estava vendo antes", "o que eu tava vendo antes", "onde eu estava no celular"
            )) return null
        val last = recent(context, 2).lastOrNull()
            ?: return "Não tenho uma tela recente suficiente para responder isso."
        val label = runCatching {
            val info = context.packageManager.getApplicationInfo(last.packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(last.packageName)
        return if (last.title.isBlank()) {
            "A última tela que registrei estava no $label."
        } else {
            "A última tela que registrei estava no $label, em ${last.title}."
        }
    }

    private fun recent(context: Context, limit: Int): List<Screen> {
        val now = System.currentTimeMillis()
        return load(context).filter { now - it.at <= MAX_AGE_MS }.takeLast(limit)
    }

    private fun load(context: Context): List<Screen> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pkg = o.optString("packageName").trim()
                if (pkg.isBlank()) continue
                add(Screen(pkg, o.optString("title").trim(), o.optLong("at", 0L)))
            }
        }.takeLast(MAX_ITEMS)
    }.getOrDefault(emptyList())

    private fun save(context: Context, items: List<Screen>) {
        val arr = JSONArray()
        items.forEach { screen ->
            arr.put(
                JSONObject()
                    .put("packageName", screen.packageName)
                    .put("title", screen.title)
                    .put("at", screen.at)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
