package com.gama.assistant

object FailSoftPolicy {
    enum class Recovery { RESTART_COMPONENT, RETRY_LATER, DISABLE_EXECUTION, CONTINUE_WITHOUT_FEATURE }

    fun recovery(component: String): Recovery {
        val key = component.trim().lowercase()
        return when (key) {
            "microphone", "tts" -> Recovery.RESTART_COMPONENT
            "brain ipc" -> Recovery.RETRY_LATER
            "automation" -> Recovery.DISABLE_EXECUTION
            else -> Recovery.CONTINUE_WITHOUT_FEATURE
        }
    }

    fun mayTerminateMainProcess(component: String): Boolean = false
}
