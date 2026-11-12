package org.seion.playback.dsp.testutil

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Deterministic test signals. All are mono FloatArrays unless noted; [interleave] makes stereo. */
object Signals {
    fun sine(n: Int, fs: Int, hz: Double, amp: Double = 0.5, phase: Double = 0.0) = FloatArray(n) {
        (
            amp *
                sin(2 * PI * hz * it / fs + phase)
            ).toFloat()
    }

    fun white(n: Int, seed: Long, amp: Double = 0.3): FloatArray {
        val r = Random(seed)
        return FloatArray(n) { ((r.nextDouble() * 2 - 1) * amp).toFloat() }
    }

    /** Pink noise: white noise through a weighted sum of one-pole filters approximating −3 dB/oct, scaled to [rms]. */
    fun pink(n: Int, seed: Long, rms: Double = 0.1): FloatArray {
        val r = Random(seed)
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        var b3 = 0.0
        var b4 = 0.0
        var b5 = 0.0
        var b6 = 0.0
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val w = r.nextGaussian()
            b0 = 0.99886 * b0 + w * 0.0555179
            b1 = 0.99332 * b1 + w * 0.0750759
            b2 = 0.96900 * b2 + w * 0.1538520
            b3 = 0.86650 * b3 + w * 0.3104856
            b4 = 0.55000 * b4 + w * 0.5329522
            b5 = -0.7616 * b5 - w * 0.0168980
            out[i] = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
            b6 = w * 0.115926
        }
        return scaleRms(out, rms)
    }

    fun scaleRms(x: DoubleArray, rms: Double): FloatArray {
        val cur = sqrt(x.sumOf { it * it } / x.size)
        return FloatArray(x.size) { (x[it] * rms / cur).toFloat() }
    }

    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from).coerceAtLeast(1))
    }

    fun db(v: Double) = 20 * kotlin.math.log10(v.coerceAtLeast(1e-30))

    fun interleave(l: FloatArray, r: FloatArray) = FloatArray(l.size * 2) { if (it % 2 == 0) l[it / 2] else r[it / 2] }

    fun channel(x: FloatArray, ch: Int, c: Int) = FloatArray(x.size / ch) { x[it * ch + c] }

    fun stereo(mono: FloatArray) = interleave(mono, mono)

    /**
     * A synthetic full-band mix: kick, hats, a Karplus-Strong pluck, a saw pad and 1/f HF, mono, about
     * −14 dBFS RMS.
     */
    fun mix(n: Int, fs: Int, seed: Long): FloatArray {
        val r = Random(seed)
        val out = DoubleArray(n)
        val beat = fs / 2
        for (i in 0 until n) {
            val t = (i % beat).toDouble() / fs
            out[i] += 0.5 * sin(2 * PI * (50 + 80 * exp(-t * 30)) * t) * exp(-t * 8)
        }
        val hat = fs / 8
        for (i in 0 until n) {
            val t = (i % hat).toDouble() / fs
            out[i] += 0.12 * (r.nextDouble() * 2 - 1) * exp(-t * 120)
        }
        var period = fs / 220
        val ks = DoubleArray(period) { r.nextDouble() * 2 - 1 }
        var k = 0
        for (i in 0 until n) {
            if (i % (fs * 2) == 0) {
                period = fs / (196 + r.nextInt(200))
                for (j in 0 until period) ks[j % ks.size] = r.nextDouble() * 2 - 1
                k = 0
            }
            val a = ks[k % period % ks.size]
            val b = ks[(k + 1) % period % ks.size]
            ks[k % period % ks.size] = 0.996 * 0.5 * (a + b)
            out[i] += 0.25 * a
            k++
        }
        for (i in 0 until n) {
            var saw = 0.0
            for (f in doubleArrayOf(110.0, 164.8, 220.0)) saw += 2 * ((i * f / fs) % 1.0) - 1
            out[i] += 0.04 * saw
        }
        val hf = pink(n, seed + 1, 0.02)
        val hp = highpass(hf, fs, 4000.0)
        for (i in 0 until n) out[i] += hp[i]
        return scaleRms(out, 0.2)
    }

    /** Second-order Butterworth high-pass (bilinear), double precision; test-side reference filter. */
    fun highpass(x: FloatArray, fs: Int, fc: Double): FloatArray = biquad(x, butter(fs, fc, true))

    fun lowpass(x: FloatArray, fs: Int, fc: Double): FloatArray = biquad(x, butter(fs, fc, false))

    fun butter(fs: Int, fc: Double, high: Boolean): DoubleArray {
        val k = kotlin.math.tan(PI * fc / fs)
        val q = 1 / sqrt(2.0)
        val norm = 1 / (1 + k / q + k * k)
        return if (high) {
            doubleArrayOf(norm, -2 * norm, norm, 2 * (k * k - 1) * norm, (1 - k / q + k * k) * norm)
        } else {
            doubleArrayOf(k * k * norm, 2 * k * k * norm, k * k * norm, 2 * (k * k - 1) * norm, (1 - k / q + k * k) * norm)
        }
    }

    fun biquad(x: FloatArray, c: DoubleArray): FloatArray {
        var z1 = 0.0
        var z2 = 0.0
        return FloatArray(x.size) {
            val v = x[it].toDouble()
            val y = c[0] * v + z1
            z1 = c[1] * v - c[3] * y + z2
            z2 = c[2] * v - c[4] * y
            y.toFloat()
        }
    }

    /** Peak of the 2 kHz high-passed difference [out] − [ref], in dB relative to [ref]'s RMS (the click detector). */
    fun clickDb(out: FloatArray, ref: FloatArray, fs: Int, skip: Int = 0): Double {
        val d = FloatArray(out.size) { out[it] - ref[it] }
        val hp = highpass(highpass(d, fs, 2000.0), fs, 2000.0)
        var peak = 0.0
        for (i in skip until hp.size) peak = maxOf(peak, abs(hp[i].toDouble()))
        return db(peak / rms(ref, skip, ref.size))
    }

    /** Hann-windowed single-bin magnitude of [x] at [hz] (amplitude, a full-scale sine reads 1). */
    fun toneAmp(x: FloatArray, fs: Int, hz: Double, from: Int = 0, len: Int = x.size - from): Double {
        var re = 0.0
        var im = 0.0
        var wsum = 0.0
        for (i in 0 until len) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / len)
            val ph = 2 * PI * hz * i / fs
            re += x[from + i] * w * cos(ph)
            im -= x[from + i] * w * sin(ph)
            wsum += w
        }
        return 2 * sqrt(re * re + im * im) / wsum
    }

    /**
     * A codec-like low-pass: STFT (2048/512, √Hann WOLA) brickwall at [fc] with a 1 kHz cosine transition
     * centred on it (so fc is its −6 dB point), the band above at [floorDb] (−70 dB: a hard wall), and in [burstShare] of the frames the band above
     * kept at [burstDb] (MP3 "sfb21" bursts). Mono in, mono out, same length.
     */
    fun codecLowpass(
        x: FloatArray,
        fs: Int,
        fc: Double,
        floorDb: Double = -70.0,
        burstShare: Double = 0.05,
        burstDb: Double = -20.0,
        seed: Long = 1
    ): FloatArray {
        val n = 2048
        val hop = 512
        val fft = org.seion.playback.dsp.core.RealFft(n)
        val w = FloatArray(n) { kotlin.math.sqrt(0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val norm = w.sumOf { it.toDouble() * it } / hop
        val out = FloatArray(x.size + n)
        val frame = FloatArray(n)
        val re = FloatArray(n / 2 + 1)
        val im = FloatArray(n / 2 + 1)
        val r = Random(seed)
        val floor = Math.pow(10.0, floorDb / 20)
        val burst = Math.pow(10.0, burstDb / 20)
        var start = -n + hop
        while (start < x.size) {
            for (i in 0 until n) frame[i] = (if (start + i in x.indices) x[start + i] else 0f) * w[i]
            fft.forward(frame, re, im)
            val above = if (r.nextDouble() < burstShare) burst else floor
            for (k in 0..n / 2) {
                val f = k.toDouble() * fs / n
                val g =
                    when {
                        f < fc - 500 -> 1.0
                        f < fc + 500 -> above + (1 - above) * (0.5 + 0.5 * cos(PI * (f - (fc - 500)) / 1000))
                        else -> above
                    }
                re[k] = (re[k] * g).toFloat()
                im[k] = (im[k] * g).toFloat()
            }
            fft.inverse(re, im, frame)
            for (i in 0 until n) if (start + i >= 0) out[start + i] += (frame[i] * w[i] / norm).toFloat()
            start += hop
        }
        return out.copyOf(x.size)
    }

    /** A deep notch (band-stop, 4th-order Butterworth) at [hz]. */
    fun notch(x: FloatArray, fs: Int, hz: Double): FloatArray {
        val c = DoubleArray(20)
        val lo = org.seion.playback.dsp.core.Filters.butterworth(
            4,
            org.seion.playback.dsp.core.Filters.Kind.LOW,
            hz * 0.97,
            0.0,
            fs,
            c
        )
        var a = x
        for (s in 0 until lo) a = biquad(a, c.copyOfRange(s * 5, s * 5 + 5))
        val hi = org.seion.playback.dsp.core.Filters.butterworth(
            4,
            org.seion.playback.dsp.core.Filters.Kind.HIGH,
            hz * 1.03,
            0.0,
            fs,
            c
        )
        var b = x
        for (s in 0 until hi) b = biquad(b, c.copyOfRange(s * 5, s * 5 + 5))
        return FloatArray(x.size) { a[it] + b[it] }
    }

    /** 4th-order Butterworth low-pass (the "dark mix"). */
    fun lowpass4(x: FloatArray, fs: Int, hz: Double): FloatArray {
        val c = DoubleArray(20)
        val n = org.seion.playback.dsp.core.Filters.butterworth(4, org.seion.playback.dsp.core.Filters.Kind.LOW, hz, 0.0, fs, c)
        var a = x
        for (s in 0 until n) a = biquad(a, c.copyOfRange(s * 5, s * 5 + 5))
        return a
    }

    /** Zero-phase Butterworth (forward-backward), low ([high] = false) or high pass of [order]. */
    fun filtfilt(x: FloatArray, fs: Int, hz: Double, order: Int, high: Boolean): FloatArray {
        val c = DoubleArray(40)
        val kind = if (high) org.seion.playback.dsp.core.Filters.Kind.HIGH else org.seion.playback.dsp.core.Filters.Kind.LOW
        val n = org.seion.playback.dsp.core.Filters.butterworth(order, kind, hz, 0.0, fs, c)
        var a = x
        for (s in 0 until n) a = biquad(a, c.copyOfRange(s * 5, s * 5 + 5))
        a = a.reversedArray()
        for (s in 0 until n) a = biquad(a, c.copyOfRange(s * 5, s * 5 + 5))
        return a.reversedArray()
    }

    /** Castanet-like test signal: 30 ms noise bursts, 4 ms decay, every 250 ms from 0.3 s, over a soft pad. */
    fun castanets(n: Int, fs: Int, seed: Long): FloatArray {
        val r = Random(seed)
        val x = DoubleArray(n)
        var s = (0.3 * fs).toInt()
        while (s < n - fs / 4) {
            val len = (0.03 * fs).toInt()
            for (i in 0 until len) x[s + i] += r.nextGaussian() * exp(-i / (0.004 * fs)) * 0.5
            s += fs / 4
        }
        for (i in 0 until n) x[i] += 0.02 * r.nextGaussian() * sin(2 * PI * i / fs * 0.5).let { it * it }
        return FloatArray(n) { x[it].toFloat() }
    }
}
