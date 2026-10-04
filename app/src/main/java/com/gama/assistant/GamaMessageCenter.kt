package com.gama.assistant

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reads only message notifications that the user has not seen yet. */
object GamaMessageCenter {
    fun readUnread(context: Context): String {
        if (!GamaSleepBriefingStore.hasNotificationAccess(context)) {
            return "Eu ainda não tenho acesso às notificações. Ative esse acesso para eu conseguir ler suas mensagens novas."
        }

        val entries = GamaUnreadMessageStore.consumeUnread(context)
        if (entries.isEmpty()) {
            return "Você não tem nenhuma mensagem nova que ainda não tenha visto."
        }

        val formatter = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
        return buildString {
            append("Você tem ${entries.size} mensagem")
            if (entries.size != 1) append("s")
            append(" nova")
            if (entries.size != 1) append("s")
            append(". ")
            entries.forEachIndexed { index, entry ->
                val sender = entry.title.ifBlank { appName(entry.packageName) }
                append(index + 1).append(", de ").append(sender)
                if (entry.time > 0L) append(", às ").append(formatter.format(Date(entry.time)))
                if (entry.text.isNotBlank()) append(": ").append(entry.text)
                append(". ")
            }
        }.trim()
    }

    private fun appName(packageName: String): String = when {
        "whatsapp" in packageName.lowercase(Locale.ROOT) -> "WhatsApp"
        "instagram" in packageName.lowercase(Locale.ROOT) -> "Instagram"
        "telegram" in packageName.lowercase(Locale.ROOT) -> "Telegram"
        "messenger" in packageName.lowercase(Locale.ROOT) -> "Messenger"
        else -> "mensagens"
    }
}
