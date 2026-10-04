package com.gama.assistant

import android.content.Context
import android.os.BatteryManager
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GamaMorningBriefing {
    fun handleSleep(context: Context, raw: String): String? {
        val c = normalize(raw)
        if (!isSleepCommand(c)) return null
        GamaSleepBriefingStore.startSleep(context)
        return buildString {
            append("Boa noite. Vou acompanhar localmente as mensagens que chegarem enquanto você dorme.")
            append(" Pela manhã também vou buscar as principais notícias na Internet e conferir sua agenda.")
            if (!GamaSleepBriefingStore.hasNotificationAccess(context)) {
                append(" Para incluir mensagens no resumo, ainda preciso do acesso às notificações.")
            }
        }
    }

    fun isMorningCommand(raw: String): Boolean {
        val c = normalize(raw)
        return c == "bom dia" || c == "bom dia gama" || c == "gama bom dia" ||
            c == "bom dia gama" || c == "gama bom dia" || "acordei" in c ||
            "briefing da manha" in c || "resumo da manha" in c ||
            "o que aconteceu enquanto eu dormia" in c || "resumo do dia" in c
    }

    fun buildAsync(context: Context, callback: (String) -> Unit) {
        Thread({
            val result = runCatching { buildBriefing(context) }
                .getOrElse {
                    "Bom dia. Não consegui montar o resumo completo agora, mas continuo disponível."
                }
            callback(result)
        }, "gama-morning-briefing").apply {
            isDaemon = true
            start()
        }
    }

    private fun buildBriefing(context: Context): String {
        val now = System.currentTimeMillis()
        val since = GamaSleepBriefingStore.windowStart(context, now)
        val entries = GamaSleepBriefingStore.entriesSince(context, since)
        val messages = entries.filter { it.kind == "message" }
        val internetNews = GamaInternetNews.fetch(context, now)

        val sections = mutableListOf<String>()
        sections += opening(context, now, since)
        sections += messageSummary(context, messages)
        sections += internetNewsSummary(internetNews)

        if (!GamaCalendarManager.hasReadPermission(context)) {
            GamaCalendarManager.openPermissionScreen(context)
            sections += "Ainda preciso da permissão de calendário para conferir seus compromissos de hoje."
        } else {
            sections += GamaCalendarManager.todaySummary(context, now)
        }

        batterySummary(context)?.let { sections += it }

        if (!GamaSleepBriefingStore.hasNotificationAccess(context)) {
            sections += "O acesso às notificações está desativado, então a parte das mensagens pode estar incompleta."
        }

        GamaSleepBriefingStore.finishSleep(context)
        GamaSleepBriefingStore.clearThrough(context, now)
        return sections.filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun opening(context: Context, now: Long, since: Long): String {
        val locale = Locale("pt", "BR")
        val date = SimpleDateFormat("EEEE, d 'de' MMMM", locale).format(Date(now))
        val time = SimpleDateFormat("HH:mm", locale).format(Date(now))
        val elapsedMinutes = ((now - since).coerceAtLeast(0L) / 60_000L).toInt()
        val hours = elapsedMinutes / 60
        val minutes = elapsedMinutes % 60
        val sleepWindow = when {
            hours > 0 && minutes > 0 -> "$hours horas e $minutes minutos"
            hours > 0 -> "$hours horas"
            else -> "$minutes minutos"
        }
        return "Bom dia. Hoje é $date, são $time. Preparei seu resumo considerando aproximadamente $sleepWindow."
    }

    private fun messageSummary(context: Context, entries: List<GamaSleepBriefingStore.Entry>): String {
        if (entries.isEmpty()) return "Não encontrei mensagens novas no período."
        val groups = entries.groupBy { e ->
            val sender = e.title.ifBlank { appLabel(context, e.packageName) }
            "${e.packageName}|$sender"
        }
        val total = entries.size
        val pieces = groups.entries.take(10).map { (_, values) ->
            val first = values.first()
            val app = appLabel(context, first.packageName)
            val sender = first.title.ifBlank { app }
            if (values.size == 1) {
                val preview = first.text.replace(Regex("\\s+"), " ").trim().take(220)
                if (preview.isBlank()) "uma mensagem de $sender pelo $app"
                else "uma mensagem de $sender pelo $app dizendo: $preview"
            } else {
                "${values.size} mensagens de $sender pelo $app"
            }
        }
        val hidden = groups.size - pieces.size
        return buildString {
            append("Enquanto você dormia, chegaram $total mensagem${if (total == 1) "" else "s"}. ")
            append(pieces.joinToString("; "))
            if (hidden > 0) append("; e mais $hidden conversa${if (hidden == 1) "" else "s"}")
            append(".")
        }
    }

    private fun internetNewsSummary(result: GamaInternetNews.Result): String {
        if (result.headlines.isEmpty()) {
            return "Não consegui atualizar as notícias pela Internet agora."
        }
        val intro = if (result.fresh) {
            "Pesquisei as principais notícias na Internet. Os destaques são: "
        } else {
            "A Internet não respondeu agora, então usei as notícias online mais recentes que eu já tinha. Os destaques são: "
        }
        return intro + result.headlines.take(6).joinToString("; ") + "."
    }

    private fun batterySummary(context: Context): String? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level !in 0..100) return null
        return when {
            level <= 20 -> "A bateria está em $level por cento. Vale colocar o celular para carregar."
            level >= 90 -> "A bateria está em $level por cento."
            else -> "Seu celular está com $level por cento de bateria."
        }
    }

    private fun appLabel(context: Context, packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName.substringAfterLast('.').ifBlank { "aplicativo" })

    private fun isSleepCommand(c: String): Boolean =
        "vou dormir" in c || "indo dormir" in c || "vou deitar" in c ||
            "indo deitar" in c || "hora de dormir" in c || "modo sono" in c ||
            c == "gama vou dormir" || c == "vou dormir gama"

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
