package com.gama.assistant

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/**
 * Lightweight Gama phrase policy. The foreground service's ConversationSession
 * is the single authority for whether voice conversation is actually open.
 * These preferences are only a small continuity hint for tools across process
 * recreation, never a second microphone/session state machine.
 */
object ZevronConversationEngine {
    private const val PREFS = "zevron_conversation_session"
    private const val KEY_ACTIVE = "conversation_active"
    private const val KEY_LAST_TURN = "last_turn"
    private const val KEY_LAST_SUBJECT = "last_subject"
    private const val SESSION_TIMEOUT_MS = 60L * 60L * 1000L

    enum class Control { NORMAL, WAKE_ONLY, SLEEP }

    fun control(raw: String): Control {
        val normalized = normalize(raw)
        if (normalized in setOf("gama", "gamma")) {
            return Control.WAKE_ONLY
        }
        val withoutWake = normalized
            .replaceFirst(Regex("^(?:gama|gamma)\\s+"), "")
            .trim()
        if (ConversationSession.isGoodbye(withoutWake)) return Control.SLEEP
        return Control.NORMAL
    }

    /** Compatibility helper for non-service callers. */
    fun intercept(context: Context, raw: String): String? = when (control(raw)) {
        Control.WAKE_ONLY -> {
            activate(context)
            "Estou ouvindo."
        }
        Control.SLEEP -> {
            stop(context)
            "Certo."
        }
        Control.NORMAL -> {
            activate(context)
            rememberTurn(context)
            null
        }
    }

    fun active(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return false
        val last = prefs.getLong(KEY_LAST_TURN, 0L)
        if (last <= 0L || now - last > SESSION_TIMEOUT_MS) {
            prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
            return false
        }
        return true
    }

    fun rememberSubject(context: Context, subject: String) {
        if (subject.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SUBJECT, subject.take(300))
            .putLong(KEY_LAST_TURN, System.currentTimeMillis())
            .apply()
    }

    fun lastSubject(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_SUBJECT, null)

    fun stripWakeWord(raw: String): String = raw.trim()
        .replaceFirst(
            Regex("""(?i)^\s*(?:gama|gamma)[\s,.:;!?-]*"""),
            ""
        )
        .trim()

    private fun activate(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_LAST_TURN, System.currentTimeMillis())
            .apply()
    }

    private fun stop(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, false)
            .putLong(KEY_LAST_TURN, System.currentTimeMillis())
            .apply()
    }

    private fun rememberTurn(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_TURN, System.currentTimeMillis())
            .apply()
    }

    internal fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
