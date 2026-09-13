package app.weddingpa.mobile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Low-latency speech preamp for the live PA path.
 *
 * Signal chain:
 *  1. Optional 80 Hz 2-pole high-pass.
 *  2. Gentle downwards expander to lower room/noise floor between words.
 *  3. User preamp in dB.
 *  4. Optional presence EQ + soft-knee compressor/leveler.
 *  5. Fast peak limiter at -1 dBFS.
 *
 * All processing is allocation-free inside process().
 */
class PcmProcessor(private val sampleRate: Int) {
    data class Stats(
        val levelPercent: Int,
        val peak: Int,
        val limitedSamples: Int,
        val gainReductionDb: Float,
        val inputDb: Float,
        val outputDb: Float
    )

    private val highPassFilter = Biquad.highPass(sampleRate, 80.0, 0.707)
    private val presenceFilter = Biquad.peaking(sampleRate, 3_200.0, 0.90, 2.2)

    private val gateAttack = smoothingCoefficient(4.0)
    private val gateRelease = smoothingCoefficient(220.0)
    private val envelopeAttack = smoothingCoefficient(5.0)
    private val envelopeRelease = smoothingCoefficient(150.0)
    private val compressorAttack = smoothingCoefficient(6.0)
    private val compressorRelease = smoothingCoefficient(175.0)
    private val limiterRelease = smoothingCoefficient(90.0)

    private var gateEnvelope = 0.0f
    private var gateGain = 1.0f
    private var compressorEnvelope = 0.0f
    private var compressorGain = 1.0f
    private var limiterGain = 1.0f
    private var lastHighPass = true
    private var lastVoiceEnhance = true

    fun reset() {
        highPassFilter.reset()
        presenceFilter.reset()
        gateEnvelope = 0.0f
        gateGain = 1.0f
        compressorEnvelope = 0.0f
        compressorGain = 1.0f
        limiterGain = 1.0f
    }

    fun process(
        buffer: ShortArray,
        count: Int,
        preampDb: Float,
        highPass: Boolean,
        voiceEnhance: Boolean,
        noiseControl: Boolean
    ): Stats {
        if (highPass != lastHighPass) {
            highPassFilter.reset()
            lastHighPass = highPass
        }
        if (voiceEnhance != lastVoiceEnhance) {
            presenceFilter.reset()
            compressorEnvelope = 0f
            compressorGain = 1f
            lastVoiceEnhance = voiceEnhance
        }

        val preamp = dbToLinear(preampDb.coerceIn(-6f, 24f))
        val makeup = if (voiceEnhance) dbToLinear(2.0f) else 1.0f
        val limiterCeiling = dbToLinear(-1.0f)

        var inputSumSquares = 0.0
        var outputSumSquares = 0.0
        var peak = 0
        var limiterHits = 0
        var maxGainReductionDb = 0f

        for (i in 0 until count) {
            var x = buffer[i].toFloat() / 32768.0f
            inputSumSquares += x.toDouble() * x.toDouble()

            if (highPass) {
                x = highPassFilter.process(x)
            }

            // Measure the noise floor before user gain so the expander behaves
            // consistently as the preamp is changed.
            val rawMagnitude = abs(x)
            gateEnvelope = smoothEnvelope(gateEnvelope, rawMagnitude, envelopeAttack, envelopeRelease)

            if (noiseControl) {
                val envelopeDb = amplitudeToDb(gateEnvelope)
                val targetGate = when {
                    envelopeDb <= -60f -> 0.16f
                    envelopeDb >= -44f -> 1.0f
                    else -> 0.16f + ((envelopeDb + 60f) / 16f) * 0.84f
                }
                val coefficient = if (targetGate > gateGain) gateAttack else gateRelease
                gateGain = coefficient * gateGain + (1f - coefficient) * targetGate
                x *= gateGain
            } else {
                gateGain = 1f
            }

            x *= preamp

            var compressorReductionDb = 0f
            if (voiceEnhance) {
                x = presenceFilter.process(x)

                compressorEnvelope = smoothEnvelope(
                    compressorEnvelope,
                    abs(x),
                    envelopeAttack,
                    envelopeRelease
                )
                val levelDb = amplitudeToDb(compressorEnvelope)
                compressorReductionDb = softKneeReduction(
                    levelDb = levelDb,
                    thresholdDb = -18f,
                    ratio = 3.2f,
                    kneeDb = 6f
                )
                val targetCompressorGain = dbToLinear(-compressorReductionDb)
                val coefficient = if (targetCompressorGain < compressorGain) compressorAttack else compressorRelease
                compressorGain = coefficient * compressorGain + (1f - coefficient) * targetCompressorGain

                x *= compressorGain * makeup
                if (compressorReductionDb > maxGainReductionDb) {
                    maxGainReductionDb = compressorReductionDb
                }
            } else {
                compressorEnvelope = 0f
                compressorGain = 1f
            }

            // Brick-wall safety stage with a release tail. Attack is immediate,
            // preventing the boosted speech chain from wrapping or hard clipping.
            val magnitude = abs(x)
            val targetLimiterGain = if (magnitude > limiterCeiling && magnitude > 0f) {
                limiterHits++
                limiterCeiling / magnitude
            } else {
                1.0f
            }
            limiterGain = if (targetLimiterGain < limiterGain) {
                targetLimiterGain
            } else {
                limiterRelease * limiterGain + (1f - limiterRelease)
            }

            x *= limiterGain
            val safe = x.coerceIn(-0.999f, 0.999f)
            val sample = (safe * 32767.0f).toInt()
            buffer[i] = sample.toShort()

            val absSample = abs(sample)
            if (absSample > peak) peak = absSample
            outputSumSquares += safe.toDouble() * safe.toDouble()
        }

        val inputRms = if (count > 0) sqrt(inputSumSquares / count) else 0.0
        val outputRms = if (count > 0) sqrt(outputSumSquares / count) else 0.0
        val inputDb = if (inputRms <= 1e-9) -90f else (20.0 * log10(inputRms)).toFloat()
        val outputDb = if (outputRms <= 1e-9) -90f else (20.0 * log10(outputRms)).toFloat()
        val level = (((outputDb + 55f) / 52f) * 100f).toInt().coerceIn(0, 100)

        return Stats(
            levelPercent = if (peak >= 32_700) 100 else level,
            peak = peak,
            limitedSamples = limiterHits,
            gainReductionDb = maxGainReductionDb,
            inputDb = inputDb,
            outputDb = outputDb
        )
    }

    private fun smoothEnvelope(current: Float, target: Float, attack: Float, release: Float): Float {
        val coefficient = if (target > current) attack else release
        return coefficient * current + (1f - coefficient) * target
    }

    private fun softKneeReduction(levelDb: Float, thresholdDb: Float, ratio: Float, kneeDb: Float): Float {
        val over = levelDb - thresholdDb
        val halfKnee = kneeDb / 2f
        val slope = 1f - 1f / ratio
        return when {
            over <= -halfKnee -> 0f
            over >= halfKnee -> over * slope
            else -> {
                val kneePosition = over + halfKnee
                slope * kneePosition * kneePosition / (2f * kneeDb)
            }
        }.coerceAtLeast(0f)
    }

    private fun smoothingCoefficient(milliseconds: Double): Float {
        val seconds = milliseconds / 1000.0
        return exp(-1.0 / (sampleRate * seconds)).toFloat()
    }

    private fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

    private fun amplitudeToDb(amplitude: Float): Float {
        return if (amplitude <= 1e-7f) -120f else (20.0 * log10(amplitude.toDouble())).toFloat()
    }

    private class Biquad(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float
    ) {
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            x1 = 0f
            x2 = 0f
            y1 = 0f
            y2 = 0f
        }

        companion object {
            fun highPass(sampleRate: Int, frequency: Double, q: Double): Biquad {
                val w0 = 2.0 * PI * frequency / sampleRate
                val c = cos(w0)
                val s = sin(w0)
                val alpha = s / (2.0 * q)

                val b0 = (1.0 + c) / 2.0
                val b1 = -(1.0 + c)
                val b2 = (1.0 + c) / 2.0
                val a0 = 1.0 + alpha
                val a1 = -2.0 * c
                val a2 = 1.0 - alpha
                return normalized(b0, b1, b2, a0, a1, a2)
            }

            fun peaking(sampleRate: Int, frequency: Double, q: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * frequency / sampleRate
                val c = cos(w0)
                val s = sin(w0)
                val alpha = s / (2.0 * q)

                val b0 = 1.0 + alpha * a
                val b1 = -2.0 * c
                val b2 = 1.0 - alpha * a
                val a0 = 1.0 + alpha / a
                val a1 = -2.0 * c
                val a2 = 1.0 - alpha / a
                return normalized(b0, b1, b2, a0, a1, a2)
            }

            private fun normalized(
                b0: Double, b1: Double, b2: Double,
                a0: Double, a1: Double, a2: Double
            ) = Biquad(
                (b0 / a0).toFloat(),
                (b1 / a0).toFloat(),
                (b2 / a0).toFloat(),
                (a1 / a0).toFloat(),
                (a2 / a0).toFloat()
            )
        }
    }
}
