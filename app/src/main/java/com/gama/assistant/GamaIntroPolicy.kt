package com.gama.assistant

/** Pure readiness policy for the one-time first-ready presentation. */
object GamaIntroPolicy {
    fun shouldLaunch(
        setupDone: Boolean,
        identityReady: Boolean,
        microphoneGranted: Boolean,
        modelInstalled: Boolean,
        introComplete: Boolean
    ): Boolean =
        setupDone &&
            identityReady &&
            microphoneGranted &&
            modelInstalled &&
            !introComplete
}
