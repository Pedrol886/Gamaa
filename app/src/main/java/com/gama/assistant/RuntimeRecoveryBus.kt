package com.gama.assistant

object RuntimeRecoveryBus {
    @Volatile
    var listener: ((String, String) -> Unit)? = null

    fun isRecoverableWorker(threadName: String): Boolean {
        return threadName == "gama-listener" ||
            threadName == "gama-live-info" ||
            threadName == "gama-research" ||
            threadName == "gama-periodic-check" ||
            threadName == "gama-calendar-awareness" ||
            threadName == "gama-ambient-awareness" ||
            threadName == "gama-local-vision" ||
            threadName == "gama-super-tool" ||
            threadName == "gama-intro-capabilities" ||
            threadName.startsWith("gama-retry-")
    }
}
