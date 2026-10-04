package com.gama.assistant

import android.util.Log

/** The main process never force-kills a PID. Android owns isolated-process teardown. */
object GamaProcessSafety {
    fun detachFailedBrain(pid: Int) {
        if (pid > 0) Log.w("GamaProcessSafety", "detaching failed isolated brain pid=$pid")
    }
}
