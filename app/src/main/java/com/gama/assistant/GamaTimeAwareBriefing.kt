package com.gama.assistant

import android.content.Context
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Routes greetings by the device clock instead of blindly trusting the spoken greeting. */
object GamaTimeAwareBriefing {
    fun handle(context: Context, raw: String, callback: (String) -> Unit): Boolean {
        GamaSleepWindowStore.parseAndSaveBedtime(context, raw)?.let {
            callback(it)
            return true
        }

        val now = System.currentTimeMillis()
        if (GamaTimePolicy.isExplicitSleep(raw)) {
            GamaSleepWindowStore.startNow(context, now)
            callback("Certo. Vou considerar as mensagens que chegarem a partir de agora para o seu próximo resumo da manhã. Boa noite.")
            return true
        }

        if (!GamaTimePolicy.isGreeting(raw)) return false
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return when (GamaTimePolicy.periodForHour(hour)) {
            GamaTimePolicy.Period.MORNING -> {
                runAsync("gama-morning-v2", callback) { buildMorning(context, now) }
                true
            }
            GamaTimePolicy.Period.AFTERNOON -> {
                runAsync("gama-afternoon-v2", callback) { buildAfternoon(context, now) }
                true
            }
            GamaTimePolicy.Period.NIGHT -> {
                GamaDayBriefing.buildAsync(context, GamaDayBriefing.Period.NIGHT, callback)
                true
            }
        }
    }

    private fun buildMorning(context: Context, now: Long): String {
        val window = GamaSleepWindowStore.resolveMorningStart(context, now)
        val entries = GamaSleepBriefingStore.entriesSince(context, window.start)
            .asSequence()
            .filter { it.kind == "message" }
            .filter { it.title.isNotBlank() || it.text.isNotBlank() }
            .distinctBy { "${it.packageName}|${it.title}|${it.text}|${it.time / 60000L}" }
            .sortedBy { it.time }
            .toList()

        val sections = mutableListOf<String>()
        val source = if (window.exact) {
            "desde ${formatTime(window.start)}, quando você me avisou que ia dormir"
        } else {
            "desde ${GamaSleepWindowStore.formatMinutes(window.bedtimeMinutes)}, seu horário de sono configurado"
        }
        sections += "Bom dia. Agora são ${formatTime(now)}. Considerei suas mensagens $source."
        sections += formatSleepMessages(entries)

        val news = GamaInternetNews.fetch(context, now)
        if (news.headlines.isNotEmpty()) {
            sections += "Nos destaques desta manhã: " + news.headlines.take(6).joinToString("; ") + "."
        }

        sections += if (GamaCalendarManager.hasReadPermission(context)) {
            GamaCalendarManager.todaySummary(context, now)
        } else {
            "Ainda preciso da permissão de calendário para conferir seus compromissos de hoje."
        }
        battery(context)?.let { sections += it }

        // Anything still marked unread from the sleep window has now been read aloud.
        runCatching { GamaUnreadMessageStore.consumeSince(context, window.start) }
        GamaSleepBriefingStore.finishSleep(context)
        GamaSleepBriefingStore.clearThrough(context, now)
        GamaSleepWindowStore.clearExact(context)
        return sections.filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun buildAfternoon(context: Context, now: Long): String {
        val sections = mutableListOf<String>()
        sections += "Boa tarde. Agora são ${formatTime(now)}."
        // The user asked for every currently unseen message, not just a count.
        sections += GamaMessageCenter.readUnread(context)
        sections += if (GamaCalendarManager.hasReadPermission(context)) {
            GamaCalendarManager.todaySummary(context, now)
        } else {
            "Ainda preciso da permissão de calendário para conferir sua agenda."
        }
        val news = GamaInternetNews.fetch(context, now)
        if (news.headlines.isNotEmpty()) {
            sections += "Entre os assuntos em destaque agora: " + news.headlines.take(4).joinToString("; ") + "."
        }
        battery(context)?.let { sections += it }
        return sections.filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun formatSleepMessages(entries: List<GamaSleepBriefingStore.Entry>): String {
        if (entries.isEmpty()) return "Não encontrei mensagens recebidas nesse período."
        val selected = entries.takeLast(60)
        return buildString {
            append("Você recebeu ${entries.size} mensagem")
            if (entries.size != 1) append("s")
            append(" nesse período. ")
            selected.forEachIndexed { index, item ->
                append(index + 1).append(", de ")
                append(item.title.ifBlank { appName(item.packageName) })
                append(", às ").append(formatTime(item.time))
                if (item.text.isNotBlank()) append(": ").append(item.text.take(900))
                append(". ")
            }
            if (entries.size > selected.size) {
                append("Existem ainda ${entries.size - selected.size} mensagens mais antigas nesse período.")
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

    private fun battery(context: Context): String? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level.takeIf { it in 0..100 }?.let { "A bateria está em $it por cento." }
    }

    private fun formatTime(value: Long): String =
        SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(value))

    private fun runAsync(name: String, callback: (String) -> Unit, block: () -> String) {
        Thread({
            val result = runCatching(block).getOrElse {
                "Não consegui montar esse resumo completo agora, mas continuo disponível."
            }
            callback(result)
        }, name).apply { isDaemon = true; start() }
    }
}
