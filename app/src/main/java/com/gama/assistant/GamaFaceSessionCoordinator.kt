package com.gama.assistant

/**
 * Ephemeral bridge between the foreground GamaService and the face gate UI.
 * The pending command lives only in RAM and disappears if the process dies.
 */
object GamaFaceSessionCoordinator {
    private data class Pending(
        val command: String?,
        val unlockMode: Boolean,
        val onApproved: ((String) -> Unit)?,
        val onStatus: ((String) -> Unit)?,
    )

    @Volatile
    private var pending: Pending? = null

    @Synchronized
    fun beginPrivateCommand(
        command: String,
        onApproved: (String) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        pending = Pending(
            command = command,
            unlockMode = false,
            onApproved = onApproved,
            onStatus = onStatus,
        )
    }

    @Synchronized
    fun beginUnlock(
        onStatus: (String) -> Unit,
    ) {
        pending = Pending(
            command = null,
            unlockMode = true,
            onApproved = null,
            onStatus = onStatus,
        )
    }


    @Synchronized
    fun beginEnrollment(
        onStatus: (String) -> Unit,
    ) {
        pending = Pending(
            command = null,
            unlockMode = false,
            onApproved = null,
            onStatus = onStatus,
        )
    }

    fun isUnlockMode(): Boolean = pending?.unlockMode == true

    @Synchronized
    fun approvePrivateCommand() {
        val current = pending ?: return
        pending = null
        val command = current.command ?: return
        current.onApproved?.invoke(command)
    }

    @Synchronized
    fun reportAndClear(message: String) {
        val current = pending
        pending = null
        current?.onStatus?.invoke(message)
    }

    fun reportWithoutClearing(message: String) {
        pending?.onStatus?.invoke(message)
    }

    @Synchronized
    fun clear() {
        pending = null
    }
}
