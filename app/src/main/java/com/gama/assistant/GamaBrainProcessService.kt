package com.gama.assistant

import android.os.Binder

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Local Gemma host that lives in the dedicated :gama_brain process.
 *
 * A native MediaPipe/LlmInference crash can therefore terminate this process without taking
 * GamaService, AudioRecord, Vosk, the floating visual or local Android commands down with it.
 */
class GamaBrainProcessService : Service() {
    companion object {
        private const val TAG = "GamaBrainProcess"
        private const val IDLE_RELEASE_MS = 90_000L
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gama-isolated-brain-worker").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleGeneration = AtomicLong(0L)

    @Volatile
    private var brain: LocalBrain? = null

    private val gamaLivenessBinder = Binder()

    override fun onBind(intent: Intent?): IBinder = gamaLivenessBinder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != BrainProcessProtocol.ACTION_ASK) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val requestId = intent.getLongExtra(BrainProcessProtocol.EXTRA_REQUEST_ID, 0L)
        val question = intent.getStringExtra(BrainProcessProtocol.EXTRA_QUESTION)
            .orEmpty()
            .trim()
            .take(BrainProcessProtocol.MAX_QUESTION_CHARS)
        val history = intent.getStringExtra(BrainProcessProtocol.EXTRA_HISTORY)
            .orEmpty()
            .takeLast(BrainProcessProtocol.MAX_HISTORY_CHARS)

        if (requestId <= 0L || question.isBlank()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // Sent before MediaPipe is touched, so the main process knows which PID belongs only
        // to this inference host and can detect a native process death.
        try {
            sendBroadcast(BrainProcessProtocol.startedIntent(this, requestId))
        } catch (e: Exception) {
            Log.w(TAG, "Could not publish brain process start", e)
        }

        val requestIdleGeneration = idleGeneration.incrementAndGet()
        worker.execute {
            val (result, success) = try {
                val engine = brain ?: LocalBrain(applicationContext).also { brain = it }
                engine.answer(question, history.takeIf { it.isNotBlank() }) to true
            } catch (t: Throwable) {
                if (t is VirtualMachineError || t is ThreadDeath) throw t
                Log.e(TAG, "Isolated local brain failure", t)
                "Não consegui concluir essa resposta agora. Os comandos do celular continuam disponíveis." to false
            }

            try {
                sendBroadcast(
                    BrainProcessProtocol.resultIntent(
                        this,
                        requestId,
                        result,
                        success = success
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not return isolated brain result", e)
            }

            scheduleIdleRelease(requestIdleGeneration)
        }

        return START_NOT_STICKY
    }

    private fun scheduleIdleRelease(token: Long) {
        mainHandler.postDelayed({
            if (token != idleGeneration.get()) return@postDelayed
            worker.execute {
                if (token != idleGeneration.get()) return@execute
                val old = brain
                brain = null
                try { old?.close() } catch (_: Exception) {}
                stopSelf()
            }
        }, IDLE_RELEASE_MS)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        idleGeneration.incrementAndGet()
        worker.execute {
            val old = brain
            brain = null
            try { old?.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        idleGeneration.incrementAndGet()
        val old = brain
        brain = null
        try { old?.close() } catch (_: Exception) {}
        worker.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
