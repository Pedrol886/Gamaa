package com.gama.assistant

import android.content.ContentUris
import android.content.Context
import android.os.BatteryManager
import android.provider.CalendarContract
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object GamaDayBriefing {
    enum class Period { AFTERNOON, NIGHT }

    fun classify(raw: String): Period? {
        val c = normalize(raw)
        return when {
            c == "boa tarde" || c == "boa tarde gama" || c == "gama boa tarde" ||
                c == "boa tarde gama" || c == "gama boa tarde" ||
                c == "resumo da tarde" || c == "briefing da tarde" -> Period.AFTERNOON
            c == "boa noite" || c == "boa noite gama" || c == "gama boa noite" ||
                c == "boa noite gama" || c == "gama boa noite" ||
                c == "resumo da noite" || c == "briefing da noite" ||
                c == "principais noticias do dia" || c == "noticias do dia" -> Period.NIGHT
            else -> null
        }
    }

    fun buildAsync(context: Context, period: Period, callback: (String) -> Unit) {
        // GAMA92_NIGHT_GREETING_IS_NOT_SLEEP: only an explicit sleep command starts the sleep window.
        Thread({
            val answer = runCatching { build(context, period) }.getOrElse {
                when (period) {
                    Period.AFTERNOON -> "Boa tarde. Não consegui montar o briefing completo agora, mas continuo disponível."
                    Period.NIGHT -> "Boa noite. Não consegui montar o briefing completo agora, mas a janela de sono já está ativa."
                }
            }
            callback(answer)
        }, "gama-day-briefing").apply {
            isDaemon = true
            start()
        }
    }

    private fun build(context: Context, period: Period): String {
        val now = System.currentTimeMillis()
        val news = GamaInternetNews.fetch(context, now)
        val sections = mutableListOf<String>()
        sections += opening(now, period)

        when (period) {
            Period.AFTERNOON -> {
                sections += unreadCount(context)
                sections += if (GamaCalendarManager.hasReadPermission(context)) {
                    GamaCalendarManager.todaySummary(context, now)
                } else {
                    "Ainda preciso da permissão de calendário para conferir o restante da sua agenda."
                }
                if (news.headlines.isNotEmpty()) {
                    sections += "Entre os assuntos em destaque agora: " + news.headlines.take(4).joinToString("; ") + "."
                }
            }
            Period.NIGHT -> {
                sections += nightNews(news)
                sections += unreadCount(context)
                sections += tomorrowCalendar(context, now)
            }
        }

        battery(context)?.let { sections += it }
        return sections.filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun opening(now: Long, period: Period): String {
        val locale = Locale("pt", "BR")
        val date = SimpleDateFormat("EEEE, d 'de' MMMM", locale).format(Date(now))
        val time = SimpleDateFormat("HH:mm", locale).format(Date(now))
        return when (period) {
            Period.AFTERNOON -> "Boa tarde. Hoje é $date e são $time. Aqui está seu resumo da tarde."
            Period.NIGHT -> "Boa noite. Hoje é $date e são $time. Separei o fechamento do seu dia."
        }
    }

    private fun nightNews(result: GamaInternetNews.Result): String {
        if (result.headlines.isEmpty()) {
            return "Não consegui atualizar as notícias agora. Não vou inventar manchetes para preencher o resumo."
        }
        val intro = if (result.fresh) {
            "Estas são as principais notícias do dia: "
        } else {
            "A Internet não respondeu agora, então estas são as notícias online mais recentes que eu já tinha: "
        }
        return intro + result.headlines.take(8).joinToString("; ") + "."
    }

    private fun unreadCount(context: Context): String {
        if (!GamaSleepBriefingStore.hasNotificationAccess(context)) {
            return "O acesso às notificações está desativado, então não consigo conferir suas mensagens novas."
        }
        val unread = GamaUnreadMessageStore.peekUnread(context)
        return when (unread.size) {
            0 -> "Você não tem mensagens novas ainda não vistas."
            1 -> "Você tem uma mensagem nova ainda não vista."
            else -> "Você tem ${unread.size} mensagens novas ainda não vistas."
        }
    }

    private fun tomorrowCalendar(context: Context, now: Long): String {
        if (!GamaCalendarManager.hasReadPermission(context)) {
            return "Ainda preciso da permissão de calendário para conferir amanhã."
        }
        val start = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = Calendar.getInstance().apply {
            timeInMillis = start
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN)
        val items = mutableListOf<Pair<Long, String>>()
        runCatching {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val titleIndex = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                while (cursor.moveToNext()) {
                    items += cursor.getLong(beginIndex) to cursor.getString(titleIndex).orEmpty().ifBlank { "Compromisso" }
                }
            }
        }
        if (items.isEmpty()) return "Você não tem compromissos marcados para amanhã."
        val formatter = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
        return "Para amanhã: " + items.take(6).joinToString("; ") { (begin, title) ->
            "$title às ${formatter.format(Date(begin))}"
        } + "."
    }

    private fun battery(context: Context): String? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level !in 0..100) return null
        return "A bateria está em $level por cento."
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
