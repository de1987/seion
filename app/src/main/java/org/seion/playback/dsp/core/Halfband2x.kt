package org.seion.playback.dsp.core

/**
 * Linear-phase halfband FIR (Kaiser-windowed sinc): [taps] odd,
 * h[n] = 0.5·sinc(n/2)·kaiser(β), normalised to unity DC gain. Every other tap but the centre is zero, so only the
 * nonzero taps are stored (17 of 31, 49 of 95). The exciter uses Up31 (its input is band-limited to 2.5–6 kHz) and
 * Down95 (it removes the shaper's products above fs before decimation).
 */
class Halfband2x(val taps: Int, beta: Double = KAISER_BETA, maxChannels: Int = 2) {
    val coefficients = DoubleArray(taps)
    private val centre = (taps - 1) / 2

    /** Nonzero taps other than the centre: their offsets from the centre (odd) and values. */
    private val sideOffsets: IntArray
    private val sideValues: DoubleArray

    // History rings per channel, doubled (each sample written twice) so the filter reads without a modulo.
    private val hist = Array(maxChannels) { DoubleArray(2 * taps) }
    private val pos = IntArray(maxChannels)

    /** Absolute tap indices of the dense phase for the up path (centre + odd offsets that are even indices). */
    private val upTaps: IntArray
    private val upValues: DoubleArray

    init {
        check(taps % 2 == 1 && ((taps - 1) / 2) % 2 == 1) { "a halfband needs an odd centre" }
        val i0b = besselI0(beta)
        var sum = 0.0
        for (i in 0 until taps) {
            val n = i - centre
            val sinc = if (n == 0) 1.0 else StrictMath.sin(Math.PI * n / 2) / (Math.PI * n / 2)
            val r = 2.0 * i / (taps - 1) - 1
            val w = besselI0(beta * StrictMath.sqrt(1 - r * r)) / i0b
            coefficients[i] = if (n != 0 && n % 2 == 0) 0.0 else 0.5 * sinc * w
            sum += coefficients[i]
        }
        for (i in 0 until taps) coefficients[i] /= sum
        val offs = ArrayList<Int>()
        for (i in 0 until taps) if (i != centre && coefficients[i] != 0.0) offs += i - centre
        sideOffsets = offs.toIntArray()
        sideValues = DoubleArray(offs.size) { coefficients[centre + offs[it]] }
        upTaps = IntArray(offs.size) { (centre + offs[it]) / 2 }
        upValues = DoubleArray(offs.size) { 2 * coefficients[centre + offs[it]] }
    }

    fun reset() {
        for (h in hist) h.fill(0.0)
        pos.fill(0)
    }

    fun flushDenormals() {
        for (h in hist) for (i in h.indices) if (kotlin.math.abs(h[i]) < Denormal.THRESHOLD) h[i] = 0.0
    }

    /**
     * Upsampling by 2 of one base-rate sample of channel [ch] (the zero-stuffed stream filtered with gain 2): writes the
     * two 2× samples into [out] at [off] (even, odd). Uses a history of base-rate samples.
     */
    fun up(x: Double, ch: Int, out: DoubleArray, off: Int) {
        val h = hist[ch]
        val half = (taps + 1) / 2 // base-rate history length needed
        var p = pos[ch]
        h[p] = x
        h[p + half] = x
        val base = p + half // the newest sample in the second copy: x[m − d] = h[base − d]
        // even output 2m: the dense phase, y[2m] = 2·Σ_{i even} c[i]·x[m − i/2]
        var acc = 0.0
        for (k in upTaps.indices) acc += upValues[k] * h[base - upTaps[k]]
        out[off] = acc
        // odd output 2m + 1: the centre tap only, x[m − (centre − 1)/2]
        out[off + 1] = 2 * coefficients[centre] * h[base - (centre - 1) / 2]
        p++
        if (p == half) p = 0
        pos[ch] = p
    }

    /**
     * Decimation by 2: consumes two 2× samples [a] (even) and [b] (odd) of channel [ch], returns one base sample: the
     * filtered value at [a]'s index, so up + down delays a passband signal by a whole (taps_up + taps_down − 2)/4
     * base samples (31 for Up31 + Down95).
     */
    fun down(a: Double, b: Double, ch: Int): Double {
        val h = hist[ch]
        var p = pos[ch]
        h[p] = a
        h[p + taps] = a
        val base = p + taps // v[t − i] = h[base − i]
        var acc = coefficients[centre] * h[base - centre]
        for (k in sideOffsets.indices) acc += sideValues[k] * h[base - centre - sideOffsets[k]]
        if (++p == taps) p = 0
        h[p] = b
        h[p + taps] = b
        if (++p == taps) p = 0
        pos[ch] = p
        return acc
    }

    /** |H| in dB at normalised frequency [f] (cycles per 2× sample). */
    fun magnitudeDb(f: Double): Double {
        var re = 0.0
        var im = 0.0
        for (i in 0 until taps) {
            re += coefficients[i] * StrictMath.cos(2 * Math.PI * f * i)
            im -= coefficients[i] * StrictMath.sin(2 * Math.PI * f * i)
        }
        return 10 * kotlin.math.log10(re * re + im * im)
    }

    companion object {
        /** β for an 80 dB Kaiser stopband. */
        const val KAISER_BETA = 7.857

        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (k < 200) {
                term *= (x / (2 * k)) * (x / (2 * k))
                sum += term
                if (term < sum * 1e-17) break
                k++
            }
            return sum
        }
    }
}
