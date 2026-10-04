package com.gama.assistant

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

object GamaCalendarManager {
    private const val PREFS = "gama_calendar_pending"
    private const val KEY_TITLE = "title"
    private const val KEY_START = "start"
    private const val KEY_END = "end"

    data class EventDraft(val title: String, val startMillis: Long, val endMillis: Long)

    fun handle(context: Context, raw: String): String? {
        val normalized = normalize(raw)
        if (!looksCalendarRelated(normalized)) return null

        if (looksLikeQuery(normalized) && !looksLikeCreate(normalized)) {
            if (!hasReadPermission(context)) {
                openPermissionScreen(context)
                return "Preciso da permissão de calendário para consultar sua agenda. Abri a confirmação na tela."
            }
            return todaySummary(context)
        }

        if (looksLikeCreate(normalized)) {
            val draft = GamaCalendarCommandParser.parseCreate(raw)
            if (draft == null) {
                return "Entendi que você quer adicionar algo à agenda. Diga o compromisso, o dia e o horário, por exemplo: Gama, marque dentista amanhã às quinze horas."
            }
            if (!hasWritePermission(context)) {
                savePending(context, draft)
                openPermissionScreen(context)
                return "Preciso da permissão de calendário. Abri a confirmação na tela e, quando você permitir, eu salvo esse compromisso automaticamente."
            }
            return createEvent(context, draft)
        }

        return "Posso consultar ou adicionar algo à sua agenda. Diga o que deseja sem precisar usar a inteligência artificial."
    }

    fun todaySummary(context: Context, now: Long = System.currentTimeMillis()): String {
        if (!hasReadPermission(context)) return "Ainda não tenho permissão para ler sua agenda."

        val start = Calendar.getInstance().apply {
            timeInMillis = now
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
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
        )
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
                    items += cursor.getLong(beginIndex) to
                        cursor.getString(titleIndex).orEmpty().ifBlank { "Compromisso" }
                }
            }
        }

        if (items.isEmpty()) return "Você não tem compromissos marcados para hoje."
        val parts = items.take(5).map { (begin, title) ->
            val cal = Calendar.getInstance().apply { timeInMillis = begin }
            val hh = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
            val mm = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
            "$title às $hh:$mm"
        }
        val extra = items.size - parts.size
        return buildString {
            append("Na sua agenda de hoje: ")
            append(parts.joinToString("; "))
            if (extra > 0) append("; e mais $extra compromisso${if (extra == 1) "" else "s"}")
            append(".")
        }
    }

    fun hasReadPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun commitPending(context: Context): Boolean {
        if (!hasWritePermission(context)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val title = prefs.getString(KEY_TITLE, null) ?: return false
        val start = prefs.getLong(KEY_START, 0L)
        val end = prefs.getLong(KEY_END, 0L)
        if (start <= 0L || end <= start) return false
        val ok = createEventInternal(context, EventDraft(title, start, end))
        if (ok) prefs.edit().clear().apply()
        return ok
    }

    fun openPermissionScreen(context: Context) {
        val intent = Intent(context, GamaCalendarPermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(intent) }
    }

    private fun createEvent(context: Context, draft: EventDraft): String {
        return if (createEventInternal(context, draft)) {
            val cal = Calendar.getInstance().apply { timeInMillis = draft.startMillis }
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val month = cal.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale("pt", "BR")).orEmpty()
            val hour = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
            val minute = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
            "Pronto. Adicionei ${draft.title} à sua agenda para $day de $month às $hour:$minute."
        } else {
            "Não consegui gravar esse compromisso no calendário. Verifique se existe uma conta de calendário ativa no aparelho."
        }
    }

    private fun createEventInternal(context: Context, draft: EventDraft): Boolean {
        val calendarId = writableCalendarId(context) ?: return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, draft.title)
            put(CalendarContract.Events.DTSTART, draft.startMillis)
            put(CalendarContract.Events.DTEND, draft.endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }
        return runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
        }.getOrDefault(false)
    }

    private fun writableCalendarId(context: Context): Long? {
        if (!hasWritePermission(context)) return null
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    CalendarContract.Calendars.IS_PRIMARY,
                ),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                if (cursor.moveToFirst()) cursor.getLong(idIndex) else null
            }
        }.getOrNull()
    }

    private fun savePending(context: Context, draft: EventDraft) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TITLE, draft.title)
            .putLong(KEY_START, draft.startMillis)
            .putLong(KEY_END, draft.endMillis)
            .apply()
    }

    internal fun looksCalendarRelated(c: String): Boolean =
        listOf(
            "agenda", "calendario", "compromisso", "evento",
            "marque", "marca", "marcar", "agende", "agendar",
            "adicione", "adiciona", "adicionar", "coloque", "coloca", "colocar",
            "anote", "anota", "anotar", "crie", "cria", "criar",
            "registre", "registra", "registrar", "bote", "bota", "botar",
            "lembre", "lembrar", "lembrete", "reserve", "reservar"
        ).any { Regex("\\b$it\\b").containsMatchIn(c) }

    internal fun looksLikeCreate(c: String): Boolean =
        listOf(
            "marque", "marca", "marcar", "agende", "agendar",
            "adicione", "adiciona", "adicionar", "coloque", "coloca", "colocar",
            "anote", "anota", "anotar", "crie", "cria", "criar",
            "registre", "registra", "registrar", "bote", "bota", "botar",
            "lembre", "lembrar", "lembrete", "reserve", "reservar"
        ).any { Regex("\\b$it\\b").containsMatchIn(c) }

    private fun looksLikeQuery(c: String): Boolean =
        listOf(
            "o que tenho", "tenho algo", "minha agenda", "leia minha agenda",
            "agenda hoje", "compromissos hoje", "eventos hoje", "o que esta marcado"
        ).any { it in c }

    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9:/]+"), " ")
            .trim()
}

object GamaCalendarCommandParser {
    private val createVerb = Regex(
        "\\b(marque|marca|marcar|agende|agendar|adicione|adiciona|adicionar|coloque|coloca|colocar|" +
            "anote|anota|anotar|crie|cria|criar|registre|registra|registrar|bote|bota|botar|" +
            "lembre|lembrar|lembrete|reserve|reservar)\\b"
    )

    fun parseCreate(raw: String, now: Long = System.currentTimeMillis()): GamaCalendarManager.EventDraft? {
        val normalized = normalize(raw)
        if (!createVerb.containsMatchIn(normalized)) return null

        val base = Calendar.getInstance().apply { timeInMillis = now }
        val date = (base.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (Regex("\\bdepois de amanha\\b").containsMatchIn(normalized)) add(Calendar.DAY_OF_MONTH, 2)
            else if (Regex("\\bamanha\\b").containsMatchIn(normalized)) add(Calendar.DAY_OF_MONTH, 1)
        }

        Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b").find(normalized)?.let { m ->
            date.set(Calendar.DAY_OF_MONTH, m.groupValues[1].toInt().coerceIn(1, 31))
            date.set(Calendar.MONTH, (m.groupValues[2].toInt() - 1).coerceIn(0, 11))
            if (m.groupValues[3].isNotBlank()) {
                val y = m.groupValues[3].toInt()
                date.set(Calendar.YEAR, if (y < 100) 2000 + y else y)
            }
        }

        Regex("\\bdia\\s+(\\d{1,2})\\b").find(normalized)?.let { m ->
            date.set(Calendar.DAY_OF_MONTH, m.groupValues[1].toInt().coerceIn(1, 31))
            if (date.timeInMillis < now) date.add(Calendar.MONTH, 1)
        }

        applyWeekday(normalized, date, base)

        val timeMatch = Regex(
            "\\b(?:as|para as|pelas)?\\s*(\\d{1,2})(?:(?::|h)(\\d{1,2}))?\\s*(?:horas|hora|h)?\\b"
        ).findAll(normalized)
            .filterNot { match ->
                val before = normalized.substring(0, match.range.first).takeLast(4)
                before.endsWith("dia ")
            }
            .lastOrNull()

        var hour = timeMatch?.groupValues?.get(1)?.toIntOrNull()
        val minute = timeMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
        if (hour != null && hour in 1..11 &&
            ("da tarde" in normalized || "a tarde" in normalized || "da noite" in normalized || "a noite" in normalized || " pm" in " $normalized")) {
            hour += 12
        }
        if (hour == 12 && ("da manha" in normalized || "meia noite" in normalized)) hour = 0
        if (hour == null || hour !in 0..23 || minute !in 0..59) return null

        val title = raw
            .replace(Regex("(?i)\\bgama\\b[,:]?"), " ")
            .replace(Regex("(?i)\\b(marque|marca|marcar|agende|agendar|adicione|adiciona|adicionar|coloque|coloca|colocar|anote|anota|anotar|crie|cria|criar|registre|registra|registrar|bote|bota|botar|lembre|lembrar|lembrete|reserve|reservar)\\b"), " ")
            .replace(Regex("(?i)\\b(um|uma)?\\s*(compromisso|evento)\\b"), " ")
            .replace(Regex("(?i)\\b(na|no|minha)?\\s*(agenda|calend[aá]rio)\\b"), " ")
            .replace(Regex("(?i)(hoje|amanh[aã]|depois de amanh[aã])"), " ")
            .replace(Regex("(?i)\\b(da|a)\\s+(manha|tarde|noite)\\b"), " ")
            .replace(Regex("(?i)\\b(segunda|ter[cç]a|quarta|quinta|sexta|s[aá]bado|domingo)(?:-feira)?\\b"), " ")
            .replace(Regex("(?i)\\bdia\\s+\\d{1,2}\\b"), " ")
            .replace(Regex("\\b\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?\\b"), " ")
            .replace(Regex("(?i)(?:[àa]s|para [àa]s|pelas)\\s*\\d{1,2}(?:(?::|h)\\d{1,2})?\\s*(?:horas|hora|h)?"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', '.', ';', ':', '-')
            .ifBlank { "Compromisso" }
            .take(120)

        val start = (date.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        if (start.timeInMillis <= now &&
            start.get(Calendar.YEAR) == base.get(Calendar.YEAR) &&
            start.get(Calendar.DAY_OF_YEAR) == base.get(Calendar.DAY_OF_YEAR)
        ) {
            start.add(Calendar.DAY_OF_MONTH, 1)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, 1) }
        return GamaCalendarManager.EventDraft(title, start.timeInMillis, end.timeInMillis)
    }

    private fun applyWeekday(normalized: String, target: Calendar, base: Calendar) {
        val days = listOf(
            "domingo" to Calendar.SUNDAY,
            "segunda" to Calendar.MONDAY,
            "terca" to Calendar.TUESDAY,
            "quarta" to Calendar.WEDNESDAY,
            "quinta" to Calendar.THURSDAY,
            "sexta" to Calendar.FRIDAY,
            "sabado" to Calendar.SATURDAY,
        )
        val match = days.firstOrNull { Regex("\\b${it.first}(?: feira)?\\b").containsMatchIn(normalized) } ?: return
        var delta = (match.second - base.get(Calendar.DAY_OF_WEEK) + 7) % 7
        if (delta == 0) delta = 7
        target.timeInMillis = base.timeInMillis
        target.add(Calendar.DAY_OF_MONTH, delta)
        target.set(Calendar.HOUR_OF_DAY, 0)
        target.set(Calendar.MINUTE, 0)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9:/]+"), " ")
            .trim()
}
