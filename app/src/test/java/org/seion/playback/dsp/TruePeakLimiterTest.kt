package org.seion.playback.dsp

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.playback.dsp.testutil.Meters
import org.seion.playback.dsp.testutil.Signals

/** The shared true-peak limiter: the ceiling, bit-exact bypass, warm-up order and the frozen gain curve. */
class TruePeakLimiterTest {
    /** The limiter alone with gL = 1: out[n] = m[n − D2]·g[n]. Interleaved. */
    private fun limit(m: FloatArray, ch: Int, fs: Int, block: Int = 256): FloatArray {
        val lim = TruePeakLimiter().apply { configure(fs, ch) }
        val d2 = DspEngine.lookahead(fs) + DspEngine.DETECTOR_ALIGN
        val frames = m.size / ch
        val out = FloatArray(m.size)
        val g = FloatArray(block)
        var f = 0
        while (f < frames) {
            val n = minOf(block, frames - f)
            lim.gains(m.copyOfRange(f * ch, (f + n) * ch), n, g)
            for (i in 0 until n) {
                val src = f + i - d2
                for (c in 0 until ch) out[(f + i) * ch + c] = if (src >= 0) m[src * ch + c] * g[i] else 0f
            }
            f += n
        }
        return out
    }

    private fun ceiling16(fs: Int) = -1.0 + TruePeakLimiter.underReadDb(fs) + 0.1

    private fun torture(fs: Int): Map<String, FloatArray> {
        val n = fs * 2
        val pink = Signals.pink(n, 3, 0.8)
        return mapOf(
            "pink +6 dB burst" to
                Signals.stereo(FloatArray(n) { if (it in fs / 2 until fs * 3 / 2) pink[it] else (0.05 * sin(it * 0.01)).toFloat() }),
            "fs/4 at 45 deg, 2.0" to Signals.stereo(FloatArray(n) { (2.0 * sin(PI / 2 * it + PI / 4)).toFloat() }),
            "50 Hz 3 dB over" to Signals.stereo(Signals.sine(n, fs, 50.0, amp = TruePeakLimiter.CEILING * 1.4125)),
            "19 kHz full scale" to Signals.stereo(Signals.sine(n, fs, 19_000.0, amp = 1.0)),
            "isolated over" to Signals.stereo(FloatArray(n) { if (it == fs) 1.8f else 0.1f * sin(2 * PI * 440 * it / fs).toFloat() }),
            // continuous overs on music-like (pink) content; white noise up to Nyquist is outside the 20 kHz design
            // assumption of the under-read bound
            "sustained overs" to Signals.interleave(Signals.pink(n, 5, 0.9), Signals.pink(n, 6, 0.7)),
            "square-ish, full scale" to
                Signals.stereo(
                    FloatArray(n) { i ->
                        var v = 0.0
                        var k = 1
                        while (k * 1000 < 15_000) {
                            v += sin(2 * PI * k * 1000.0 * i / fs) / k
                            k += 2
                        }
                        (v * 4 / PI * 1.0).toFloat()
                    }
                )
        )
    }

    // TP <= -1.00 dBTP on the 4x meter and <= ceiling + underRead + 0.1 dB on a 16x reference.
    @Test fun `true peak stays under the ceiling on the clipping torture set`() {
        for (fs in intArrayOf(44_100, 48_000)) {
            for ((name, x) in torture(fs)) {
                val y = limit(x, 2, fs)
                val skip = DspEngine.latencyFor(fs)
                val tp4 = Meters.truePeakDb(y, 2, fs, skip)
                assertTrue("$name $fs: 4x TP $tp4 dBTP", tp4 <= -1.0 + 1e-3)
                val win = if (name.startsWith("19") || name.startsWith("fs/4")) skip + 2000 else y.size / 2
                // stop 64 frames before the end: the abrupt end of the array would ring in the sinc interpolator
                val tp16 = Meters.truePeak16Db(y, 2, skip, minOf(win, y.size / 2 - 64))
                assertTrue("$name $fs: 16x TP $tp16 dBTP vs ${ceiling16(fs)}", tp16 <= ceiling16(fs))
            }
        }
    }

    // The 16x ceilings above (−0.34 dBTP at 44.1 kHz, −0.43 at 48 kHz) rest on the detector's under-read at 20 kHz:
    // pin the production numbers and the oversampling factor per rate (4x to 48 kHz, 2x to 96 kHz, 1x above).
    @Test fun `the detector under-read at 20 kHz is pinned per rate`() {
        assertEquals(0.5631, TruePeakLimiter.underReadDb(44_100), 1e-3)
        assertEquals(0.4736, TruePeakLimiter.underReadDb(48_000), 1e-3)
        assertEquals(0.5631, TruePeakLimiter.underReadDb(88_200), 1e-3)
        assertEquals(0.4736, TruePeakLimiter.underReadDb(96_000), 1e-3)
        assertEquals(0.5631, TruePeakLimiter.underReadDb(176_400), 1e-3)
        assertEquals(0.4736, TruePeakLimiter.underReadDb(192_000), 1e-3)
    }

    // A single inter-sample over placed at each of the 4 phases is fully caught (integer alignment, D_tp = 5.875).
    @Test fun `an inter-sample over at every phase is caught`() {
        val fs = 48_000
        for (phase in 0 until 4) {
            val off = phase / 4.0
            val x = FloatArray(4000) { i ->
                val t = i - 2000 - off
                val s = if (abs(t) < 1e-9) 1.0 else sin(PI * t * 0.9) / (PI * t * 0.9)
                (2.0 * s * (if (abs(t) < 40) 0.5 + 0.5 * kotlin.math.cos(PI * t / 40) else 0.0)).toFloat()
            }
            val y = limit(x, 1, fs)
            val tp = Meters.truePeak16Db(y, 1, 1500, 3000)
            assertTrue("phase $phase: $tp", tp <= ceiling16(fs))
            val tp4 = Meters.truePeakDb(y, 1, fs, 1000)
            assertTrue("phase $phase 4x $tp4", tp4 <= -1.0 + 1e-3)
        }
    }

    @Test fun `50 Hz at 3 dB over the ceiling stays under 1 percent THD+N`() {
        val fs = 48_000
        val x = Signals.sine(fs * 3, fs, 50.0, amp = TruePeakLimiter.CEILING * 1.4125)
        val y = limit(x, 1, fs)
        val thd = Meters.thdN(y, fs, 50.0, fs * 2, 32_768)
        assertTrue("THD+N ${thd * 100} %", thd < 0.01)
    }

    @Test fun `gain recovers within 0_5 s after an isolated over and slower when busy`() {
        val fs = 48_000
        val lim = TruePeakLimiter().apply { configure(fs, 1) }
        val g = FloatArray(1)
        fun gainAfter(x: FloatArray): FloatArray {
            val out = FloatArray(x.size)
            for (i in x.indices) {
                lim.gains(floatArrayOf(x[i]), 1, g)
                out[i] = g[0]
            }
            return out
        }
        val iso = FloatArray(fs) { if (it == 1000) 1.8f else 0.1f }
        val gi = gainAfter(iso)
        val back = (1000 until fs).first { it > 1300 && 20 * log10(gi[it].toDouble()) > -0.1 }
        assertTrue("recovered after ${(back - 1000) / fs.toDouble()} s", back - 1000 < fs / 2)
        lim.reset()
        val busy = FloatArray(fs * 4) { if (it < fs * 3) (1.5 * sin(2 * PI * 200 * it / fs)).toFloat() else 0.1f }
        val gb = gainAfter(busy)
        val backBusy = (fs * 3 until fs * 4).firstOrNull { 20 * log10(gb[it].toDouble()) > -0.1 } ?: (fs * 4)
        assertTrue("busy recovery ${(backBusy - fs * 3) / fs.toDouble()} s", backBusy - fs * 3 > fs / 2)
    }

    // The gain curve of a step over matches tools/dsp/frozen_vectors.py within 1e-4 dB.
    @Test fun `the step-over gain curve matches the frozen vector`() {
        val json = Json.parseToJsonElement(File("src/test/resources/dsp/frozen-limiter.json").readText()).jsonObject
        val fs = json["fs"]!!.jsonPrimitive.int
        val from = json["from"]!!.jsonPrimitive.int
        val expected = json["gain"]!!.jsonArray.map { it.jsonPrimitive.double }
        val lim = TruePeakLimiter().apply { configure(fs, 1) }
        val m = FloatArray(2000) { if (it < json["stepAt"]!!.jsonPrimitive.int) 0.5f else 2.0f }
        val g = FloatArray(2000)
        lim.gains(m, 2000, g)
        for (i in expected.indices) {
            val e = 20 * log10(expected[i])
            val a = 20 * log10(g[from + i].toDouble())
            assertEquals("sample ${from + i}", e, a, 1e-4)
        }
    }

    // RenovaPure on but no wall decided: the limiter never engages on lossless 0 dBFS+ material (bit-exact).
    @Test fun `RenovaPure without a wall never engages the limiter`() {
        val fs = 44_100
        val e = DspEngine(rp = Generator.Dummy(target = 0f), limiter = TruePeakLimiter()).apply { configure(fs, 2) }
        val idle = DspEngine(limiter = TruePeakLimiter()).apply { configure(fs, 2) }
        e.setParams(DspParams(renovaPure = true))
        val x = Signals.interleave(Signals.white(fs, 7, 1.2), Signals.white(fs, 8, 1.1))
        val a = x.copyOf()
        val b = x.copyOf()
        e.process(a, fs)
        idle.process(b, fs)
        for (i in a.indices) assertEquals("sample $i", b[i].toRawBits(), a[i].toRawBits())
        assertEquals(0f, e.limiterAmount, 0f)
    }

    // gL reaches 1 before a_CX leaves 0; from the end of WARM the output is <= -1.0 dBTP (4x) through RAMP_IN and ON;
    // during WARM it never exceeds the dry signal's true peak.
    @Test fun `CrystalX toggled on a +1 dBTP master is limited from the end of WARM`() {
        val fs = 48_000
        val boost =
            object : WetChain {
                private var ch = 2

                override fun configure(fs: Int, ch: Int) {
                    this.ch = ch
                }

                override fun reset() {}

                override fun process(y1: FloatArray, out: FloatArray, frames: Int) {
                    for (i in 0 until frames * ch) out[i] = 1.88f * y1[i] // +5.5 dB: the wet chain adds overs
                }
            }
        val e = DspEngine(cx = boost, limiter = TruePeakLimiter()).apply { configure(fs, 2) }
        val pink = Signals.pink(fs * 2, 9, 0.5)
        val clipped = FloatArray(pink.size) { pink[it].coerceIn(-0.6f, 0.6f) }
        val peak = Meters.truePeakDb(Signals.stereo(clipped), 2, fs)
        val master = FloatArray(clipped.size * 2) { (clipped[it / 2] * Math.pow(10.0, (1.0 - peak) / 20)).toFloat() }
        assertEquals(1.0, Meters.truePeakDb(master, 2, fs), 0.01)
        val out = master.copyOf()
        val block = 16
        val toggle = fs / 2
        var f = 0
        val buf = FloatArray(block * 2)
        while (f < out.size / 2) {
            if (f == toggle) e.setParams(DspParams(crystalX = true))
            System.arraycopy(out, f * 2, buf, 0, block * 2)
            e.process(buf, block)
            System.arraycopy(buf, 0, out, f * 2, block * 2)
            if (e.crystalAmount > 0f) assertEquals("gL before a_CX leaves 0 (frame $f)", 1f, e.limiterAmount, 0f)
            f += block
        }
        val d2 = e.limiterDelay
        val warmEnd = toggle + 960 + d2
        val l = e.latency
        assertTrue("after WARM", Meters.truePeakDb(out, 2, fs, warmEnd) <= -1.0 + 1e-3)
        val dryTp = Meters.truePeakDb(master, 2, fs, toggle - l, warmEnd - l)
        assertTrue("during WARM", Meters.truePeakDb(out, 2, fs, toggle, warmEnd) <= dryTp + 1e-3)
    }
}
