package org.seion.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.lang.management.ManagementFactory
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.playback.dsp.core.FastMath
import org.seion.playback.dsp.testutil.FakeInnerSink
import org.seion.playback.dsp.testutil.Pcm
import org.seion.playback.dsp.testutil.Signals

/**
 * The ReplayGain stage in the engine and the sink: off or untagged stays the bit-exact delayed copy;
 * a tagged stream is scaled from its first frame; a gapless boundary ramps from the new stream's first frame; a rise
 * waits for the limiter, which keeps it under the ceiling; no allocation on the audio thread.
 */
class ReplayGainEngineTest {
    private val fs = 48_000

    private fun assertBits(expected: FloatArray, actual: FloatArray, what: String) {
        assertEquals("$what length", expected.size, actual.size)
        for (i in expected.indices) {
            if (expected[i].toRawBits() !=
                actual[i].toRawBits()
            ) {
                throw AssertionError("$what sample $i: expected ${expected[i]} got ${actual[i]}")
            }
        }
    }

    private fun run(e: DspEngine, x: FloatArray, ch: Int, block: Int = 256): FloatArray {
        val out = x.copyOf()
        val buf = FloatArray(block * ch)
        var f = 0
        while (f < x.size / ch) {
            val n = minOf(block, x.size / ch - f)
            System.arraycopy(out, f * ch, buf, 0, n * ch)
            e.process(buf, n)
            System.arraycopy(buf, 0, out, f * ch, n * ch)
            f += n
        }
        return out
    }

    /** [x] delayed by [l] frames ([ch] channels), each sample times [g] (float, as the engine multiplies). */
    private fun delayed(x: FloatArray, l: Int, ch: Int, g: Float = 1f) = FloatArray(x.size) { i ->
        if (i < l * ch) {
            0f
        } else if (g == 1f) {
            x[i - l * ch]
        } else {
            x[i - l * ch] * g
        }
    }

    private fun tags(trackDb: Double, trackPeak: Double = Double.NaN, albumDb: Double = Double.NaN) =
        ReplayGainTags(trackDb, trackPeak, albumDb, Double.NaN)

    private fun engine(limiter: GainComputer = TruePeakLimiter()) = DspEngine(cx = CrystalX(), rp = RenovaPure(), limiter = limiter).apply {
        configure(fs, 2)
    }

    @Test fun `off, or on with an untagged file, keeps the idle copy bit for bit`() {
        val x = Signals.interleave(Signals.white(fs, 1, 0.4), Signals.white(fs, 2, 0.4))
        val l = DspEngine.latencyFor(fs)
        // Tags present, switch off.
        val off = engine().apply { setStreamGains(tags(-6.0, 0.9, -7.0)) }
        assertBits(delayed(x, l, 2), run(off, x, 2), "off with tags")
        assertTrue(off.isIdle)
        // Switch on, untagged file, pre-amp +6: unchanged (the pre-amp is for tagged files).
        val untagged = engine().apply {
            setParams(DspParams(replayGain = ReplayGainMode.TRACK, preampDb = 6f))
            setStreamGains(ReplayGainTags.NONE)
        }
        assertBits(delayed(x, l, 2), run(untagged, x, 2), "on, untagged")
        assertTrue(untagged.isIdle)
    }

    @Test fun `a tagged stream is scaled from its first frame, exactly`() {
        val x = Signals.interleave(Signals.white(fs, 3, 0.4), Signals.white(fs, 4, 0.4))
        val e = engine().apply {
            setParams(DspParams(replayGain = ReplayGainMode.ALBUM))
            setStreamGains(tags(-3.0, 0.9, albumDb = -6.0)) // album mode: −6 dB
        }
        val g = e.replayGainTarget
        assertEquals(Math.pow(10.0, -6.0 / 20).toFloat(), g, 2e-5f)
        // A cut needs no limiter: the active path is D1 + D2 of the scaled input, bit for bit.
        assertBits(delayed(x, DspEngine.latencyFor(fs), 2, g), run(e, x, 2), "−6 dB")
    }

    @Test fun `a gapless boundary ramps from the new stream's first frame`() {
        val ch = 1
        val e = DspEngine(limiter = TruePeakLimiter()).apply {
            configure(fs, ch)
            setParams(DspParams(replayGain = ReplayGainMode.TRACK))
            setStreamGains(tags(-3.0, 0.5))
        }
        val n = 10_000
        val dc = FloatArray(n) { 0.5f }
        val a = run(e, dc, ch, block = 300)
        e.newStream(CodecClass.LOSSLESS, null) // the sink's gapless reconfigure: same state, new tags
        e.setStreamGains(tags(-9.0, 0.5))
        val b = run(e, dc, ch, block = 300)
        val out = a + b
        val l = DspEngine.latencyFor(fs)
        val ga = 0.5f * FastMath.fromDb20(-3.0).toFloat()
        val gb = 0.5f * FastMath.fromDb20(-9.0).toFloat()
        val ramp = Math.rint(DspEngine.RG_RAMP_S * fs).toInt()
        // Up to the boundary: stream A's gain exactly.
        for (i in l until l + n) assertEquals("frame $i", ga, out[i], 0f)
        // The ramp starts at the first frame of B and is monotonic, with no step larger than one ramp step.
        assertTrue(out[l + n] < ga)
        val step = (ga - gb) / ramp
        for (i in l + n until l + n + ramp) {
            assertTrue("monotonic at $i", out[i] <= out[i - 1])
            assertTrue("step at $i", out[i - 1] - out[i] <= step * 1.01f)
        }
        for (i in l + n + ramp until out.size) assertEquals("frame $i", gb, out[i], 1e-6f)
    }

    @Test fun `a rise waits for the limiter and the output stays under the ceiling`() {
        val ch = 2
        val e = engine().apply { setParams(DspParams(replayGain = ReplayGainMode.TRACK)) }
        val loud = Signals.stereo(Signals.sine(fs, fs, 997.0, amp = 0.85))
        e.setStreamGains(ReplayGainTags.NONE)
        val a = run(e, loud, ch)
        assertTrue("untagged: idle", e.isIdle)
        e.newStream(CodecClass.LOSSLESS, null)
        e.setStreamGains(tags(6.0)) // +6 dB, peak unknown: the limiter must be in
        val b = run(e, loud, ch)
        assertTrue(e.replayGainLimiter)
        assertEquals(1f, e.limiterAmount, 0f)
        assertTrue("untouched before the boundary", a.all { Math.abs(it) <= 0.85f + 1e-6f })
        val peak = b.maxOf { Math.abs(it) }
        assertTrue("peak $peak after a +6 dB rise", peak <= TruePeakLimiter.CEILING + 1e-4)
        // It did rise: the limited level is near the ceiling, well above the input's.
        assertTrue("level ${Signals.rms(b, b.size / 2)}", Signals.rms(b, b.size / 2) > 0.6)
    }

    @Test fun `a boost set before any audio has the limiter in from the first frame`() {
        val e = engine().apply {
            setParams(DspParams(replayGain = ReplayGainMode.TRACK, preampDb = 6f))
            setStreamGains(tags(3.0, 0.9)) // +9 dB on a 0.9 peak
        }
        assertEquals(1f, e.limiterAmount, 0f)
        val out = run(e, Signals.stereo(Signals.sine(fs, fs, 440.0, amp = 0.9)), 2)
        assertTrue(out.maxOf { Math.abs(it) } <= TruePeakLimiter.CEILING + 1e-4)
    }

    @Test fun `switching ReplayGain off returns to the bit-exact idle path`() {
        val e = engine().apply {
            setParams(DspParams(replayGain = ReplayGainMode.TRACK))
            setStreamGains(tags(-6.0, 0.5))
        }
        val x = Signals.interleave(Signals.white(fs, 5, 0.4), Signals.white(fs, 6, 0.4))
        run(e, x, 2)
        e.setParams(DspParams())
        val y = run(e, x, 2)
        assertTrue(e.isIdle)
        // After the 30 ms ramp and the latency, the output is the input delayed by L again, bit for bit.
        val l = DspEngine.latencyFor(fs)
        val settle = (Math.rint(DspEngine.RG_RAMP_S * fs).toInt() + l + 256) * 2
        val expected = delayed(x, l, 2)
        for (i in settle until y.size) {
            if (expected[i].toRawBits() != y[i].toRawBits()) throw AssertionError("sample $i: ${expected[i]} vs ${y[i]}")
        }
    }

    // With ReplayGain: 0 bytes across 10^4 blocks, ramps and pre-amp changes included (setParams per block,
    // as the sink calls it), alone and with both effects on.
    @Test fun `ReplayGain allocates nothing on the audio thread`() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        for (effects in listOf(false, true)) {
            val rp = RenovaPure().apply { forceFc = 16_000.0 }
            val e = DspEngine(cx = CrystalX(), rp = rp, limiter = TruePeakLimiter()).apply { configure(fs, 2) }
            val p1 = DspParams(crystalX = effects, renovaPure = effects, replayGain = ReplayGainMode.TRACK, preampDb = 0f)
            val p2 = p1.copy(preampDb = 5f) // a rise past the ceiling: the limiter's hold and ramps run too
            e.setParams(p1)
            e.setStreamGains(tags(-3.0, 0.95))
            val x = Signals.interleave(Signals.mix(fs, fs, 1), Signals.mix(fs, fs, 2))
            val buf = FloatArray(512)
            fun blocks(count: Int) {
                var f = 0
                repeat(count) { k ->
                    e.setParams(if ((k / 500) % 2 == 0) p1 else p2)
                    System.arraycopy(x, (f % (fs - 256)) * 2, buf, 0, 512)
                    e.process(buf, 256)
                    f += 256
                }
            }
            blocks(30_000) // warm-up: past the JIT's recompilations of the toggling paths (a 888-byte transient seen once)
            val id = Thread.currentThread().id
            val before = bean.getThreadAllocatedBytes(id)
            blocks(10_000)
            // Read before building the message: a string template's first run bootstraps its concatenation (it allocates).
            val allocated = bean.getThreadAllocatedBytes(id) - before
            assertEquals("bytes allocated (effects $effects)", 0L, allocated)
        }
    }

    // With tags in the format: switch off = bit-exact; Track mode scales by the tag (the sink reads the
    // stream's metadata at configure).
    @Test fun `the sink reads the tags from the format's metadata`() {
        val ch = 2
        val music = Pcm.sine(9_000, ch, fs, 440.0)
        val format =
            Pcm
                .format(C.ENCODING_PCM_FLOAT, fs, ch)
                .buildUpon()
                .setMetadata(Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "-6.00 dB"), VorbisComment("REPLAYGAIN_TRACK_PEAK", "0.5")))
                .build()
        val l = DspEngine.latencyFor(fs)
        fun render(params: DspParams): FloatArray {
            val fake = FakeInnerSink()
            val sink = SeionAudioSink(fake.sink, control = DspControl(params))
            sink.configure(Pcm.config(format))
            Pcm.feed(sink, Pcm.float32(music), 4 * ch, fs, Random(1))
            Pcm.endOfStream(sink)
            return fake.floats()
        }
        val withTail = FloatArray(music.size + l * ch) { if (it < l * ch) 0f else music[it - l * ch] }
        assertBits(withTail, render(DspParams()), "switch off")
        val g = FastMath.fromDb20(-6.0).toFloat()
        val scaled = render(DspParams(replayGain = ReplayGainMode.TRACK))
        assertBits(FloatArray(withTail.size) { if (it < l * ch) 0f else withTail[it] * g }, scaled, "track −6 dB")
    }
}
