package org.seion.playback.dsp

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.playback.dsp.core.Filters
import org.seion.playback.dsp.testutil.Meters
import org.seion.playback.dsp.testutil.Signals

/** CrystalX+: frozen stage gains, the static contour, the stereo stage, exciter purity, THD, the blend and route morphs. */
class CrystalXTest {
    private val g = 10.0.pow(-7.5 / 20)

    private fun chain(fs: Int, ch: Int, v: Voicing = Voicing.HEADPHONES) = CrystalX().apply {
        setVoicing(v)
        configure(fs, ch)
    }

    private fun run(cx: CrystalX, x: FloatArray, ch: Int): FloatArray {
        val out = FloatArray(x.size)
        val frames = x.size / ch
        val inB = FloatArray(256 * ch)
        val outB = FloatArray(256 * ch)
        var f = 0
        while (f < frames) {
            val n = minOf(256, frames - f)
            System.arraycopy(x, f * ch, inB, 0, n * ch)
            cx.process(inB, outB, n)
            System.arraycopy(outB, 0, out, f * ch, n * ch)
            f += n
        }
        return out
    }

    private fun db(v: Double) = 20 * log10(v)

    /** |1 + s·(H − 1)| of the 121 Hz shelf at [hz]. */
    private fun blendDb(fs: Int, hz: Double, s: Double): Double {
        val c = DoubleArray(5)
        Filters.shelf(fs, 121.0, 5.5, true, 0.3, c)
        val o = DoubleArray(2)
        Filters.response(c[0], c[1], c[2], c[3], c[4], hz, fs, o)
        return db(hypot(1 + s * (o[0] - 1), s * o[1]))
    }

    private fun sectionDb(c: DoubleArray, n: Int, hz: Double, fs: Int) = Filters.cascadeDb(c, n, hz, fs)

    /** The static (small-signal, mono) gain of the chain: pre-gain, shelves at full amount, makeup. */
    private fun staticDb(fs: Int, hz: Double, s: Double = 1.0): Double {
        val c = DoubleArray(5)
        Filters.shelf(fs, minOf(9000.0, 0.4 * fs), 1.5, false, 0.7, c)
        return -3.0 + sectionDb(c, 1, hz, fs) + blendDb(fs, hz, s) + 1.0
    }

    // The 13 linear stage gains at 10 frequencies match tools/dsp/frozen_vectors.py within 1e-4 dB.
    @Test fun `stage gains match the frozen vector`() {
        val json = Json.parseToJsonElement(File("src/test/resources/dsp/frozen-crystalx.json").readText()).jsonObject
        val fs = json["fs"]!!.jsonPrimitive.int
        val freqs = json["freqs"]!!.jsonArray.map { it.jsonPrimitive.double }
        val stages = json["stagesDb"]!!.jsonObject
        val c = DoubleArray(20)
        fun exp(name: String) = stages[name]!!.jsonArray.map { it.jsonPrimitive.double }
        fun check(name: String, actual: (Double) -> Double) {
            val e = exp(name)
            freqs.forEachIndexed { i, f -> assertEquals("$name at $f", e[i], actual(f), 1e-4) }
        }
        check("preGain") { -3.0 }
        check("makeup") { 1.0 }
        Filters.shelf(fs, 9000.0, 1.5, false, 0.7, c)
        val hs = c.copyOf(5)
        check("highShelf") { sectionDb(hs, 1, it, fs) }
        check("lowShelfHeadphones") { blendDb(fs, it, 1.0) }
        check("lowShelfSpeaker") { blendDb(fs, it, 0.55) }
        val n1 = Filters.butterworth(2, Filters.Kind.LOW, 120.0, 0.0, fs, c)
        val sc = c.copyOf(5 * n1)
        check("sidechainLp") { sectionDb(sc, n1, it, fs) }
        val n2 = Filters.butterworth(2, Filters.Kind.BAND, 2500.0, 6000.0, fs, c)
        val bp = c.copyOf(5 * n2)
        check("exciterBandPass") { sectionDb(bp, n2, it, fs) }
        Filters.butterworth(2, Filters.Kind.HIGH, 5000.0, 0.0, fs, c)
        val hp = c.copyOf(5)
        check("exciterHighPass") { sectionDb(hp, 1, it, fs) }
        Filters.butterworth(1, Filters.Kind.LOW, 700.0, 0.0, fs, c)
        val lp1 = c.copyOf(5)
        Filters.butterworth(1, Filters.Kind.HIGH, 700.0, 0.0, fs, c)
        val hp1 = c.copyOf(5)
        check("crossfeedLp") { sectionDb(lp1, 1, it, fs) }
        check("crossfeedHp") { sectionDb(hp1, 1, it, fs) }
        val o = DoubleArray(2)
        val p = DoubleArray(2)
        check("crossfeedMono") {
            Filters.response(lp1[0], lp1[1], lp1[2], lp1[3], lp1[4], it, fs, o)
            Filters.response(hp1[0], hp1[1], hp1[2], hp1[3], hp1[4], it, fs, p)
            db(hypot(1 + g * (o[0] + p[0]), g * (o[1] + p[1])) / (1 + g))
        }
        check("crossfeedHardLeftRatio") {
            Filters.response(lp1[0], lp1[1], lp1[2], lp1[3], lp1[4], it, fs, o)
            Filters.response(hp1[0], hp1[1], hp1[2], hp1[3], hp1[4], it, fs, p)
            db(g * hypot(o[0], o[1]) / hypot(1 + g * p[0], g * p[1]))
        }
        Filters.butterworth(2, Filters.Kind.HIGH, 300.0, 0.0, fs, c)
        val wh = c.copyOf(5)
        check("widthHighPass") { sectionDb(wh, 1, it, fs) }
    }

    // ISO 226:2003 table: frequency, alpha_f, L_U, T_f.
    private val isoF =
        doubleArrayOf(
            20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0,
            200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0,
            2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0
        )
    private val isoA =
        doubleArrayOf(
            0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330,
            0.315, 0.301, 0.288, 0.276, 0.267, 0.259, 0.253, 0.250, 0.246, 0.244,
            0.243, 0.243, 0.243, 0.242, 0.242, 0.245, 0.254, 0.271, 0.301
        )
    private val isoL =
        doubleArrayOf(
            -31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5,
            -3.1, -2.0, -1.1, -0.4, 0.0, 0.3, 0.5, 0.0, -2.7, -4.1,
            -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2, -10.7, -3.1
        )
    private val isoT =
        doubleArrayOf(
            78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9,
            14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5, 1.7,
            -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3
        )

    private fun contour(phon: Double) = DoubleArray(isoF.size) { i ->
        val af = 4.47e-3 * (10.0.pow(0.025 * phon) - 1.15) + (0.4 * 10.0.pow((isoT[i] + isoL[i]) / 10 - 9)).pow(isoA[i])
        10 / isoA[i] * log10(af) - isoL[i] + 94
    }

    // The static contour vs ISO 226 80→70 phon, ±0.5 dB to 2 kHz and ±1.0 dB to 12.5 kHz;
    // the implementation vs the analytic biquads ±0.05 dB at every rate.
    @Test fun `the static contour follows ISO 226 80 to 70 phon`() {
        for (fs in intArrayOf(44_100, 48_000)) {
            val a = contour(70.0)
            val b = contour(80.0)
            val i1k = isoF.indexOfFirst { it == 1000.0 }
            val comp = DoubleArray(isoF.size) { (a[it] - 70) - (b[it] - 80) }
            val k1k = comp[i1k]
            val chain1k = staticDb(fs, 1000.0)
            for (i in isoF.indices) {
                val expected = comp[i] - k1k
                val actual = staticDb(fs, isoF[i]) - chain1k
                val tol = if (isoF[i] <= 2000) 0.5 else 1.0
                assertEquals("fs $fs at ${isoF[i]} Hz", expected, actual, tol)
            }
        }
    }

    @Test fun `the chain's small-signal response matches the analytic biquads at every rate`() {
        for (fs in intArrayOf(22_050, 44_100, 48_000, 88_200, 96_000, 192_000)) {
            for (hz in doubleArrayOf(40.0, 121.0, 500.0, 1000.0, 2000.0, 8000.0)) {
                if (hz > 0.35 * fs) continue
                val cx = chain(fs, 2)
                val n = fs
                val tone = Signals.sine(n, fs, hz, amp = 0.003) // −50 dBFS: the dynamics stay at a = 1, h ≈ 0
                val out = run(cx, Signals.stereo(tone), 2)
                val from = n / 2
                val m = minOf(n / 2 - 10, ((n / 2) / (fs / hz)).toInt() * (fs / hz).toInt())
                val gain = db(Signals.toneAmp(Signals.channel(out, 2, 0), fs, hz, from, m) / Signals.toneAmp(tone, fs, hz, from, m))
                assertEquals("fs $fs at $hz Hz", staticDb(fs, hz), gain, 0.05)
            }
        }
    }

    // Mono flat ±0.05 dB 20 Hz–20 kHz; hard-left R re L −7.5 ± 1 dB ≤ 150 Hz, ≤ −23 dB at 3 kHz,
    // ≤ −25 dB from 4 kHz.
    @Test fun `crossfeed keeps mono flat and crossfeeds hard-left bass`() {
        val fs = 48_000
        for (hz in doubleArrayOf(20.0, 100.0, 700.0, 3000.0, 10_000.0, 20_000.0)) {
            val tone = Signals.sine(fs * 2, fs, hz, amp = 0.003)
            val hp = run(chain(fs, 2, Voicing.HEADPHONES), Signals.stereo(tone), 2)
            val line = run(chain(fs, 2, Voicing.LINE), Signals.stereo(tone), 2)
            val len = fs
            val d = db(
                Signals.toneAmp(Signals.channel(hp, 2, 0), fs, hz, fs / 2, len) /
                    Signals.toneAmp(Signals.channel(line, 2, 0), fs, hz, fs / 2, len)
            )
            assertEquals("mono at $hz", 0.0, d, 0.05)
        }
        for ((hz, check) in listOf(
            50.0 to "lf",
            100.0 to "lf",
            150.0 to "lf",
            3000.0 to "3k",
            4000.0 to "4k",
            8000.0 to "4k",
            16000.0 to "4k"
        )) {
            val tone = Signals.sine(fs * 2, fs, hz, amp = 0.003)
            val out = run(chain(fs, 2), Signals.interleave(tone, FloatArray(tone.size)), 2)
            val l = Signals.toneAmp(Signals.channel(out, 2, 0), fs, hz, fs / 2, fs)
            val r = Signals.toneAmp(Signals.channel(out, 2, 1), fs, hz, fs / 2, fs)
            val ratio = db(r / l)
            when (check) {
                "lf" -> assertEquals("R re L at $hz", -7.5, ratio, 1.0)
                "3k" -> assertTrue("R re L at $hz: $ratio", ratio <= -23)
                else -> assertTrue("R re L at $hz: $ratio", ratio <= -25)
            }
        }
    }

    /** Strongest component of [y] (mono, after [skip]) that is not a harmonic of [f0] (± 40 Hz), in dBFS. */
    private fun worstNonHarmonic(y: FloatArray, fs: Int, f0: Double, skip: Int): Double {
        val n = 32_768
        val fft = org.seion.playback.dsp.core.RealFft(n)
        val w = DoubleArray(n) { i -> 0.42 - 0.5 * kotlin.math.cos(2 * PI * i / n) + 0.08 * kotlin.math.cos(4 * PI * i / n) }
        val buf = FloatArray(n) { (y[skip + it] * w[it]).toFloat() }
        val re = FloatArray(n / 2 + 1)
        val im = FloatArray(n / 2 + 1)
        fft.forward(buf, re, im)
        val wsum = w.sum()
        var worst = -400.0
        for (k in 1..n / 2) {
            val f = k.toDouble() * fs / n
            if (f < 20) continue
            var harmonic = false
            var h = 0
            while (h * f0 <= fs / 2.0 + 40) {
                if (abs(f - h * f0) <= 40) harmonic = true
                h++
            }
            if (harmonic) continue
            val a = 2 * hypot(re[k].toDouble(), im[k].toDouble()) / wsum
            worst = maxOf(worst, db(a + 1e-30))
        }
        return worst
    }

    private fun exciterH(fs: Int, f0: Double, oversample: Boolean): FloatArray {
        val e = Exciter(oversample).apply { configure(fs) }
        val x = Signals.sine(fs * 2, fs, f0, amp = 0.5)
        return FloatArray(x.size) { e.process(x[it].toDouble(), 0).toFloat() }
    }

    // Non-harmonic components measured on h (before the −20 dB gain and the duck),
    // 4.0/5.5 kHz at −6 dBFS ≤ −60 dBFS, 9/12 kHz controls; the 1× shaper at 5.5 kHz FAILS by ≥ 5 dB; the stage output
    // stays ≤ −90 dBFS; 1 kHz through the full chain keeps its static gain ±0.05 dB.
    @Test fun `the oversampled exciter does not alias`() {
        for (fs in intArrayOf(44_100, 48_000)) {
            for (f0 in doubleArrayOf(4000.0, 5500.0, 9000.0, 12_000.0)) {
                val worst = worstNonHarmonic(exciterH(fs, f0, true), fs, f0, fs / 2)
                // At 5.5 kHz / 48 kHz the chosen Down95 measures far below −100 dBFS, a 31-tap downsampler
                // −72.5; −75 pins the chosen filter through the real exciter, not only in the primitive test
                val bound = if (fs == 48_000 && f0 == 5500.0) -75.0 else -60.0
                assertTrue("fs $fs tone $f0: $worst dBFS (bound $bound)", worst <= bound)
            }
            val oneX = worstNonHarmonic(exciterH(fs, 5500.0, false), fs, 5500.0, fs / 2)
            assertTrue("1x at 5.5 kHz must fail by >= 5 dB: $oneX dBFS", oneX >= -55)
            for (f0 in doubleArrayOf(4000.0, 5500.0)) {
                val tone = Signals.sine(fs * 2, fs, f0, amp = 0.5)
                val out = Signals.channel(run(chain(fs, 2, Voicing.LINE), Signals.stereo(tone), 2), 2, 0)
                val worst = worstNonHarmonic(out, fs, f0, fs / 2)
                assertTrue("stage output fs $fs $f0: $worst", worst <= -90)
            }
            val tone = Signals.sine(fs * 2, fs, 1000.0, amp = 0.5)
            val out = Signals.channel(run(chain(fs, 2), Signals.stereo(tone), 2), 2, 0)
            val gain = db(Signals.toneAmp(out, fs, 1000.0, fs / 2, fs) / Signals.toneAmp(tone, fs, 1000.0, fs / 2, fs))
            assertEquals("1 kHz static gain", staticDb(fs, 1000.0), gain, 0.05)
        }
    }

    // 1 kHz at −12 dBFS through the full chain (headphones): THD+N ≤ −60 dB.
    @Test fun `1 kHz at -12 dBFS stays under -60 dB THD+N`() {
        val fs = 48_000
        val tone = Signals.sine(fs * 2, fs, 1000.0, amp = 10.0.pow(-12 / 20.0))
        val out = Signals.channel(run(chain(fs, 2), Signals.stereo(tone), 2), 2, 0)
        val thd = Meters.thdN(out, fs, 1000.0, fs / 2, 32_768)
        assertTrue("THD+N ${db(thd)} dB", db(thd) <= -60)
    }

    // The parallel blend is monotone in a and never dips below 0 dB; loud bass engages the duck; a step of the
    // amount leaves no HF discontinuity.
    @Test fun `the dynamic low shelf blends cleanly`() {
        for (fs in intArrayOf(44_100, 48_000)) {
            var f = 10.0
            while (f < 20_000) {
                var prev = -1.0
                for (k in 0..20) {
                    val v = blendDb(fs, f, k / 20.0)
                    assertTrue("blend dips at $f Hz", v >= -1e-9)
                    assertTrue("blend not monotone at $f Hz", v >= prev - 1e-12)
                    prev = v
                }
                f *= 1.1
            }
        }
        val fs = 48_000
        // A 60 Hz tone whose sidechain peak sits 10 dB over the threshold: at least 8 dB of the 10 dB range engaged.
        val loud = Signals.sine(fs * 2, fs, 60.0, amp = 10.0.pow(-10 / 20.0) / 10.0.pow(-3 / 20.0))
        val cx = chain(fs, 2)
        run(cx, Signals.stereo(loud), 2)
        assertTrue("amount ${cx.bassAmount}", cx.bassAmount <= 0.2)
        // An amount step 1 -> 0 -> 1 on pink noise (forced at control rate): no HF discontinuity above −80 dBFS. The
        // exciter is off here so the chain is linear in a and the blended reference is exact: this tests the
        // parallel-blend mechanism (no coefficient switching, no zipper); the exciter's own duck adapts smoothly to the new level.
        val pink = Signals.stereo(Signals.pink(fs * 3, 11, 0.1))
        fun runForced(step: Boolean): FloatArray {
            val c = chain(fs, 2).apply { exciterEnabled = false }
            val out = FloatArray(pink.size)
            val inB = FloatArray(512)
            val outB = FloatArray(512)
            for (f in 0 until fs * 3 step 240) { // 240 divides fs: the steps are set exactly at fs and 2·fs
                val n = minOf(240, fs * 3 - f)
                c.forcedAmount = if (step && f in fs until 2 * fs) 0.0 else 1.0
                System.arraycopy(pink, f * 2, inB, 0, n * 2)
                c.process(inB, outB, n)
                System.arraycopy(outB, 0, out, f * 2, n * 2)
            }
            return Signals.channel(out, 2, 0)
        }
        // Reference: the two constant-amount runs blended with the same amount trajectory (the step reaches the audio
        // one control block after it is set and ramps over the next 32 samples).
        val a1 = runForced(false)
        val stepped = runForced(true)
        val a0 =
            run(
                chain(fs, 2).apply {
                    forcedAmount = 0.0
                    exciterEnabled = false
                },
                pink,
                2
            ).let { Signals.channel(it, 2, 0) }
        fun amount(i: Int): Float {
            fun edge(at: Int, from: Float, to: Float): Float? = when {
                i < at + 32 -> null
                i < at + 64 -> from + (to - from) * ((i - at - 32 + 1).toFloat() / 32)
                else -> null
            }
            return when {
                i < fs + 32 -> 1f
                i < fs + 64 -> edge(fs, 1f, 0f)!!
                i < 2 * fs + 32 -> 0f
                i < 2 * fs + 64 -> edge(2 * fs, 0f, 1f)!!
                else -> 1f
            }
        }
        val diff = FloatArray(a1.size) { stepped[it] - (amount(it) * a1[it] + (1 - amount(it)) * a0[it]) }
        val hf = Signals.highpass(Signals.highpass(diff, fs, 4000.0), fs, 4000.0)
        var peak = 0.0
        var at = 0
        for (i in fs / 2 until hf.size) {
            if (abs(hf[i].toDouble()) > peak) {
                peak = abs(hf[i].toDouble())
                at = i
            }
        }
        assertTrue("HF discontinuity ${db(peak)} dBFS at $at", db(peak) <= -80)
    }

    // Route morphs during the mix: no click, targets reached in 30 ms. Order HEADPHONES → LINE → SPEAKER so
    // each morph moves one stereo stage (crossfeed, then width): a direct HEADPHONES → SPEAKER morph adds the smooth
    // bilinear term (w − 1)·x·HP(XF) mid-morph, which a linear crossfade reference cannot represent (not a click).
    @Test fun `voicing morphs are click-free and settle in 30 ms`() {
        val fs = 48_000
        val mix = Signals.interleave(Signals.mix(fs * 3, fs, 3), Signals.mix(fs * 3, fs, 4))

        // The exciter is off so the stereo stage and the shelf are linear in the morphing parameters and the
        // crossfaded reference is exact (the exciter's duck adapts smoothly to the new level by itself).
        fun chain(fs: Int, ch: Int, v: Voicing = Voicing.HEADPHONES) = this.chain(fs, ch, v).apply { exciterEnabled = false }
        val trace = DoubleArray(fs * 3 / 32 + 1)
        val cx = chain(fs, 2).apply { this.trace = trace }
        val out = FloatArray(mix.size)
        val inB = FloatArray(512)
        val outB = FloatArray(512)
        var settled = -1
        for (f in 0 until fs * 3 step 240) { // 240 divides fs: the switches land exactly on fs and 2·fs
            val n = minOf(240, fs * 3 - f)
            if (f == fs) cx.setVoicing(Voicing.LINE)
            if (f == 2 * fs) cx.setVoicing(Voicing.SPEAKER)
            System.arraycopy(mix, f * 2, inB, 0, n * 2)
            cx.process(inB, outB, n)
            System.arraycopy(outB, 0, out, f * 2, n * 2)
            if (f >= 2 * fs &&
                settled < 0 &&
                cx.voicingValues.contentEquals(CrystalX.VOICINGS[Voicing.SPEAKER.ordinal])
            ) {
                settled = f + 240 - 2 * fs
            }
        }
        assertTrue("settled after $settled frames (${cx.voicingValues.toList()})", settled in 1..(1440 + 240))
        // Reference: the same input through fixed voicings replaying the morphing run's low-shelf amounts (the
        // threshold/range morph moves a(t) through the level-dependent sidechain), crossfaded linearly over the 30 ms
        // morph windows; with a(t) shared, every morphing parameter is linear in the output.
        val a = run(chain(fs, 2, Voicing.HEADPHONES).apply { replay = trace }, mix, 2)
        val b = run(chain(fs, 2, Voicing.LINE).apply { replay = trace }, mix, 2)
        val c = run(chain(fs, 2, Voicing.SPEAKER).apply { replay = trace }, mix, 2)
        val ref = FloatArray(mix.size) { i ->
            val f = i / 2
            when {
                f < fs -> a[i]
                f < fs + 1440 -> a[i] + (b[i] - a[i]) * ((f - fs + 1).toFloat() / 1440)
                f < 2 * fs -> b[i]
                f < 2 * fs + 1440 -> b[i] + (c[i] - b[i]) * ((f - 2 * fs + 1).toFloat() / 1440)
                else -> c[i]
            }
        }
        for (ch in 0..1) {
            val o = Signals.channel(out, 2, ch)
            val r = Signals.channel(ref, 2, ch)
            val click = Signals.clickDb(o, r, fs, skip = fs / 2)
            val d = Signals.highpass(Signals.highpass(FloatArray(o.size) { o[it] - r[it] }, fs, 2000.0), fs, 2000.0)
            val at = (fs / 2 until d.size).maxByOrNull { abs(d[it]) }
            assertTrue("ch $ch click $click dB at $at", click < -60)
        }
    }

    // API 31–32 device lists pick USB > wired > BT > line > BLE speaker > speaker.
    @Test fun `route priority picks the platform's media route`() {
        val speaker = android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val wired = android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        val a2dp = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        val usb = android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        val bleSpeaker = android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER
        val hdmi = android.media.AudioDeviceInfo.TYPE_HDMI
        assertEquals(1, RouteVoicing.priority(wired))
        assertEquals(Voicing.HEADPHONES, RouteVoicing.pick(intArrayOf(speaker, a2dp, wired)))
        assertEquals(RouteVoicing.priority(usb), RouteVoicing.priority(intArrayOf(wired, usb).minByOrNull { RouteVoicing.priority(it) }!!))
        assertEquals(Voicing.SPEAKER, RouteVoicing.pick(intArrayOf(bleSpeaker, speaker)))
        assertEquals(Voicing.LINE, RouteVoicing.pick(intArrayOf(hdmi, speaker, bleSpeaker)))
        assertEquals(Voicing.HEADPHONES, RouteVoicing.pick(intArrayOf(hdmi, a2dp)))
        assertEquals(Voicing.SPEAKER, RouteVoicing.pick(intArrayOf(speaker)))
        assertEquals(Voicing.SPEAKER, RouteVoicing.pick(intArrayOf()))
    }
}
