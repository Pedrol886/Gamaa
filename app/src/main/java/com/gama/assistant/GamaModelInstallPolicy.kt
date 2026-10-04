package com.gama.assistant

object GamaModelInstallPolicy {
    const val STALL_TIMEOUT_MS = 240_000L

    fun isStalled(
        nowMs: Long,
        lastProgressMs: Long,
        downloaded: Long,
        previousBytes: Long,
    ): Boolean {
        if (lastProgressMs <= 0L) return false
        if (nowMs < lastProgressMs) return false
        if (downloaded > previousBytes) return false
        return nowMs - lastProgressMs >= STALL_TIMEOUT_MS
    }
}
