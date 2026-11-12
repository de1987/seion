package org.seion.playback.dsp.testutil

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.seion.playback.dsp.core.Halfband2x
import org.seion.playback.dsp.core.TpUpsampler

/** Test meters. Inputs are interleaved with [ch] channels. */
object Meters {
    /**
     * BS.1770-4 true peak (the 4× interpolator at ≤ 48 kHz, its phases only, as the standard meter), in dBTP, over
     * frames [from, to). Near 0.45·fs the interpolator can read below the sample peak itself; the 16× reference
     * covers true reconstruction.
     */
    fun truePeakDb(x: FloatArray, ch: Int, fs: Int, from: Int = 0, to: Int = x.size / ch): Double {
        val tp = TpUpsampler(ch).apply { configure(fs) }
        var peak = 0.0
        val lead = minOf(from, 32)
        for (f in from - lead until minOf(to + 6, x.size / ch)) {
            for (c in 0 until ch) {
                val v = tp.push(x[f * ch + c].toDouble(), c)
                // push at f reports the interval of samples f−6..f−5
                if (f - 6 >= from && f - 5 < to) peak = maxOf(peak, v)
            }
        }
        return 20 * log10(peak.coerceAtLeast(1e-12))
    }

    /** A 16× windowed-sinc reference meter (Kaiser β 10, ±32 base samples), in dBTP, over frames [from, to). */
    fun truePeak16Db(x: FloatArray, ch: Int, from: Int = 0, to: Int = x.size / ch): Double {
        val half = 32
        val os = 16
        val i0b = Halfband2x.besselI0(10.0)
        val table = Array(os) { k ->
            DoubleArray(2 * half) { j ->
                val tt = (j - half + 1) - k.toDouble() / os // distance from sample n to t = i + k/os
                val sinc = if (abs(tt) < 1e-12) 1.0 else sin(PI * tt) / (PI * tt)
                val r = tt / (half + 1)
                val w = if (abs(r) >= 1) 0.0 else Halfband2x.besselI0(10.0 * sqrt(1 - r * r)) / i0b
                sinc * w
            }
        }
        val frames = x.size / ch
        var peak = 0.0
        for (c in 0 until ch) {
            for (i in from until to) {
                for (k in 0 until os) {
                    var acc = 0.0
                    val tab = table[k]
                    for (j in 0 until 2 * half) {
                        val n = i - (j - half + 1)
                        if (n in 0 until frames) acc += x[n * ch + c] * tab[j]
                    }
                    peak = maxOf(peak, abs(acc))
                }
            }
        }
        return 20 * log10(peak.coerceAtLeast(1e-12))
    }

    /** THD+N of a sine at [hz] (7-term Blackman-Harris, fundamental notched ± 8 bins: its main lobe), as a ratio. */
    fun thdN(x: FloatArray, fs: Int, hz: Double, from: Int, len: Int): Double {
        val a =
            doubleArrayOf(
                0.27105140069342,
                -0.43329793923448,
                0.21812299954311,
                -0.06592544638803,
                0.01081174209837,
                -0.00077658482522,
                0.00001388721735
            )
        val n = len
        val w = DoubleArray(n) { i ->
            var s = 0.0
            for (k in a.indices) s += a[k] * cos(2 * PI * k * i / n)
            s
        }
        val fft = org.seion.playback.dsp.core.RealFft(n)
        val buf = FloatArray(n) { (x[from + it] * w[it]).toFloat() }
        val re = FloatArray(n / 2 + 1)
        val im = FloatArray(n / 2 + 1)
        fft.forward(buf, re, im)
        val bin = hz * n / fs
        var fund = 0.0
        var rest = 0.0
        for (k in 1 until n / 2) {
            val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            if (abs(k - bin) <= 8) fund += p else rest += p // the 7-term window's main lobe is ±7 bins
        }
        return sqrt(rest / fund)
    }

    /**
     * BS.1770-4 integrated loudness (LUFS) with pyloudnorm's K-weighting (shelf 1500 Hz +4 dB Q 0.7071, high-pass
     * 38 Hz Q 0.5, designed for any fs), 400 ms blocks with 75 % overlap, −70 LUFS absolute and −10 LU relative gates.
     */
    fun lufs(x: FloatArray, ch: Int, fs: Int): Double {
        fun shelf(): DoubleArray {
            val a = Math.pow(10.0, 4.0 / 40)
            val w0 = 2 * PI * 1500.0 / fs
            val alpha = sin(w0) / (2 * (1 / sqrt(2.0)))
            val cw = cos(w0)
            val a0 = (a + 1) - (a - 1) * cw + 2 * sqrt(a) * alpha
            return doubleArrayOf(
                a * ((a + 1) + (a - 1) * cw + 2 * sqrt(a) * alpha) / a0,
                -2 * a * ((a - 1) + (a + 1) * cw) / a0,
                a * ((a + 1) + (a - 1) * cw - 2 * sqrt(a) * alpha) / a0,
                2 * ((a - 1) - (a + 1) * cw) / a0,
                ((a + 1) - (a - 1) * cw - 2 * sqrt(a) * alpha) / a0
            )
        }
        fun highpass(): DoubleArray {
            val w0 = 2 * PI * 38.0 / fs
            val alpha = sin(w0) / (2 * 0.5)
            val cw = cos(w0)
            val a0 = 1 + alpha
            return doubleArrayOf((1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
        }
        val frames = x.size / ch
        val power = DoubleArray(frames)
        for (c in 0 until ch) {
            val y = Signals.biquad(Signals.biquad(Signals.channel(x, ch, c), shelf()), highpass())
            for (i in 0 until frames) power[i] += y[i].toDouble() * y[i]
        }
        val block = (0.4 * fs).toInt()
        val step = block / 4
        val blocks = ArrayList<Double>()
        var s = 0
        while (s + block <= frames) {
            var e = 0.0
            for (i in s until s + block) e += power[i]
            blocks += e / block
            s += step
        }
        fun loud(z: Double) = -0.691 + 10 * log10(z)
        val abs = blocks.filter { loud(it) > -70 }
        if (abs.isEmpty()) return -70.0
        val rel = loud(abs.average()) - 10
        val gated = abs.filter { loud(it) > rel }
        return loud(gated.average())
    }

    /**
     * Log-spectral distance over [fmin, fmax] (the RMS over frames of the dB difference of the power spectra): mono, Hann
     * 2048/512, log10 power (scipy 'spectrum' scaling), frames whose reference energy exceeds 1e-8.
     */
    fun lsd(ref: FloatArray, est: FloatArray, fs: Int, fmin: Double, fmax: Double): Double {
        val n = 2048
        val hop = 512
        val fft = org.seion.playback.dsp.core.RealFft(n)
        val w = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val wsum = w.sumOf { it.toDouble() }
        val fr = FloatArray(n)
        val fe = FloatArray(n)
        val rr = FloatArray(n / 2 + 1)
        val ri = FloatArray(n / 2 + 1)
        val er = FloatArray(n / 2 + 1)
        val ei = FloatArray(n / 2 + 1)
        val k0 = kotlin.math.ceil(fmin * n / fs).toInt()
        val k1 = kotlin.math.floor(fmax * n / fs).toInt()
        var sum = 0.0
        var frames = 0
        var s = 0
        while (s + n <= minOf(ref.size, est.size)) {
            for (i in 0 until n) {
                fr[i] = ref[s + i] * w[i]
                fe[i] = est[s + i] * w[i]
            }
            fft.forward(fr, rr, ri)
            fft.forward(fe, er, ei)
            var energy = 0.0
            var acc = 0.0
            for (k in 0..n / 2) {
                val pr = (rr[k].toDouble() * rr[k] + ri[k].toDouble() * ri[k]) / (wsum * wsum)
                energy += pr
                if (k in k0..k1) {
                    val pe = (er[k].toDouble() * er[k] + ei[k].toDouble() * ei[k]) / (wsum * wsum)
                    val d = log10(pr + 1e-10) - log10(pe + 1e-10)
                    acc += d * d
                }
            }
            if (energy > 1e-8) {
                sum += sqrt(acc / (k1 - k0 + 1))
                frames++
            }
            s += hop
        }
        return sum / frames
    }

    /** Energy of [x] in [f0, f1] (one long FFT, power sum), linear. */
    fun bandEnergy(x: FloatArray, fs: Int, f0: Double, f1: Double): Double {
        var n = 1
        while (n < x.size) n = n shl 1
        val fft = org.seion.playback.dsp.core.RealFft(n)
        val buf = x.copyOf(n)
        val re = FloatArray(n / 2 + 1)
        val im = FloatArray(n / 2 + 1)
        fft.forward(buf, re, im)
        var e = 0.0
        for (k in 0..n / 2) {
            val f = k.toDouble() * fs / n
            if (f in f0..f1) e += re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
        }
        return e
    }
}
