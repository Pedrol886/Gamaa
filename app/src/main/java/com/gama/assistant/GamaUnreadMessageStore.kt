package com.gama.assistant

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Local-only unread inbox derived from currently posted message notifications.
 * A message disappears when its notification is removed, and messages read aloud
 * by Gama are marked seen so they are not repeated.
 */
object GamaUnreadMessageStore {
    private const val PREFS = "gama_unread_messages"
    private const val KEY_UNREAD = "unread"
    private const val KEY_SEEN = "seen"
    private const val MAX_UNREAD = 120
    private const val MAX_SEEN = 400

    data class Item(
        val notificationKey: String,
        val signature: String,
        val packageName: String,
        val title: String,
        val text: String,
        val time: Long,
    )

    fun capture(context: Context, sbn: StatusBarNotification?) {
        val item = itemFrom(context, sbn) ?: return
        val seen = loadSeen(context)
        if (item.signature in seen) return

        val all = loadUnread(context).toMutableList()
        all.removeAll { it.notificationKey == item.notificationKey }
        all += item
        saveUnread(context, all.sortedBy { it.time }.takeLast(MAX_UNREAD))
    }

    fun remove(context: Context, sbn: StatusBarNotification?) {
        val key = sbn?.key.orEmpty()
        if (key.isBlank()) return
        val remaining = loadUnread(context).filterNot { it.notificationKey == key }
        saveUnread(context, remaining)
    }

    fun syncFromActive(context: Context, active: Array<StatusBarNotification>?) {
        val activeKeys = active.orEmpty().map { it.key }.toSet()
        val retained = loadUnread(context).filter { it.notificationKey in activeKeys }
        saveUnread(context, retained)
        active.orEmpty().forEach { capture(context, it) }
    }

    /** Snapshot for briefings. Does not mark anything as seen. */
    fun peekUnread(context: Context): List<Item> = loadUnread(context).sortedBy { it.time }

    /** Marks only unseen messages from the requested time window as heard. */
    fun consumeSince(context: Context, since: Long): List<Item> {
        val all = loadUnread(context).sortedBy { it.time }
        val selected = all.filter { it.time >= since }
        if (selected.isEmpty()) return emptyList()
        val seen = loadSeen(context).toMutableList()
        selected.forEach { item ->
            seen.remove(item.signature)
            seen += item.signature
        }
        saveSeen(context, seen.takeLast(MAX_SEEN))
        saveUnread(context, all.filter { it.time < since })
        return selected
    }

    /** Returns only still-unseen messages and marks this exact content as seen. */
    fun consumeUnread(context: Context): List<Item> {
        val unread = loadUnread(context).sortedBy { it.time }
        if (unread.isEmpty()) return emptyList()

        val seen = loadSeen(context).toMutableList()
        unread.forEach { item ->
            seen.remove(item.signature)
            seen += item.signature
        }
        saveSeen(context, seen.takeLast(MAX_SEEN))
        saveUnread(context, emptyList())
        return unread
    }

    private fun itemFrom(context: Context, sbn: StatusBarNotification?): Item? {
        if (sbn == null || sbn.packageName == context.packageName) return null
        val notification = sbn.notification ?: return null
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return null

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
        if (title.isBlank() && text.isBlank()) return null
        if (!isMessage(notification, sbn.packageName)) return null

        val key = sbn.key.ifBlank { "${sbn.packageName}|${sbn.id}|${sbn.tag.orEmpty()}" }
        val normalizedTitle = title.take(180)
        val normalizedText = text.take(1200)
        val signature = "$key|$normalizedTitle|$normalizedText"
        return Item(
            notificationKey = key,
            signature = signature,
            packageName = sbn.packageName,
            title = normalizedTitle,
            text = normalizedText,
            time = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
        )
    }

    private fun isMessage(notification: Notification, packageName: String): Boolean {
        val p = packageName.lowercase(Locale.ROOT)
        return notification.category == Notification.CATEGORY_MESSAGE || listOf(
            "whatsapp", "telegram", "messenger", "instagram", "messages",
            "mms", "sms", "discord",
        ).any { it in p }
    }

    private fun loadUnread(context: Context): List<Item> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_UNREAD, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(
                        Item(
                            notificationKey = o.optString("key"),
                            signature = o.optString("sig"),
                            packageName = o.optString("pkg"),
                            title = o.optString("title"),
                            text = o.optString("text"),
                            time = o.optLong("time"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveUnread(context: Context, items: List<Item>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("key", item.notificationKey)
                    .put("sig", item.signature)
                    .put("pkg", item.packageName)
                    .put("title", item.title)
                    .put("text", item.text)
                    .put("time", item.time)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_UNREAD, array.toString())
            .apply()
    }

    private fun loadSeen(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SEEN, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveSeen(context: Context, signatures: List<String>) {
        val array = JSONArray()
        signatures.distinct().takeLast(MAX_SEEN).forEach(array::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SEEN, array.toString())
            .apply()
    }
}
