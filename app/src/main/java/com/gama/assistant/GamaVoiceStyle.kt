package com.gama.assistant

import android.speech.tts.TextToSpeech

object GamaVoiceStyle {
    fun apply(tts: TextToSpeech?) {
        val engine = tts ?: return
        // Keep the pitch close to the voice's natural recording. The previous
        // 0.82 value made many Android voices sound artificial and metallic.
        runCatching { engine.setPitch(1.00f) }
        runCatching { engine.setSpeechRate(0.97f) }
    }
}
