package com.gama.assistant

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.time.Instant

/**
 * Tiny privacy-safe crash recorder. It never stores audio, commands, messages,
 * contacts, notification bodies, calendar text, or conversation content.
 */
object CrashRecorder {
    private const val PREFS = "gama_crash_log"
    private const val KEY_LAST = "last"
    private const val KEY_AT = "at"
    private const val KEY_COMPONENT = "component"
    private const val KEY_PROCESS = "process"
    private const val KEY_THREAD = "thread"

    @Volatile
    private var component: String = "idle"

    fun markComponent(name: String) {
        component = name.take(32)
    }

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(app, thread, error)
            val processName = processName(app)
            val isMainProcess = processName == app.packageName
            val recoverableWorker = isMainProcess &&
                thread !== android.os.Looper.getMainLooper().thread &&
                RuntimeRecoveryBus.isRecoverableWorker(thread.name.orEmpty())

            if (recoverableWorker) {
                RuntimeRecoveryBus.listener?.invoke(
                    thread.name.orEmpty(),
                    error.javaClass.simpleName.ifBlank { error.javaClass.name }
                )
                // Do not route a known isolated worker failure into Android's fatal handler.
                // The worker ends and its owner recreates only that subsystem.
            } else {
                // Main/UI or unknown failures are recorded, then delegated. Swallowing an
                // arbitrary main-thread exception can leave Android framework state corrupt.
                previous?.uncaughtException(thread, error)
            }
        }
    }

    fun recordSoft(context: Context, componentName: String, errorClass: String) {
        runCatching {
            val safe = "component=${componentName.take(32)} | exception=${errorClass.take(80)}"
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_AT, System.currentTimeMillis())
                .putString(KEY_COMPONENT, componentName.take(32))
                .putString(KEY_PROCESS, processName(context))
                .putString(KEY_THREAD, Thread.currentThread().name.take(48))
                .putString(KEY_LAST, safe)
                .commit()
        }
    }

    private fun record(context: Context, thread: Thread, error: Throwable) {
        runCatching {
            val appFrames = error.stackTrace
                .asSequence()
                .filter { it.className.startsWith("com.gama.assistant") }
                .take(4)
                .joinToString(" <- ") { frame ->
                    "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
                }
            val errorName = error.javaClass.name.take(100)
            val headline = buildString {
                append("exception=").append(errorName)
                append(" | component=").append(component)
                append(" | thread=").append(thread.name.take(48))
                if (appFrames.isNotBlank()) append(" | at=").append(appFrames)
            }.take(900)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_AT, System.currentTimeMillis())
                .putString(KEY_COMPONENT, component)
                .putString(KEY_PROCESS, processName(context))
                .putString(KEY_THREAD, thread.name.take(48))
                .putString(KEY_LAST, headline)
                .commit()
        }
    }

    private fun processName(context: Context): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            context.packageName + if (Process.myPid() == 0) ":unknown" else ""
        }
    }
}
