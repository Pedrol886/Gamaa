package com.gama.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class GamaAudioFrontEndTest {
    private fun pcm(samples: Int, generator: (Int) -> Double): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = generator(i).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v ushr 8) and 0xff).toByte()
        }
        return out
    }

    @Test fun lowFrequencyWindIsDetectedAndReduced() {
        val front = GamaAudioFrontEnd(16000)
        val wind = pcm(2048) { i -> 6000.0 * sin(2.0 * PI * 28.0 * i / 16000.0) }
        val m = front.processInPlace(wind, wind.size)
        assertTrue(m.windLikely)
        assertTrue(m.speechRms < m.rawRms * 0.60)
    }

    @Test fun normalSpeechBandIsNotClassifiedAsWind() {
        val front = GamaAudioFrontEnd(16000)
        val speech = pcm(2048) { i ->
            1800.0 * sin(2.0 * PI * 180.0 * i / 16000.0) +
                900.0 * sin(2.0 * PI * 900.0 * i / 16000.0)
        }
        val m = front.processInPlace(speech, speech.size)
        assertFalse(m.windLikely)
    }
}
