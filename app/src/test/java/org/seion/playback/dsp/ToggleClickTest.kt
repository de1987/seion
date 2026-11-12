package org.seion.playback.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.playback.dsp.testutil.Signals

/**
 * The control state machine and crossfades with dummy wet stages (CrystalX+ = −6 dB of y1, RenovaPure = a band of
 * −6 dB of y1): equal-gain linear 30 ms ramps, WARM adds no output change, no clicks; the latency whatever the
 * switches; the flush fade; gL reaches 1 during WARM.
 */
class ToggleClickTest {
    private val fs = 48_000
    private val warm = 960
    private val ramp = 1440

    private fun engine(ch: Int = 2) = DspEngine().apply { configure(fs, ch) }

    /** Runs [x] (stereo) through the engine in [block]-frame calls, applying [schedule] (frame -> params) first. */
    private fun run(e: DspEngine, x: FloatArray, schedule: Map<Int, DspParams>, block: Int = 480): FloatArray {
        val out = x.copyOf()
        val ch = e.channels
        val frames = x.size / ch
        var f = 0
        val buf = FloatArray(block * ch)
        while (f < frames) {
            schedule[f]?.let(e::setParams)
            val n = minOf(block, frames - f)
            System.arraycopy(out, f * ch, buf, 0, n * ch)
            e.process(buf, n)
            System.arraycopy(buf, 0, out, f * ch, n * ch)
            f += n
        }
        return out
    }

    /** The amount law, computed independently: toggles at frames, WARM 20 ms then 30 ms linear ramps. */
    private fun expectedAmounts(frames: Int, toggles: List<Pair<Int, Boolean>>): DoubleArray {
        val a = DoubleArray(frames)
        var state = 0 // 0 off, 1 warm, 2 in, 3 on, 4 out
        var amount = 0.0
        var warmLeft = 0
        val map = toggles.toMap()
        for (f in 0 until frames) {
            map[f]?.let { on ->
                state =
                    if (on) {
                        when (state) {
                            0 -> {
                                warmLeft = warm
                                1
                            }
                            4 -> 2
                            else -> state
                        }
                    } else {
                        when (state) {
                            1 -> {
                                amount = 0.0
                                0
                            }
                            2, 3 -> 4
                            else -> state
                        }
                    }
            }
            when (state) {
                1 -> if (--warmLeft <= 0) state = 2
                2 -> {
                    amount += 1.0 / ramp
                    if (amount >= 1 - 1e-6) {
                        amount = 1.0
                        state = 3
                    }
                }
                4 -> {
                    amount -= 1.0 / ramp
                    if (amount <= 1e-6) {
                        amount = 0.0
                        state = 0
                    }
                }
            }
            a[f] = amount
        }
        return a
    }

    @Test fun `CrystalX ramp is equal-gain linear over 30 ms after a 20 ms WARM`() {
        val e = engine()
        val frames = 6000
        val x = FloatArray(frames * 2) { 0.5f }
        val out = run(e, x, mapOf(0 to DspParams(crystalX = true)))
        val d2 = e.limiterDelay
        // The amount of processing frame n applies to y1[n] (= x[n − D1]) and comes out at n + D2.
        // out = 0.5·(1 − gC) + 0.25·gC → gC = 2·(1 − out/0.5)
        for (n in 0 until frames - d2) {
            val g = 2 * (1 - out[(n + d2) * 2] / 0.5)
            val expected = if (n < warm) 0.0 else minOf(1.0, (n - warm + 1).toDouble() / ramp)
            if (n >= e.pureDelay) assertEquals("frame $n", expected, g, 2e-6)
        }
        // Reaches 1 at warm + ramp ± 1 frame.
        val full = (e.pureDelay until frames - d2).first { 2 * (1 - out[(it + d2) * 2] / 0.5) >= 1 - 1e-6 }
        assertTrue("full at $full", kotlin.math.abs(full - (warm + ramp - 1)) <= 1)
    }

    @Test fun `WARM adds no output change and the limiter amount reaches 1 by its end`() {
        val e = engine()
        val x = Signals.stereo(Signals.pink(4000, 1, 0.2))
        val dry = run(engine(), x, emptyMap())
        val out = run(e, x, mapOf(0 to DspParams(crystalX = true)), block = warm)
        // Processing frames [0, warm) come out at [D2, D2 + warm): identical to the dry path.
        for (i in 0 until (e.limiterDelay + warm) * 2) assertEquals("sample $i", dry[i].toRawBits(), out[i].toRawBits())
        val e2 = engine()
        run(e2, FloatArray(warm * 2), mapOf(0 to DspParams(crystalX = true)), block = warm)
        assertEquals(1f, e2.limiterAmount, 1e-6f)
        assertEquals(0f, e2.crystalAmount, 0f)
    }

    private fun toggleRun(cx: Boolean, rp: Boolean, signal: FloatArray): Pair<FloatArray, FloatArray> {
        val e = engine()
        val frames = signal.size / 2
        val period = fs / 2
        val togglesCx = mutableListOf<Pair<Int, Boolean>>()
        val togglesPa = mutableListOf<Pair<Int, Boolean>>()
        val schedule = mutableMapOf<Int, DspParams>()
        var onC = false
        var onP = rp
        // RenovaPure starts on: its activity d ramps over 250 ms once, then stays (the decision is kept while idle).
        if (rp) {
            togglesPa += 0 to true
            schedule[0] = DspParams(renovaPure = true)
        }
        var f = period
        var k = 0
        while (f < frames) {
            if (cx) onC = !onC
            if (rp && (!cx || k % 2 == 0)) onP = !onP
            togglesCx += f to onC
            togglesPa += f to onP
            schedule[f] = DspParams(crystalX = onC, renovaPure = onP)
            f += period
            k++
        }
        val out = run(e, signal, schedule)
        val gC = expectedAmounts(frames, togglesCx)
        val gP = expectedAmounts(frames, togglesPa)
        val l = e.latency
        val d2 = e.limiterDelay
        val ref = FloatArray(signal.size)
        for (i in l until frames) {
            // Output frame i: processing frame i − D2 (whose amounts apply), input sample i − L.
            for (c in 0 until 2) {
                val xd = signal[(i - l) * 2 + c].toDouble()
                val y1 = xd + gP[i - d2] * 0.5 * xd
                val m = (1 - gC[i - d2]) * y1 + gC[i - d2] * 0.5 * y1
                ref[i * 2 + c] = m.toFloat()
            }
        }
        return out to ref
    }

    @Test fun `toggling every 0_5 s for 20 s follows the ideal crossfade without clicks`() {
        val frames = fs * 20
        val signals =
            mapOf("1 kHz" to Signals.stereo(Signals.sine(frames, fs, 1000.0)), "mix" to Signals.stereo(Signals.mix(frames, fs, 2)))
        for ((name, s) in signals) {
            for ((cx, rp) in listOf(true to false, false to true, true to true)) {
                val (out, ref) = toggleRun(cx, rp, s)
                val l = DspEngine.latencyFor(fs)
                val skip = l + fs * 3 / 10 // after the one-off 250 ms activity ramp
                val outL = Signals.channel(out, 2, 0).copyOfRange(skip, frames)
                val refL = Signals.channel(ref, 2, 0).copyOfRange(skip, frames)
                val click = Signals.clickDb(outL, refL, fs)
                assertTrue("$name cx=$cx rp=$rp click $click dB", click < -60)
            }
        }
    }

    @Test fun `a toggle during a ramp reverses from the current amount`() {
        val e = engine()
        val frames = 8000
        val x = FloatArray(frames * 2) { 0.5f }
        val out = run(e, x, mapOf(0 to DspParams(crystalX = true), 1680 to DspParams(crystalX = false)), block = 240)
        val l = e.latency
        var maxStep = 0.0
        for (k in 1 until frames - l) maxStep = maxOf(maxStep, kotlin.math.abs(out[(k + l) * 2] - out[(k + l - 1) * 2]).toDouble())
        assertTrue("largest per-frame change $maxStep", maxStep <= 0.25 / ramp + 1e-6)
    }

    // The delay is exactly L whatever the switches.
    @Test fun `an impulse comes out exactly L frames later with any switches`() {
        for (p in listOf(DspParams(), DspParams(crystalX = true), DspParams(renovaPure = true), DspParams(true, true))) {
            for (rate in intArrayOf(44_100, 48_000, 96_000)) {
                val e = DspEngine().apply { configure(rate, 1) }
                e.setParams(p)
                e.process(FloatArray(rate / 10), rate / 10) // past WARM and the ramps
                val x = FloatArray(4096)
                x[100] = 0.5f
                e.process(x, x.size)
                val peak = x.indices.maxByOrNull { kotlin.math.abs(x[it]) }!!
                assertEquals("$p at $rate", 100 + DspEngine.latencyFor(rate), peak)
            }
        }
    }

    @Test fun `a flush while active fades in over 3 ms from the first non-zero sample`() {
        val e = engine(1)
        e.setParams(DspParams(crystalX = true))
        val x = Signals.sine(fs, fs, 997.0, phase = Math.PI / 2) // starts at its peak: a hard start would click
        e.process(x.copyOf(), x.size)
        e.flush()
        val y = x.copyOf()
        e.process(y, y.size)
        val l = e.latency
        assertTrue(y.copyOfRange(0, l).all { it == 0f })
        val fade = 144 // 3 ms at 48 kHz
        for (k in 0 until 2000) {
            val ideal = 0.5f * x[k] * minOf(1f, k.toFloat() / fade)
            assertEquals("frame $k", ideal, y[l + k], 1e-6f)
        }
    }

    // With the real CrystalX+ chain: toggling every 0.5 s for 20 s follows the ideal equal-gain crossfade of
    // y1 and cx(y1) (cx from a continuously running reference chain), no clicks.
    @Test fun `toggling the real CrystalX chain follows the ideal crossfade`() {
        val frames = fs * 20
        for ((name, mono) in mapOf("1 kHz" to Signals.sine(frames, fs, 1000.0), "mix" to Signals.mix(frames, fs, 5))) {
            val x = Signals.stereo(mono)
            val e = DspEngine(cx = CrystalX()).apply { configure(fs, 2) }
            val schedule = mutableMapOf<Int, DspParams>()
            val toggles = mutableListOf<Pair<Int, Boolean>>()
            var on = false
            var f = fs / 2
            while (f < frames) {
                on = !on
                schedule[f] = DspParams(crystalX = on)
                toggles += f to on
                f += fs / 2
            }
            val out = run(e, x, schedule)
            val gC = expectedAmounts(frames, toggles)
            // reference chain on the dry (D1-delayed) signal, running all the time
            val l = e.latency
            val d1 = e.pureDelay
            val y1 = FloatArray(x.size) { if (it / 2 >= d1) x[it - d1 * 2] else 0f }
            val wet = FloatArray(x.size)
            val ref = CrystalX().apply { configure(fs, 2) }
            var k = 0
            val inB = FloatArray(512)
            val outB = FloatArray(512)
            while (k < frames) {
                val n = minOf(256, frames - k)
                System.arraycopy(y1, k * 2, inB, 0, n * 2)
                ref.process(inB, outB, n)
                System.arraycopy(outB, 0, wet, k * 2, n * 2)
                k += n
            }
            val d2 = e.limiterDelay
            val expected = FloatArray(x.size)
            for (i in d2 until frames) {
                for (c in 0..1) {
                    val a = gC[i - d2].toFloat()
                    expected[i * 2 + c] = (1 - a) * y1[(i - d2) * 2 + c] + a * wet[(i - d2) * 2 + c]
                }
            }
            val skip = l + fs / 4
            val click = Signals.clickDb(
                Signals.channel(out, 2, 0).copyOfRange(skip, frames),
                Signals.channel(expected, 2, 0).copyOfRange(skip, frames),
                fs
            )
            assertTrue("$name click $click dB", click < -60)
        }
    }
}
