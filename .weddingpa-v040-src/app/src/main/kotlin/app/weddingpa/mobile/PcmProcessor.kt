package app.weddingpa.mobile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Small allocation-free voice DSP stage used by the live PA path.
 * - First-order 80 Hz high-pass for handling/rumble noise.
 * - User gain.
 * - Gentle soft-knee peak limiting above ~ -1.7 dBFS.
 */
class PcmProcessor(private val sampleRate: Int) {
    data class Stats(val levelPercent: Int, val peak: Int, val limitedSamples: Int)

    private var prevX = 0.0f
    private var prevY = 0.0f
    private val dt = 1.0f / sampleRate
    private val rc = 1.0f / (2.0f * PI.toFloat() * 80.0f)
    private val hpAlpha = rc / (rc + dt)

    fun reset() {
        prevX = 0.0f
        prevY = 0.0f
    }

    fun process(buffer: ShortArray, count: Int, gain: Float, highPass: Boolean): Stats {
        var sumSquares = 0.0
        var peak = 0
        var limited = 0
        val safeGain = gain.coerceIn(0.25f, 4.0f)

        for (i in 0 until count) {
            val x = buffer[i].toFloat()
            val filtered = if (highPass) {
                val y = hpAlpha * (prevY + x - prevX)
                prevX = x
                prevY = y
                y
            } else {
                // Keep the filter state sane so re-enabling it doesn't create a large transient.
                prevX = x
                prevY = 0f
                x
            }

            var amplified = filtered * safeGain
            val magnitude = abs(amplified)
            if (magnitude > 27_000f) {
                limited++
                amplified = if (amplified >= 0f) {
                    27_000f + (magnitude - 27_000f) * 0.18f
                } else {
                    -(27_000f + (magnitude - 27_000f) * 0.18f)
                }
            }

            val sample = amplified.coerceIn(-32_768f, 32_767f).toInt()
            buffer[i] = sample.toShort()
            peak = maxOf(peak, abs(sample))
            sumSquares += sample.toDouble() * sample.toDouble()
        }

        val rms = if (count > 0) sqrt(sumSquares / count) else 0.0
        val db = if (rms <= 0.0) -60.0 else 20.0 * log10(rms / 32768.0)
        val level = (((db + 55.0) / 55.0) * 100.0).toInt().coerceIn(0, 100)
        return Stats(if (peak >= 32_760) 100 else level, peak, limited)
    }
}
