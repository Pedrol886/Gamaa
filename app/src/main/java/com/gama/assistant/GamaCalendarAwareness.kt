package com.gama.assistant

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Quiet calendar watcher. It only surfaces events that are close enough to require attention. */
object GamaCalendarAwareness {
    private const val PREFS = "gama_calendar_awareness"
    private const val KEY_LAST = "last_event_key"
    private const val HORIZON_MS = 15L * 60L * 1000L

    fun poll(context: Context, now: Long = System.currentTimeMillis()): GamaEventHub.Event? {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val end = now + HORIZON_MS
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
        )
        val candidate = runCatching {
            context.contentResolver.query(
                uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val id = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID))
                val title = c.getString(c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)).orEmpty().ifBlank { "Compromisso" }
                val begin = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN))
                Triple(id, title, begin)
            }
        }.getOrNull() ?: return null

        val key = "${candidate.first}:${candidate.third}"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST, null) == key) return null
        prefs.edit().putString(KEY_LAST, key).apply()
        val hhmm = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(candidate.third))
        return GamaEventHub.Event(
            kind = GamaEventHub.Kind.SYSTEM,
            source = "Agenda",
            summary = "${candidate.second} às $hhmm, em menos de quinze minutos",
            priority = GamaEventHub.Priority.HIGH,
            occurredAt = now,
        )
    }
}
