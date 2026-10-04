package com.gama.assistant

import android.content.Context

/**
 * Small local-only task memory. It keeps the current collaborative goal and
 * the last verified outcome. Nothing here is uploaded automatically.
 */
object GamaTaskMemory {
    private const val PREFS = "gama_task_memory"
    private const val KEY_GOAL = "goal"
    private const val KEY_RESULT = "result"
    private const val KEY_UPDATED = "updated"
    private const val KEY_SESSION = "session_active_until"
    private const val SESSION_MS = 60L * 60L * 1000L

    fun rememberGoal(context: Context, goal: String) {
        val clean = goal.trim().replace(Regex("\\s+"), " ").take(700)
        if (clean.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GOAL, clean)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .putLong(KEY_SESSION, System.currentTimeMillis() + SESSION_MS)
            .apply()
    }

    fun rememberResult(context: Context, result: String) {
        val clean = result.trim().replace(Regex("\\s+"), " ").take(900)
        if (clean.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RESULT, clean)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .putLong(KEY_SESSION, System.currentTimeMillis() + SESSION_MS)
            .apply()
    }

    fun touch(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_SESSION, System.currentTimeMillis() + SESSION_MS)
            .apply()
    }

    fun sessionActive(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_SESSION, 0L) > now

    fun summary(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val goal = prefs.getString(KEY_GOAL, null).orEmpty().trim()
        val result = prefs.getString(KEY_RESULT, null).orEmpty().trim()
        if (goal.isBlank() && result.isBlank()) return null
        return buildString {
            if (goal.isNotBlank()) append("A tarefa atual é: $goal.")
            if (result.isNotBlank()) {
                if (isNotEmpty()) append(' ')
                append("A última coisa que aconteceu foi: $result.")
            }
        }
    }

    fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SESSION)
            .apply()
    }
}
