package com.gama.assistant

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Lightweight, offline speech front-end tuned for a phone microphone.
 *
 * It complements Android's hardware NoiseSuppressor/AGC with three jobs that are
 * especially useful outdoors and in crowds:
 *  - remove sub-speech low-frequency energy caused by wind/handling;
 *  - estimate a rolling environmental noise floor and SNR;
 *  - apply conservative gain/limiting without turning background chatter into speech.
 *
 * The processor is stateful and must be fed consecutive 16-bit mono PCM blocks.
 */
data class GamaAudioMetrics(
    val rawRms: Double,
    val speechRms: Double,
    val noiseRms: Double,
    val snrDb: Double,
    val zeroCrossingRate: Double,
    val windLikely: Boolean,
    val severeNoise: Boolean,
) {
    val speechLikely: Boolean
        get() = !windLikely && speechRms >= 55.0 &&
            (snrDb >= 4.0 || (snrDb >= 2.5 && zeroCrossingRate in 0.025..0.38))

    companion object {
        fun initial() = GamaAudioMetrics(0.0, 0.0, 95.0, 0.0, 0.0, false, false)
    }
}

class GamaAudioFrontEnd(private val sampleRate: Int) {
    // A gentle ~90 Hz one-pole high-pass filter. It keeps speech formants while
    // cutting the strongest wind/handling rumble before Vosk sees it.
    private val hpAlpha: Double
    private var previousInput = 0.0
    private var previousOutput = 0.0
    private var noiseRms = 95.0

    init {
        val cutoffHz = 90.0
        val dt = 1.0 / sampleRate.toDouble()
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        hpAlpha = rc / (rc + dt)
    }

    fun reset() {
        previousInput = 0.0
        previousOutput = 0.0
        noiseRms = 95.0
    }

    /** Processes [read] bytes in-place and returns environmental metrics. */
    fun processInPlace(buffer: ByteArray, read: Int): GamaAudioMetrics {
        if (read < 2) return GamaAudioMetrics.initial().copy(noiseRms = noiseRms)

        var rawEnergy = 0.0
        var filteredEnergy = 0.0
        var crossings = 0
        var samples = 0
        var previousFiltered = 0.0
        var i = 0

        while (i + 1 < read) {
            val lo = buffer[i].toInt() and 0xff
            val hi = buffer[i + 1].toInt()
            val raw = ((hi shl 8) or lo).toShort().toDouble()

            val filtered = hpAlpha * (previousOutput + raw - previousInput)
            previousInput = raw
            previousOutput = filtered

            rawEnergy += raw * raw
            filteredEnergy += filtered * filtered
            if (samples > 0 && (filtered >= 0.0) != (previousFiltered >= 0.0)) crossings++
            previousFiltered = filtered

            val sample = filtered.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = (sample and 0xff).toByte()
            buffer[i + 1] = ((sample ushr 8) and 0xff).toByte()
            samples++
            i += 2
        }

        if (samples == 0) return GamaAudioMetrics.initial().copy(noiseRms = noiseRms)

        val rawRms = sqrt(rawEnergy / samples)
        var speechRms = sqrt(filteredEnergy / samples)
        val zcr = crossings.toDouble() / samples.coerceAtLeast(1)

        // If most energy vanishes after the 90 Hz high-pass, the block is dominated
        // by low-frequency turbulence/handling rather than useful speech.
        val retained = speechRms / rawRms.coerceAtLeast(1.0)
        val windLikely = rawRms >= 420.0 && retained < 0.43 && zcr < 0.075

        val snrBeforeUpdate = dbRatio(speechRms + 1.0, noiseRms + 1.0)

        // Track the floor only from near-floor frames. This allows Gama to adapt to
        // a bus/classroom without teaching it that the owner's close speech is noise.
        if (speechRms <= noiseRms * 1.55 || snrBeforeUpdate < 3.8) {
            val candidate = speechRms.coerceIn(35.0, 4200.0)
            noiseRms = noiseRms * 0.94 + candidate * 0.06
        } else {
            // Very slow upward drift follows a sustained crowd without pumping.
            noiseRms = (noiseRms * 1.00035).coerceAtMost(4200.0)
        }
        if (windLikely) {
            noiseRms = (noiseRms * 0.96 + speechRms.coerceAtMost(4200.0) * 0.04)
        }

        val snrDb = dbRatio(speechRms + 1.0, noiseRms + 1.0)
        val severeNoise = windLikely || noiseRms >= 1150.0

        // Conservative software AGC. Android's hardware AGC remains primary; this
        // only rescues a nearby but quiet voice and never boosts detected wind.
        var gain = 1.0
        if (!windLikely && snrDb >= 4.0 && speechRms in 65.0..1800.0) {
            gain = (1500.0 / speechRms).coerceIn(1.0, 1.85)
        } else if (speechRms > 9000.0) {
            gain = (9000.0 / speechRms).coerceIn(0.55, 1.0)
        }

        if (gain != 1.0) {
            i = 0
            while (i + 1 < read) {
                val lo = buffer[i].toInt() and 0xff
                val hi = buffer[i + 1].toInt()
                val value = ((hi shl 8) or lo).toShort().toInt()
                val scaled = (value * gain).toInt().coerceIn(-30000, 30000)
                buffer[i] = (scaled and 0xff).toByte()
                buffer[i + 1] = ((scaled ushr 8) and 0xff).toByte()
                i += 2
            }
            speechRms *= gain
        }

        return GamaAudioMetrics(
            rawRms = rawRms,
            speechRms = speechRms,
            noiseRms = noiseRms,
            snrDb = snrDb,
            zeroCrossingRate = zcr,
            windLikely = windLikely,
            severeNoise = severeNoise,
        )
    }

    private fun dbRatio(signal: Double, noise: Double): Double =
        20.0 * ln((signal / noise).coerceAtLeast(0.0001)) / ln(10.0)
}
