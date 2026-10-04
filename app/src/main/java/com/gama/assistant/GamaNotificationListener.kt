package com.gama.assistant

import android.app.Notification
import android.os.Bundle
import android.content.Intent
import android.app.RemoteInput
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

data class GamaMessage(
    val app: String,
    val sender: String,
    val text: String,
    val postedAt: Long
)

data class GamaActiveNotification(
    val app: String,
    val title: String,
    val text: String,
    val postedAt: Long
)

enum class GamaReplyStatus { SENT, NO_MATCH, UNAVAILABLE, FAILED }

class GamaNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        private var instance: GamaNotificationListener? = null
        @Volatile private var snapshotReady = false
        @Volatile private var snapshot: List<GamaMessage> = emptyList()
        @Volatile private var notificationSnapshotReady = false
        @Volatile private var notificationSnapshot: List<GamaActiveNotification> = emptyList()

        private const val PREFS = "gama_message_cache"
        private const val KEY = "messages"
        private const val NOTIFICATION_KEY = "active_notifications"

        fun messages(context: Context): List<GamaMessage> {
            // Nunca atravessa o binder do NotificationListener na thread de comando.
            // A cópia imutável é atualizada pelos callbacks do próprio listener.
            return try {
                if (snapshotReady) snapshot else loadCache(context)
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun notifications(context: Context): List<GamaActiveNotification> {
            return try {
                if (notificationSnapshotReady) notificationSnapshot else loadNotificationCache(context)
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun loadNotificationCache(context: Context): List<GamaActiveNotification> {
            return try {
                val raw = context
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(NOTIFICATION_KEY, "[]") ?: "[]"
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            GamaActiveNotification(
                                app = o.optString("app").take(80),
                                title = o.optString("title").take(160),
                                text = o.optString("text").take(360),
                                postedAt = o.optLong("postedAt")
                            )
                        )
                    }
                }.sortedByDescending { it.postedAt }.take(30)
            } catch (_: Exception) {
                emptyList()
            }
        }


        fun replyToWhatsApp(target: String, message: String): GamaReplyStatus {
            val service = instance ?: return GamaReplyStatus.UNAVAILABLE
            return service.replyToWhatsAppInternal(target, message)
        }

        private fun loadCache(context: Context): List<GamaMessage> {
            return try {
                val raw = context
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, "[]") ?: "[]"

                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            GamaMessage(
                                app = o.optString("app").take(80),
                                sender = o.optString("sender").take(160),
                                text = o.optString("text").take(300),
                                postedAt = o.optLong("postedAt")
                            )
                        )
                    }
                }.sortedByDescending { it.postedAt }.take(30)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    override fun onListenerConnected() {
        // GAMA87_UNREAD_SYNC
        GamaUnreadMessageStore.syncFromActive(this, activeNotifications)
        super.onListenerConnected()
        instance = this
        refreshCache()
        AssistantRuntime.service?.resumePendingNotificationRead()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // GAMA87_UNREAD_CAPTURE
        GamaUnreadMessageStore.capture(this, sbn)
        // GAMA76_SLEEP_CAPTURE
        GamaSleepBriefingStore.captureNotification(this, sbn)
        captureSituationEvent(sbn)

        super.onNotificationPosted(sbn)
        if (sbn?.packageName != packageName) refreshCache()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // GAMA87_UNREAD_REMOVE
        GamaUnreadMessageStore.remove(this, sbn)
        super.onNotificationRemoved(sbn)
        if (sbn?.packageName != packageName) refreshCache()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            snapshotReady = false
            notificationSnapshotReady = false
        }
        super.onDestroy()
    }


    private fun shouldPublishSituationEvent(
        sbn: StatusBarNotification,
        source: String,
        body: String
    ): Boolean {
        val now = System.currentTimeMillis()
        val postedAt = sbn.postTime.takeIf { it > 0L } ?: now

        if (now - postedAt !in 0L..90_000L) return false

        val signature = listOf(
            sbn.packageName,
            sbn.key,
            source.trim(),
            body.replace(Regex("\\s+"), " ").trim().take(220)
        ).joinToString("|")

        val prefs = getSharedPreferences(
            "gama_proactive_dedupe",
            Context.MODE_PRIVATE
        )
        val previous = prefs.getString("signature", null)
        val previousAt = prefs.getLong("at", 0L)

        if (signature == previous && now - previousAt < 5L * 60L * 1000L) {
            return false
        }

        prefs.edit()
            .putString("signature", signature)
            .putLong("at", now)
            .apply()
        return true
    }

    private fun captureSituationEvent(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val messagingPackages = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.instagram.android",
            "org.telegram.messenger", "com.facebook.orca",
            "com.google.android.apps.messaging", "com.samsung.android.messaging"
        )
        val n = sbn.notification ?: return
        val contactEvent = n.category == Notification.CATEGORY_MESSAGE ||
            n.category == Notification.CATEGORY_CALL ||
            sbn.packageName in messagingPackages
        if (!contactEvent) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = (n.extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        if (title.isBlank() && body.isBlank()) return
        val source = title.ifBlank { "Mensagem" }.take(100)
        if (!shouldPublishSituationEvent(sbn, source, body)) return
        val recent = GamaEventHub.recentFromSource(this, source, 2L * 60L * 1000L)
        val priority = GamaProactivePolicy.messagePriority(body, recent)
        GamaEventHub.record(
            this,
            GamaEventHub.Event(
                kind = GamaEventHub.Kind.MESSAGE,
                source = source,
                summary = body.ifBlank { "Nova mensagem" },
                priority = priority,
                occurredAt = sbn.postTime,
            )
        )
    }

    private fun replyToWhatsAppInternal(target: String, message: String): GamaReplyStatus {
        val wanted = WhatsAppCommandParser.cleanTarget(target)
        if (wanted.isBlank() || message.isBlank()) return GamaReplyStatus.NO_MATCH
        val notifications = try { activeNotifications?.toList().orEmpty() } catch (_: Exception) { return GamaReplyStatus.UNAVAILABLE }
        val candidates = notifications.mapNotNull { sbn ->
            if (sbn.packageName !in setOf("com.whatsapp", "com.whatsapp.w4b")) return@mapNotNull null
            val n = sbn.notification ?: return@mapNotNull null
            val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val nt = VoicePolicy.normalize(title)
            if (nt.isBlank() || !(nt == wanted || nt.contains(wanted) || wanted.contains(nt))) return@mapNotNull null
            val action = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return@mapNotNull null
            Triple(sbn, title, action)
        }
        if (candidates.size != 1) return GamaReplyStatus.NO_MATCH
        val action = candidates.first().third
        val inputs = action.remoteInputs ?: return GamaReplyStatus.NO_MATCH
        return try {
            val fillIn = Intent()
            val bundle = Bundle()
            inputs.forEach { bundle.putCharSequence(it.resultKey, message) }
            RemoteInput.addResultsToIntent(inputs, fillIn, bundle)
            action.actionIntent.send(this, 0, fillIn)
            GamaReplyStatus.SENT
        } catch (_: Exception) {
            GamaReplyStatus.FAILED
        }
    }

    private fun refreshCache() {
        try {
            val fresh = collectMessages().take(30)
            val active = collectActiveNotifications().take(30)
            snapshot = fresh
            snapshotReady = true
            notificationSnapshot = active
            notificationSnapshotReady = true
            saveCache(fresh)
            saveNotificationCache(active)
        } catch (_: Exception) {
            // Mantém o último snapshot bom. Uma notificação defeituosa nunca derruba o Gama.
        }
    }

    private fun saveCache(items: List<GamaMessage>) {
        val arr = JSONArray()

        for (item in items) {
            arr.put(
                JSONObject().apply {
                    put("app", item.app)
                    put("sender", item.sender)
                    put("text", item.text)
                    put("postedAt", item.postedAt)
                }
            )
        }

        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    private fun saveNotificationCache(items: List<GamaActiveNotification>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("app", item.app)
                    put("title", item.title)
                    put("text", item.text)
                    put("postedAt", item.postedAt)
                }
            )
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(NOTIFICATION_KEY, arr.toString())
            .apply()
    }

    private fun collectActiveNotifications(): List<GamaActiveNotification> {
        val notifications = try {
            activeNotifications?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        return notifications.mapNotNull { sbn ->
            try {
                if (sbn.packageName == packageName) return@mapNotNull null
                val notification = sbn.notification ?: return@mapNotNull null
                val extras = notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
                val normal = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                    ?.joinToString(" • ") { it.toString().trim() }
                    .orEmpty()
                val body = sequenceOf(big, normal, lines).firstOrNull { it.isNotBlank() }.orEmpty()
                if (title.isBlank() && body.isBlank()) return@mapNotNull null

                val appName = try {
                    val info = packageManager.getApplicationInfo(sbn.packageName, 0)
                    packageManager.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    sbn.packageName.substringAfterLast('.')
                }

                GamaActiveNotification(
                    app = appName.take(80),
                    title = title.take(160),
                    text = body.take(360),
                    postedAt = sbn.postTime
                )
            } catch (_: Exception) {
                null
            }
        }
            .distinctBy { "${it.app}|${it.title}|${it.text}" }
            .sortedByDescending { it.postedAt }
            .take(30)
    }

    private fun collectMessages(): List<GamaMessage> {
        val messagingPackages = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android",
            "org.telegram.messenger",
            "com.facebook.orca",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging"
        )

        val notifications = try {
            activeNotifications?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        return notifications.mapNotNull { sbn ->
            try {
                if (sbn.packageName == packageName) return@mapNotNull null

                val notification = sbn.notification ?: return@mapNotNull null
            val isMessage =
                notification.category == Notification.CATEGORY_MESSAGE ||
                sbn.packageName in messagingPackages

            if (!isMessage) return@mapNotNull null

            val extras = notification.extras

            val title = extras
                .getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()
                ?.trim()
                .orEmpty()

            var body = extras
                .getCharSequence(Notification.EXTRA_TEXT)
                ?.toString()
                ?.trim()
                .orEmpty()

            if (body.isBlank()) {
                body = extras
                    .getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            }

            if (title.isBlank() && body.isBlank()) return@mapNotNull null

            val appName = try {
                val info = packageManager.getApplicationInfo(sbn.packageName, 0)
                packageManager.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                sbn.packageName.substringAfterLast(".")
            }

                GamaMessage(
                    app = appName.take(80),
                    sender = title.ifBlank { "Mensagem" }.take(160),
                    text = body.ifBlank { "Nova mensagem" }.take(300),
                    postedAt = sbn.postTime
                )
            } catch (_: Exception) {
                null
            }
        }
            .distinctBy { "${it.app}|${it.sender}|${it.text}" }
            .sortedByDescending { it.postedAt }
            .take(30)
    }
}
