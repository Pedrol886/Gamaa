package com.gama.assistant

import android.os.SystemClock

/**
 * Short in-memory authorization window after Android biometric success.
 * No facial image or biometric template is stored by Gama.
 */
object GamaFaceAuthSession {
    private const val VALID_FOR_MS = 120_000L

    @Volatile
    private var authenticatedAtElapsed = 0L

    fun markAuthenticated() {
        authenticatedAtElapsed = SystemClock.elapsedRealtime()
    }

    fun isFresh(): Boolean {
        val at = authenticatedAtElapsed
        if (at <= 0L) return false
        return SystemClock.elapsedRealtime() - at <= VALID_FOR_MS
    }

    fun clear() {
        authenticatedAtElapsed = 0L
    }
}
