package com.gama.assistant

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.HandlerThread
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Keeps Android's hardware/software audio effects attached to every Gama AudioRecord. */
object GamaAudioNoiseSupervisor {
    private val effects = ConcurrentHashMap<Int, MutableList<Any>>()
    @Volatile private var ownerRef: WeakReference<Any>? = null
    @Volatile private var started = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    fun start(owner: Any) {
        ownerRef = WeakReference(owner)
        if (started) return
        synchronized(this) {
            if (started) return
            val worker = HandlerThread("GamaAudioDsp").also { it.start() }
            thread = worker
            handler = Handler(worker.looper)
            started = true
            handler?.post(scanLoop)
        }
    }

    private val scanLoop = object : Runnable {
        override fun run() {
            val owner = ownerRef?.get()
            if (owner == null) {
                stop()
                return
            }
            runCatching { attachRecursively(owner, HashSet(), 0) }
            handler?.postDelayed(this, 2000L)
        }
    }

    private fun attachRecursively(value: Any?, seen: MutableSet<Int>, depth: Int) {
        if (value == null || depth > 3) return
        if (value is AudioRecord) {
            attach(value)
            return
        }
        val cls = value.javaClass
        if (!cls.name.startsWith("com.gama.assistant")) return
        val identity = System.identityHashCode(value)
        if (!seen.add(identity)) return
        var current: Class<*>? = cls
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { field ->
                if (!Modifier.isStatic(field.modifiers)) {
                    runCatching {
                        field.isAccessible = true
                        attachRecursively(field.get(value), seen, depth + 1)
                    }
                }
            }
            current = current.superclass
        }
    }

    fun attachNow(record: AudioRecord) = runCatching { attach(record) }.getOrNull()

    fun detach(record: AudioRecord) {
        val session = runCatching { record.audioSessionId }.getOrDefault(0)
        if (session <= 0) return
        effects.remove(session)?.forEach { effect ->
            runCatching {
                when (effect) {
                    is NoiseSuppressor -> effect.release()
                    is AcousticEchoCanceler -> effect.release()
                    is AutomaticGainControl -> effect.release()
                }
            }
        }
    }

    fun stop() {
        started = false
        handler?.removeCallbacksAndMessages(null)
        effects.values.flatten().forEach { effect ->
            runCatching {
                when (effect) {
                    is NoiseSuppressor -> effect.release()
                    is AcousticEchoCanceler -> effect.release()
                    is AutomaticGainControl -> effect.release()
                }
            }
        }
        effects.clear()
        runCatching { thread?.quitSafely() }
        handler = null
        thread = null
    }

    private fun attach(record: AudioRecord) {
        val session = runCatching { record.audioSessionId }.getOrDefault(0)
        if (session <= 0 || effects.containsKey(session)) return
        val held = mutableListOf<Any>()

        // First line of defence against fans, traffic and other steady background noise.
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(session)?.also {
                    it.enabled = true
                    held += it
                }
            }
        }

        // Keeps Gama's own TTS/media from being mistaken for a barge-in command.
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(session)?.also {
                    it.enabled = true
                    held += it
                }
            }
        }

        // Useful when the owner is farther from the phone. The software front-end has
        // a limiter so AGC cannot turn wind gusts into unbounded peaks.
        runCatching {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(session)?.also {
                    it.enabled = true
                    held += it
                }
            }
        }

        effects[session] = held
    }
}
