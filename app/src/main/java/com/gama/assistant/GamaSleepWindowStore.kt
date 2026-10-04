package com.gama.assistant

import android.content.Context
import java.util.Calendar

/** Exact local sleep marker plus a configurable fallback bedtime. */
object GamaSleepWindowStore {
    private const val PREFS = "gama_sleep_window_v2"
    private const val KEY_EXACT_START = "exact_start"
    private const val KEY_BEDTIME_MINUTES = "bedtime_minutes"
    private const val DEFAULT_BEDTIME_MINUTES = 22 * 60 + 30
    private const val MAX_EXACT_AGE_MS = 20L * 60L * 60L * 1000L

    data class Window(val start: Long, val exact: Boolean, val bedtimeMinutes: Int)

    fun startNow(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_EXACT_START, now)
            .apply()
        GamaSleepBriefingStore.startSleep(context, now)
    }

    fun clearExact(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_EXACT_START)
            .apply()
    }

    fun bedtimeMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_BEDTIME_MINUTES, DEFAULT_BEDTIME_MINUTES)
            .coerceIn(0, 1439)

    fun setBedtime(context: Context, hour: Int, minute: Int): Boolean {
        if (hour !in 0..23 || minute !in 0..59) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_BEDTIME_MINUTES, hour * 60 + minute)
            .apply()
        return true
    }

    fun parseAndSaveBedtime(context: Context, raw: String): String? {
        val c = GamaTimePolicy.normalize(raw)
        val patterns = listOf(
            Regex("""(?:meu horario de dormir e|meu horario de dormir eh|costumo dormir as|eu durmo as|normalmente durmo as)\\s*(\\d{1,2})(?::|h)?(\\d{2})?"""),
            Regex("""(?:defina|coloque|configure)\\s+(?:meu )?horario de dormir(?: para)?\\s*(\\d{1,2})(?::|h)?(\\d{2})?"""),
        )
        val match = patterns.firstNotNullOfOrNull { it.find(c) } ?: return null
        val hour = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val minute = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        if (!setBedtime(context, hour, minute)) return "Esse horário de dormir não é válido."
        return "Certo. Se você não me avisar quando for dormir, vou considerar ${formatMinutes(hour * 60 + minute)} como seu horário de sono."
    }

    fun resolveMorningStart(context: Context, now: Long = System.currentTimeMillis()): Window {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val exact = prefs.getLong(KEY_EXACT_START, 0L)
        val bedtime = bedtimeMinutes(context)
        if (exact in (now - MAX_EXACT_AGE_MS)..now && exact > 0L) {
            return Window(exact, true, bedtime)
        }

        val hour = bedtime / 60
        val minute = bedtime % 60
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis >= now) add(Calendar.DAY_OF_MONTH, -1)
        }
        return Window(cal.timeInMillis, false, bedtime)
    }

    fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
}
