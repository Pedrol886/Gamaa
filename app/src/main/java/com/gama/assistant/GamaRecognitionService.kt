package com.gama.assistant

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

interface TranscriptionListener {
    fun ready()
    fun partial(text: String)
    fun result(text: String)
    fun error(code: Int)
}

/** Android speech adapter, using the same Vosk worker and microphone as Gama. */
class GamaRecognitionService : RecognitionService() {
    private val main = Handler(Looper.getMainLooper())
    private var active: TranscriptionListener? = null

    override fun onStartListening(intent: Intent, callback: Callback) {
        val service = AssistantRuntime.service
        if (active != null || service?.isVoiceActive() != true) {
            try { callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) } catch (_: Exception) {}
            return
        }
        val bridge = object : TranscriptionListener {
            private var began = false
            private fun deliver(done: Boolean = false, action: () -> Unit) {
                main.post {
                    if (active !== this) return@post
                    if (done) active = null
                    try { action() } catch (_: Exception) {
                        service.stopTranscription(this, true)
                        if (active === this) active = null
                    }
                }
            }
            override fun ready() = deliver { callback.readyForSpeech(Bundle()) }
            override fun partial(text: String) = deliver {
                if (!began) { began = true; callback.beginningOfSpeech() }
                callback.partialResults(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                })
            }
            override fun result(text: String) = deliver(true) {
                if (text.isBlank()) callback.error(SpeechRecognizer.ERROR_NO_MATCH)
                else {
                    if (!began) callback.beginningOfSpeech()
                    callback.endOfSpeech()
                    callback.results(Bundle().apply {
                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                    })
                }
            }
            override fun error(code: Int) = deliver(true) { callback.error(code) }
        }
        active = bridge
        service.startTranscription(bridge)
    }

    override fun onStopListening(callback: Callback) {
        active?.let { AssistantRuntime.service?.stopTranscription(it, false) }
    }
    override fun onCancel(callback: Callback) {
        active?.let { AssistantRuntime.service?.stopTranscription(it, true) }
        active = null
    }
    override fun onDestroy() {
        active?.let { AssistantRuntime.service?.stopTranscription(it, true) }
        active = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
