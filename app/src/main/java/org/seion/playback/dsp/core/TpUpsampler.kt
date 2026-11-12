package org.seion.playback.dsp.core

/**
 * The ITU-R BS.1770-4 Annex 2 true-peak interpolator: 4 phases × 12 taps (the published coefficients). Its group delay
 * is 23.5 sub-samples = D_tp = 5.875 base samples, so the phases computed at base index k lie between base samples
 * k − 6 and k − 5. 2× uses phases {0, 2}; 1× (above 96 kHz) is the sample peak.
 */
class TpUpsampler(maxChannels: Int = 2) {
    private val hist = Array(maxChannels) { DoubleArray(TAPS) }
    private val pos = IntArray(maxChannels)
    var factor = 4
        private set

    fun configure(fs: Int) {
        factor = when {
            fs <= 48_000 -> 4
            fs <= 96_000 -> 2
            else -> 1
        }
        reset()
    }

    fun reset() {
        for (h in hist) h.fill(0.0)
        pos.fill(0)
    }

    /**
     * Pushes base sample [x] of channel [ch] and returns the largest absolute interpolated value of this index's
     * phases (for 1×: |x| delayed by 6 so every factor has the same alignment).
     */
    fun push(x: Double, ch: Int): Double {
        val h = hist[ch]
        var p = pos[ch]
        h[p] = x
        pos[ch] = if (p + 1 == TAPS) 0 else p + 1
        if (factor == 1) return kotlin.math.abs(h[(p - 6 + TAPS) % TAPS])
        var peak = 0.0
        val step = 4 / factor
        var phase = 0
        while (phase < 4) {
            val c = COEFFS[phase]
            var acc = 0.0
            var idx = p
            for (t in 0 until TAPS) {
                acc += c[t] * h[idx]
                idx = if (idx == 0) TAPS - 1 else idx - 1
            }
            val a = kotlin.math.abs(acc)
            if (a > peak) peak = a
            phase += step
        }
        return peak
    }

    companion object {
        const val TAPS = 12
        val COEFFS =
            arrayOf(
                doubleArrayOf(
                    0.0017089843750, 0.0109863281250, -0.0196533203125, 0.0332031250000, -0.0594482421875, 0.1373291015625,
                    0.9721679687500, -0.1022949218750, 0.0476074218750, -0.0266113281250, 0.0148925781250, -0.0083007812500
                ),
                doubleArrayOf(
                    -0.0291748046875, 0.0292968750000, -0.0517578125000, 0.0891113281250, -0.1665039062500, 0.4650878906250,
                    0.7797851562500, -0.2003173828125, 0.1015625000000, -0.0582275390625, 0.0330810546875, -0.0189208984375
                ),
                doubleArrayOf(
                    -0.0189208984375, 0.0330810546875, -0.0582275390625, 0.1015625000000, -0.2003173828125, 0.7797851562500,
                    0.4650878906250, -0.1665039062500, 0.0891113281250, -0.0517578125000, 0.0292968750000, -0.0291748046875
                ),
                doubleArrayOf(
                    -0.0083007812500, 0.0148925781250, -0.0266113281250, 0.0476074218750, -0.1022949218750, 0.9721679687500,
                    0.1373291015625, -0.0594482421875, 0.0332031250000, -0.0196533203125, 0.0109863281250, 0.0017089843750
                )
            )
    }
}
