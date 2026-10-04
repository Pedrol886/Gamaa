package com.gama.assistant

/**
 * Single deterministic gate for the behaviours that must never be decided by
 * a fuzzy transcript alone: waking, sleeping and unsolicited speech.
 *
 * Gemma can interpret requests, but it cannot bypass these gates.
 */
object GamaSupervisor {
    enum class State { SLEEPING, LISTENING, THINKING, EXECUTING, VERIFYING, SPEAKING }

    @Volatile
    private var state: State = State.SLEEPING

    fun state(): State = state

    @Synchronized
    fun onWakeAccepted() {
        state = State.LISTENING
    }

    @Synchronized
    fun onConversationClosed() {
        state = State.SLEEPING
    }

    @Synchronized
    fun onThinking() {
        if (state != State.SLEEPING) state = State.THINKING
    }

    @Synchronized
    fun onExecuting() {
        if (state != State.SLEEPING) state = State.EXECUTING
    }

    @Synchronized
    fun onVerifying() {
        if (state != State.SLEEPING) state = State.VERIFYING
    }

    @Synchronized
    fun onSpeaking() {
        if (state != State.SLEEPING) state = State.SPEAKING
    }

    @Synchronized
    fun onListening() {
        if (state != State.SLEEPING) state = State.LISTENING
    }

    fun acceptWake(
        text: String,
        sessionActive: Boolean,
        voiceEvidence: Boolean,
    ): Boolean = !sessionActive && voiceEvidence && VoicePolicy.hasWake(text)

    fun acceptGoodbye(
        text: String,
        sessionActive: Boolean,
        voiceEvidence: Boolean,
        finalEndpoint: Boolean,
    ): Boolean = sessionActive && finalEndpoint && voiceEvidence && ConversationSession.isGoodbye(text)

    fun maySpeakProactively(
        sessionActive: Boolean,
        eventAgeMs: Long,
    ): Boolean = sessionActive && eventAgeMs in 0L..20_000L

    fun shouldDrainProactiveQueue(sessionActive: Boolean): Boolean = sessionActive
}
