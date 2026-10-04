package com.gama.assistant

import java.util.concurrent.ConcurrentHashMap

object GamaRecoveryCoordinator {
    private data class State(var failures: Int, var lastAt: Long)
    private val states = ConcurrentHashMap<String, State>()

    @Synchronized
    fun nextDelayMs(component: String, now: Long = System.currentTimeMillis()): Long {
        val previous = states[component]
        val state = if (previous == null || now - previous.lastAt > 120_000L) {
            State(1, now)
        } else {
            previous.apply {
                failures = (failures + 1).coerceAtMost(7)
                lastAt = now
            }
        }
        states[component] = state
        return when (state.failures) {
            1 -> 500L
            2 -> 1_000L
            3 -> 2_000L
            4 -> 4_000L
            5 -> 8_000L
            else -> 15_000L
        }
    }

    fun markHealthy(component: String) {
        states.remove(component)
    }
}
