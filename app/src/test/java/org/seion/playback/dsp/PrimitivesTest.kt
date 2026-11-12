package org.seion.playback.dsp

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.playback.dsp.core.Biquad
import org.seion.playback.dsp.core.EnvFollower
import org.seion.playback.dsp.core.FastMath
import org.seion.playback.dsp.core.Filters
import org.seion.playback.dsp.core.Halfband2x
import org.seion.playback.dsp.core.PhasorTable
import org.seion.playback.dsp.core.RealFft
import org.seion.playback.dsp.core.Rng
import org.seion.playback.dsp.core.TpUpsampler
import org.seion.playback.dsp.core.Wola

/** The DSP primitives against their specified thresholds, the WOLA identity and double-precision biquads. */
class PrimitivesTest {
    private val rates = intArrayOf(22_050, 44_100, 48_000, 88_200, 96_000, 192_000)

    /** Runs [x] through [sections] biquads; returns the output. */
    private fun run(c: DoubleArray, sections: Int, x: DoubleArray): DoubleArray {
        val bq = Array(sections) { Biquad(1).apply { set(c, it * 5) } }
        return DoubleArray(x.size) { i ->
            var v = x[i]
            for (b in bq) v = b.process(v, 0)
            v
        }
    }

    /** |DFT| in dB of the impulse response at [hz]. */
    private fun irDb(h: DoubleArray, hz: Double, fs: Int): Double {
        var re = 0.0
        var im = 0.0
        for (n in h.indices) {
            re += h[n] * cos(2 * PI * hz * n / fs)
            im -= h[n] * sin(2 * PI * hz * n / fs)
        }
        return 10 * log10(re * re + im * im)
    }

    @Test fun `biquads match their analytic response within 0_01 dB, are stable and have finite energy at every rate`() {
        val c = DoubleArray(40)
        for (fs in rates) {
            val designs =
                listOf(
                    "shelf low" to {
                        Filters.shelf(fs, 121.0, 5.5, true, 0.3, c)
                        1
                    },
                    "shelf high" to {
                        Filters.shelf(fs, minOf(9000.0, 0.4 * fs), 1.5, false, 0.7, c)
                        1
                    },
                    "lp2 120" to { Filters.butterworth(2, Filters.Kind.LOW, 120.0, 0.0, fs, c) },
                    "hp2 5k" to { Filters.butterworth(2, Filters.Kind.HIGH, minOf(5000.0, 0.4 * fs), 0.0, fs, c) },
                    "bp2 2.5-6k" to { Filters.butterworth(2, Filters.Kind.BAND, 2500.0, minOf(6000.0, 0.45 * fs), fs, c) },
                    "bp4 8-16k" to { Filters.butterworth(4, Filters.Kind.BAND, minOf(8000.0, 0.3 * fs), minOf(16000.0, 0.45 * fs), fs, c) },
                    "lp1 700" to { Filters.butterworth(1, Filters.Kind.LOW, 700.0, 0.0, fs, c) }
                )
            for ((name, design) in designs) {
                val sections = design()
                for (s in 0 until sections) {
                    // poles inside the unit circle: |a2| < 1 and |a1| < 1 + a2
                    val a1 = c[s * 5 + 3]
                    val a2 = c[s * 5 + 4]
                    assertTrue("$name $fs unstable", abs(a2) < 1 && abs(a1) < 1 + a2 + 1e-12)
                }
                val ir = run(c, sections, DoubleArray(fs) { if (it == 0) 1.0 else 0.0 })
                val energy = ir.sumOf { it * it }
                assertTrue("$name $fs energy $energy", energy.isFinite() && abs(ir.last()) < 1e-9)
                for (hz in doubleArrayOf(50.0, 121.0, 1000.0, 4000.0, 9000.0)) {
                    if (hz >= fs / 2.0) continue
                    val analytic = Filters.cascadeDb(c, sections, hz, fs)
                    if (analytic < -80) continue
                    assertEquals("$name $fs $hz", analytic, irDb(ir, hz, fs), 0.01)
                }
            }
        }
    }

    @Test fun `Butterworth designs match scipy's reference points`() {
        val c = DoubleArray(40)
        // scipy butter(2, [2500, 6000], 'band', fs=48000) at 4/5.5/9/12 kHz: 0 / -1.4 / -14.4 / -22.9 dB.
        val n = Filters.butterworth(2, Filters.Kind.BAND, 2500.0, 6000.0, 48_000, c)
        assertEquals(2, n)
        assertEquals(0.0, Filters.cascadeDb(c, n, 4000.0, 48_000), 0.1)
        assertEquals(-1.4, Filters.cascadeDb(c, n, 5500.0, 48_000), 0.1)
        assertEquals(-14.4, Filters.cascadeDb(c, n, 9000.0, 48_000), 0.1)
        assertEquals(-22.9, Filters.cascadeDb(c, n, 12000.0, 48_000), 0.1)
        // -3.01 dB at the cutoff for low and high pass.
        val l = Filters.butterworth(2, Filters.Kind.LOW, 120.0, 0.0, 48_000, c)
        assertEquals(-3.0103, Filters.cascadeDb(c, l, 120.0, 48_000), 1e-3)
        val h = Filters.butterworth(1, Filters.Kind.HIGH, 700.0, 0.0, 48_000, c)
        assertEquals(-3.0103, Filters.cascadeDb(c, h, 700.0, 48_000), 1e-3)
    }

    @Test fun `first-order low and high pass are complementary`() {
        val lp = DoubleArray(5)
        val hp = DoubleArray(5)
        for (fs in rates) {
            Filters.butterworth(1, Filters.Kind.LOW, 700.0, 0.0, fs, lp)
            Filters.butterworth(1, Filters.Kind.HIGH, 700.0, 0.0, fs, hp)
            val x = DoubleArray(2000) { Random(fs.toLong()).nextGaussian() }
            val r = Random(1)
            for (i in x.indices) x[i] = r.nextGaussian()
            val a = run(lp, 1, x)
            val b = run(hp, 1, x)
            for (i in x.indices) assertEquals(x[i], a[i] + b[i], 1e-12)
        }
    }

    @Test fun `envelope follower time constants within 5 percent`() {
        val fs = 48_000
        val e = EnvFollower().apply { configure(fs, 0.010, 0.150) }
        var blocks = 0
        while (e.feed(1.0) < 1 - 1 / Math.E) blocks++
        assertEquals(0.010, (blocks + 1) * 32.0 / fs, 0.010 * 0.05 + 32.0 / fs)
        repeat(200) { e.feed(1.0) } // settle at 1
        blocks = 0
        while (e.feed(0.0) > 1 / Math.E) blocks++
        assertEquals(0.150, (blocks + 1) * 32.0 / fs, 0.150 * 0.05 + 32.0 / fs)
    }

    // Up31 and Down95 Kaiser halfbands. Built from the constants the exciter uses (CrystalX.UP_TAPS/DOWN_TAPS), so a
    // shorter filter in the chain fails here, not only a literal.
    @Test fun `Up31 is flat to 0_10 fs2 and rejects 80 dB from 0_345 fs2`() {
        val h = Halfband2x(CrystalX.UP_TAPS)
        var ripple = 0.0
        var f = 0.0
        while (f <= 0.10) {
            ripple = maxOf(ripple, abs(h.magnitudeDb(f)))
            f += 0.001
        }
        assertTrue("ripple $ripple", ripple <= 0.01)
        var stop = Double.NEGATIVE_INFINITY
        f = 0.345 // measured: 80 dB from 0.342 fs2 (−79.1 dB at 0.340); the feed's images start at 0.43 fs2
        while (f <= 0.5) {
            stop = maxOf(stop, h.magnitudeDb(f))
            f += 0.0005
        }
        assertTrue("stopband $stop", stop <= -80)
    }

    @Test fun `Down95 is flat to 0_22 fs2 and rejects 80 dB from 0_28 fs2`() {
        val h = Halfband2x(CrystalX.DOWN_TAPS)
        var ripple = 0.0
        var f = 0.0
        while (f <= 0.22) {
            ripple = maxOf(ripple, abs(h.magnitudeDb(f)))
            f += 0.0005
        }
        assertTrue("ripple $ripple", ripple <= 0.01)
        var stop = Double.NEGATIVE_INFINITY
        f = 0.28
        while (f <= 0.5) {
            stop = maxOf(stop, h.magnitudeDb(f))
            f += 0.0002
        }
        assertTrue("stopband $stop", stop <= -80)
        assertEquals("halfband zeros exploited", 49, h.coefficients.count { it != 0.0 })
    }

    @Test fun `up then down by 2 returns a passband sine delayed by the filters`() {
        val up = Halfband2x(CrystalX.UP_TAPS)
        val down = Halfband2x(CrystalX.DOWN_TAPS)
        val fs = 48_000
        val n = 4000
        val x = DoubleArray(n) { 0.5 * sin(2 * PI * 3000.0 * it / fs) }
        val two = DoubleArray(2)
        val y = DoubleArray(n)
        for (i in 0 until n) {
            up.up(x[i], 0, two, 0)
            y[i] = down.down(two[0], two[1], 0)
        }
        // group delay: (31 − 1)/2 + (95 − 1)/2 = 62 samples at 2× = 31 base samples
        val d = 31
        var err = 0.0
        for (i in 500 until n) err = maxOf(err, abs(y[i] - x[i - d]))
        assertTrue("err $err", err < 1e-3)
    }

    @Test fun `the BS_1770 interpolator reads the true peak of fs over 4 at 45 degrees`() {
        val tp = TpUpsampler(1).apply { configure(48_000) }
        var peak = 0.0
        var sample = 0.0
        for (i in 0 until 400) {
            val x = sin(PI / 2 * i + PI / 4)
            sample = maxOf(sample, abs(x))
            val v = tp.push(x, 0)
            if (i > 20) peak = maxOf(peak, v)
        }
        assertEquals(0.7071, sample, 1e-3)
        // The published coefficients read 1.0052 here (phase-2 response 1.0257 at fs/4). Cross-checked with the same
        // table in tools/dsp/frozen_vectors.py.
        assertEquals(1.0052, peak, 0.0005)
    }

    @Test fun `real FFT matches the naive DFT and inverts`() {
        for (size in intArrayOf(512, 4096)) {
            val fft = RealFft(size)
            val r = Random(size.toLong())
            val x = FloatArray(size) { (r.nextGaussian() * 0.3).toFloat() }
            val re = FloatArray(size / 2 + 1)
            val im = FloatArray(size / 2 + 1)
            fft.forward(x, re, im)
            var maxMag = 0.0
            var maxErr = 0.0
            for (k in 0..size / 2 step (if (size > 512) 37 else 1)) {
                var dr = 0.0
                var di = 0.0
                for (t in 0 until size) {
                    dr += x[t] * cos(2 * PI * k * t / size)
                    di -= x[t] * sin(2 * PI * k * t / size)
                }
                maxMag = maxOf(maxMag, sqrt(dr * dr + di * di))
                maxErr = maxOf(maxErr, abs(dr - re[k]), abs(di - im[k]))
            }
            assertTrue("size $size rel err ${maxErr / maxMag}", maxErr / maxMag <= 1e-5)
            val back = FloatArray(size)
            fft.inverse(re, im, back)
            var e = 0.0
            for (t in 0 until size) e = maxOf(e, abs(back[t] - x[t]).toDouble())
            assertTrue("identity $e", e <= 1e-6)
        }
    }

    // Analysis -> synthesis identity <= 1e-6 at 44.1/48 kHz, with the streaming latency N − 1 = 511.
    @Test fun `WOLA identity within 1e-6 and latency 511`() {
        for (fs in intArrayOf(44_100, 48_000)) {
            val w = Wola(512, 128, 2)
            val r = Random(fs.toLong())
            val n = 20_000
            val x = FloatArray(n * 2) { (r.nextGaussian() * 0.3).toFloat() }
            val y = FloatArray(n * 2)
            for (i in 0 until n) {
                w.push(x, i * 2, 2, null)
                y[i * 2] = w.output(0)
                y[i * 2 + 1] = w.output(1)
            }
            var err = 0.0
            for (i in 511 until n) for (c in 0..1) err = maxOf(err, abs(y[i * 2 + c] - x[(i - 511) * 2 + c]).toDouble())
            assertTrue("fs $fs identity error $err", err <= 1e-6)
            for (i in 0 until 511 * 2) assertEquals(0f, y[i], 1e-6f)
        }
    }

    @Test fun `xorshift32 golden sequence and phasor table`() {
        // The same sequence as tools/dsp/frozen_vectors.py's xorshift32(12345) (cross-language golden values).
        val rng = Rng(12345)
        assertArrayEquals(intArrayOf(-958040966, 1697253807, -1478455392, 1955480042, 718842323), IntArray(5) { rng.next() })
        assertEquals(1f, PhasorTable.cos[0], 0f)
        assertEquals(1f, PhasorTable.sin[256], 1e-7f)
    }

    @Test fun `fast tanh, log2 and exp2 meet their error bounds`() {
        var e = 0.0
        var x = -6.0
        while (x <= 6.0) {
            e = maxOf(e, abs(FastMath.tanh(x) - StrictMath.tanh(x)))
            assertEquals(-FastMath.tanh(x), FastMath.tanh(-x), 0.0)
            x += 0.0003
        }
        assertTrue("tanh $e", e <= 1e-4)
        assertEquals(1.0, FastMath.tanh(40.0), 0.0)
        var el = 0.0
        var v = 1e-12
        while (v < 1e12) {
            el = maxOf(el, abs(FastMath.log2(v) - StrictMath.log(v) / StrictMath.log(2.0)))
            v *= 1.0137
        }
        assertTrue("log2 $el", el <= 2e-5)
        var ee = 0.0
        var t = -40.0
        while (t < 40) {
            ee = maxOf(ee, abs(FastMath.exp2(t) / StrictMath.pow(2.0, t) - 1))
            t += 0.0071
        }
        assertTrue("exp2 $ee", ee <= 2e-5)
        assertEquals(-6.0206, FastMath.db10(0.25), 1e-3)
        assertEquals(0.5, FastMath.fromDb20(-6.0206), 1e-5)
    }

    // Double biquads: the 121 Hz shelf at 192 kHz, TDF-II vs a direct-form-I double reference.
    @Test fun `double biquad drift stays under 1e-9`() {
        val c = DoubleArray(5)
        Filters.shelf(192_000, 121.0, 5.5, true, 0.3, c)
        val bq = Biquad(1).apply { set(c) }
        val r = Random(5)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        var err = 0.0
        repeat(192_000) {
            val x = r.nextGaussian() * 0.3
            val a = bq.process(x, 0)
            val b = c[0] * x + c[1] * x1 + c[2] * x2 - c[3] * y1 - c[4] * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = b
            err = maxOf(err, abs(a - b))
        }
        assertTrue("drift $err", err <= 1e-9)
    }

    // A state left after a burst decays to exactly 0 with the per-block flush.
    @Test fun `denormal flush brings recursive state to exactly zero`() {
        val c = DoubleArray(10)
        val n = Filters.butterworth(2, Filters.Kind.LOW, 120.0, 0.0, 48_000, c)
        val bq = Biquad(1).apply { set(c) }
        repeat(1000) { bq.process(if (it < 100) 1.0 else 0.0, 0) }
        var blocks = 0
        while (!bq.isZero() && blocks < 100_000) {
            repeat(256) { bq.process(0.0, 0) }
            bq.flushDenormals()
            blocks++
        }
        assertTrue("blocks $blocks", bq.isZero())
        assertEquals(1, n)
    }
}
