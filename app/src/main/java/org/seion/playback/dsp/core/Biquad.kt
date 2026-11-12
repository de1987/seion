package org.seion.playback.dsp.core

import kotlin.math.abs

/**
 * A transposed direct-form II biquad with double coefficients and state, one state pair per
 * channel. Coefficients are normalised (a0 = 1): y = b0·x + z1; z1 = b1·x − a1·y + z2; z2 = b2·x − a2·y.
 */
class Biquad(maxChannels: Int = 2) {
    var b0 = 1.0
        private set
    var b1 = 0.0
        private set
    var b2 = 0.0
        private set
    var a1 = 0.0
        private set
    var a2 = 0.0
        private set
    private val z1 = DoubleArray(maxChannels)
    private val z2 = DoubleArray(maxChannels)

    fun set(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        this.b0 = b0
        this.b1 = b1
        this.b2 = b2
        this.a1 = a1
        this.a2 = a2
    }

    /** Coefficients from [c] at [off]: b0, b1, b2, a1, a2. */
    fun set(c: DoubleArray, off: Int = 0) = set(c[off], c[off + 1], c[off + 2], c[off + 3], c[off + 4])

    fun process(x: Double, ch: Int): Double {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    /** States below 1e-25 are set to 0 once per block. */
    fun flushDenormals() {
        for (i in z1.indices) {
            if (abs(z1[i]) < Denormal.THRESHOLD) z1[i] = 0.0
            if (abs(z2[i]) < Denormal.THRESHOLD) z2[i] = 0.0
        }
    }

    fun isZero(): Boolean = z1.all { it == 0.0 } && z2.all { it == 0.0 }

    /** Analytic H(e^{jω}) at [hz] for sample rate [fs], written as (re, im) into [out]. */
    fun response(hz: Double, fs: Int, out: DoubleArray) = Filters.response(b0, b1, b2, a1, a2, hz, fs, out)

    fun magnitudeDb(hz: Double, fs: Int): Double {
        val o = DoubleArray(2)
        response(hz, fs, o)
        return 10 * kotlin.math.log10(o[0] * o[0] + o[1] * o[1])
    }
}

/** States below this are flushed once per block. */
object Denormal {
    const val THRESHOLD = 1e-25

    fun flush(v: Double): Double = if (abs(v) < THRESHOLD) 0.0 else v
}
