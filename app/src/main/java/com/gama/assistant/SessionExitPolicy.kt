package com.gama.assistant

object SessionExitPolicy {
    fun shouldEnd(raw: String): Boolean = ConversationSession.isGoodbye(raw)
}
