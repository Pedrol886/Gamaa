package com.gama.assistant

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

object GamaSleepBriefingStore {
    private const val PREFS = "gama_sleep_briefing"
    private const val KEY_SLEEPING = "sleeping"
    private const val KEY_SLEEP_START = "sleep_start"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 300
    private const val FALLBACK_WINDOW_MS = 8L * 60L * 60L * 1000L

    data class Entry(
        val kind: String,
        val packageName: String,
        val title: String,
        val text: String,
        val time: Long,
    )

    fun startSleep(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SLEEPING, true)
            .putLong(KEY_SLEEP_START, now)
            .apply()
    }

    fun windowStart(context: Context, now: Long = System.currentTimeMillis()): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getLong(KEY_SLEEP_START, 0L)
        return if (stored in 1 until now) stored else now - FALLBACK_WINDOW_MS
    }

    fun finishSleep(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SLEEPING, false)
            .remove(KEY_SLEEP_START)
            .apply()
    }

    fun hasNotificationAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.split(':').any { raw ->
            ComponentName.unflattenFromString(raw)?.packageName == context.packageName
        }
    }

    fun captureNotification(context: Context, sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == context.packageName) return
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val big = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim()
        val normal = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val lines = extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(". ") { it.toString().trim() }
            .orEmpty()
        val text = big.ifBlank { lines.ifBlank { normal } }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (title.isBlank() && text.isBlank()) return

        val kind = classify(
            packageName = sbn.packageName,
            category = notification.category.orEmpty(),
            title = title,
            text = text,
        ) ?: return

        append(
            context,
            Entry(
                kind = kind,
                packageName = sbn.packageName,
                title = title.take(180),
                text = text.take(1200),
                time = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        )
    }

    fun entriesSince(context: Context, since: Long): List<Entry> =
        load(context).filter { it.time >= since }.sortedBy { it.time }

    fun clearThrough(context: Context, through: Long) {
        save(context, load(context).filter { it.time > through })
    }

    internal fun classify(
        packageName: String,
        category: String,
        title: String,
        text: String,
    ): String? {
        val p = packageName.lowercase(Locale.ROOT)
        val c = category.lowercase(Locale.ROOT)

        val messagePackages = listOf(
            "whatsapp", "telegram", "messenger", "instagram",
            "messages", "mms", "sms", "discord",
        )
        if (c == Notification.CATEGORY_MESSAGE || messagePackages.any { it in p }) {
            return "message"
        }

        // News are deliberately NOT collected from notifications anymore.
        // The morning briefing obtains headlines from the Internet.
        return null
    }

    private fun append(context: Context, item: Entry) {
        val all = load(context).toMutableList()
        val duplicate = all.lastOrNull()?.let {
            it.packageName == item.packageName &&
                it.title == item.title &&
                it.text == item.text &&
                kotlin.math.abs(it.time - item.time) < 4_000L
        } == true
        if (duplicate) return
        all += item
        save(context, if (all.size > MAX_ITEMS) all.takeLast(MAX_ITEMS) else all)
    }

    private fun load(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(
                        Entry(
                            kind = o.optString("k"),
                            packageName = o.optString("p"),
                            title = o.optString("t"),
                            text = o.optString("x"),
                            time = o.optLong("m"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("k", e.kind)
                    .put("p", e.packageName)
                    .put("t", e.title)
                    .put("x", e.text)
                    .put("m", e.time)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
