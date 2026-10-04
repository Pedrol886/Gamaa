package com.gama.assistant

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reads the notifications currently visible to Android's notification listener. */
object GamaNotificationCenter {
    fun hasAccess(context: Context): Boolean = GamaSleepBriefingStore.hasNotificationAccess(context)

    fun readActive(context: Context): String {
        if (!hasAccess(context)) {
            return "Eu ainda não tenho acesso às notificações. Toque em Autorizar acesso às notificações no Gama e ative o acesso no Android."
        }

        val entries = GamaNotificationListener.notifications(context)
        if (entries.isEmpty()) {
            return "Não encontrei notificações ativas agora."
        }

        val visible = entries.take(8)
        val formatter = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
        return buildString {
            if (entries.size == 1) append("Você tem 1 notificação")
            else append("Você tem ${entries.size} notificações")
            append(" ativa")
            if (entries.size != 1) append("s")
            append(". ")
            visible.forEachIndexed { index, entry ->
                append(index + 1).append(", ").append(entry.app)
                if (entry.title.isNotBlank()) append(", ").append(entry.title)
                if (entry.postedAt > 0L) append(", às ").append(formatter.format(Date(entry.postedAt)))
                if (entry.text.isNotBlank()) append(": ").append(entry.text)
                append(". ")
            }
            if (entries.size > visible.size) {
                append("E mais ${entries.size - visible.size} notificações ativas.")
            }
        }.trim()
    }
}
