package org.seion.playback.dsp

import androidx.media3.common.C
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.playback.dsp.testutil.FakeInnerSink
import org.seion.playback.dsp.testutil.Pcm
import org.seion.playback.dsp.testutil.Signals

/** RenovaPure's cutoff detector and gating: frozen vectors, a synthetic test matrix, timing, codec hints. */
class CutoffDetectorTest {
    private val fs = 44_100

    private fun detect(l: FloatArray, r: FloatArray, codec: CodecClass, seconds: Int = 6): CutoffDetector {
        val d = CutoffDetector().apply {
            configure(fs, 2)
            newStream(codec)
        }
        val x = Signals.interleave(l, r)
        val frames = minOf(l.size, fs * seconds)
        var f = 0
        while (f < frames) {
            val n = minOf(256, frames - f)
            d.process(x, f * 2, n)
            f += n
        }
        d.finishSearch()
        return d
    }

    private fun mix(seed: Long) = Signals.mix(fs * 7, fs, seed)

    /** Codec-like walls; lossy ones carry sporadic "sfb21" bursts above the wall, pure ones (bursts = 0) none. */
    private fun stereoLp(fc: Double, seed: Long, floorDb: Double = -70.0, bursts: Double = 0.05): Pair<FloatArray, FloatArray> {
        val m = mix(seed)
        val l = Signals.codecLowpass(m, fs, fc, floorDb, burstShare = bursts, seed = seed)
        val r = Signals.codecLowpass(
            FloatArray(m.size) { m[(it + 3) % m.size] * 0.9f },
            fs,
            fc,
            floorDb,
            burstShare = bursts,
            seed =
            seed + 1
        )
        return l to r
    }

    // The window edge (lossy, lossless, provisional) and the P10 rule match tools/dsp/frozen_vectors.py.
    @Test fun `the detector window matches the frozen vector`() {
        val json = Json.parseToJsonElement(File("src/test/resources/dsp/frozen-detector.json").readText()).jsonObject
        val psd = json["psd"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
        fun edge(codec: CodecClass, provisional: Boolean): Double {
            val d = CutoffDetector().apply {
                configure(44_100, 2)
                newStream(codec)
            }
            d.loadWindow(psd, provisional)
            d.finishSearch()
            return d.lastEdge
        }
        assertEquals(json["edgeLossy"]!!.jsonPrimitive.double, edge(CodecClass.LOSSY, false), 1e-9)
        assertEquals(json["edgeLossless"]!!.jsonPrimitive.double, edge(CodecClass.LOSSLESS, false), 1e-9)
        assertEquals(json["edgeProvisionalLossy"]!!.jsonPrimitive.double, edge(CodecClass.LOSSY, true), 1e-9)
        val d = CutoffDetector().apply {
            configure(44_100, 2)
            newStream(CodecClass.LOSSY)
        }
        d.loadWindow(psd, provisional = true)
        d.finishSearch()
        assertEquals(json["provisionalFc"]!!.jsonPrimitive.doubleOrNull!!, d.fc, 1e-9)
        // the standard decision is the P10 of the window edges (numpy's linear interpolation)
        val edges = json["edges"]!!.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()
        assertEquals(json["p10"]!!.jsonPrimitive.double, d.percentile(edges, 0, edges.size, 10.0), 1e-9)
    }

    // A matrix of synthetic signals: lossy walls, soft roll-offs, lo-fi and dark mixes.
    @Test fun `codec-like walls are found within 400 Hz`() {
        for ((i, wall) in listOf(15_200.0, 16_000.0, 16_700.0, 17_200.0, 18_600.0, 18_900.0).withIndex()) {
            val (l, r) = stereoLp(wall, 10L + i)
            val d = detect(l, r, CodecClass.LOSSY)
            assertTrue("wall $wall: fc ${d.fc}", kotlin.math.abs(d.fc - wall) <= 400)
            assertTrue("per-window edge ${d.lastEdge} vs $wall", kotlin.math.abs(d.lastEdge - wall) <= 300 + 400)
        }
    }

    @Test fun `full band, 320k-like, dark, notched and soft-walled material bypasses`() {
        val full = mix(20)
        assertEquals("full band", 0.0, detect(full, full, CodecClass.LOSSY).fc, 0.0)
        val (h1, h2) = stereoLp(20_100.0, 21)
        assertEquals("wall at 20.1 kHz (320k)", 0.0, detect(h1, h2, CodecClass.LOSSY).fc, 0.0)
        val dark = Signals.lowpass4(mix(22), fs, 14_000.0)
        assertEquals("dark mix", 0.0, detect(dark, dark, CodecClass.LOSSY).fc, 0.0)
        val notch = Signals.notch(mix(23), fs, 15_000.0)
        assertEquals("notch", 0.0, detect(notch, notch, CodecClass.LOSSY).fc, 0.0)
    }

    @Test fun `the lossless class needs a 40 dB wall above 13 kHz`() {
        val (l, r) = stereoLp(15_200.0, 30, floorDb = -68.0, bursts = 0.0)
        val hard = detect(l, r, CodecClass.LOSSLESS)
        assertTrue("lossless 68 dB wall at 15.2 kHz: ${hard.fc}", kotlin.math.abs(hard.fc - 15_200) <= 400)
        val (s1, s2) = stereoLp(15_200.0, 31, floorDb = -29.0, bursts = 0.0)
        assertEquals("lossless 29 dB soft wall", 0.0, detect(s1, s2, CodecClass.LOSSLESS).fc, 0.0)
        val (t1, t2) = stereoLp(10_000.0, 32, floorDb = -48.0, bursts = 0.0)
        assertEquals("lossless 48 dB wall at 10 kHz (lo-fi)", 0.0, detect(t1, t2, CodecClass.LOSSLESS).fc, 0.0)
        assertTrue("the same 10 kHz wall in a lossy stream is found", detect(t1, t2, CodecClass.LOSSY).fc > 0)
    }

    @Test fun `anti-phase HF cannot fake a wall in the power sum`() {
        val m = mix(40)
        val base = Signals.codecLowpass(m, fs, 15_000.0, seed = 41)
        val hf = Signals.highpass(Signals.highpass(Signals.white(m.size, 42, 0.05), fs, 15_500.0), fs, 15_500.0)
        val l = FloatArray(m.size) { base[it] + hf[it] }
        val r = FloatArray(m.size) { base[it] - hf[it] }
        assertEquals("anti-phase", 0.0, detect(l, r, CodecClass.LOSSY).fc, 0.0)
    }

    // A provisional decision within 1.1 s; none during silence; a cached key is active at once.
    @Test fun `provisional decision within 1_1 s, none in silence`() {
        // A stationary synthetic wall: white noise brickwalled at 16 kHz in one long FFT (−80 dB above).
        val nfft = 1 shl 17
        val fft = org.seion.playback.dsp.core.RealFft(nfft)
        fun walled(seed: Long): FloatArray {
            val w = Signals.white(nfft, seed, 0.3)
            val re = FloatArray(nfft / 2 + 1)
            val im = FloatArray(nfft / 2 + 1)
            fft.forward(w, re, im)
            for (k in 0..nfft / 2) {
                if (k.toDouble() * fs / nfft > 16_000) {
                    re[k] *= 1e-4f
                    im[k] *= 1e-4f
                }
            }
            val out = FloatArray(nfft)
            fft.inverse(re, im, out)
            return out
        }
        val l = walled(51)
        val r = walled(52)
        val d = CutoffDetector().apply {
            configure(fs, 2)
            newStream(CodecClass.LOSSY)
        }
        val x = Signals.interleave(l, r)
        var f = 0
        var decidedAt = -1
        while (f < fs * 2) {
            d.process(x, f * 2, 256)
            f += 256
            if (decidedAt < 0 && d.fc > 0) decidedAt = f
        }
        assertTrue(
            "decided at ${decidedAt / fs.toDouble()} s (edge ${d.lastEdge}, decisions ${d.decisions}, fc ${d.fc})",
            decidedAt in 1..(fs * 11 / 10)
        )
        val silent = CutoffDetector().apply {
            configure(fs, 2)
            newStream(CodecClass.LOSSY)
        }
        val zeros = FloatArray(fs * 4 * 2)
        silent.process(zeros, 0, fs * 4)
        silent.finishSearch()
        assertEquals(0.0, silent.fc, 0.0)
        assertEquals(-1.0, silent.lastEdge, 0.0)
    }

    @Test fun `a cached decision engages at once and caches only after 3 s`() {
        val rp = RenovaPure().apply { configure(fs, 2) }
        val (l, r) = stereoLp(16_000.0, 60)
        val x = Signals.interleave(l, r)
        rp.newStream(CodecClass.LOSSY, "/music/a.mp3")
        val gen = FloatArray(512)
        val y1 = FloatArray(512)
        var f = 0
        while (f < fs * 5) {
            rp.process(x, f * 2, y1, gen, 256)
            f += 256
        }
        rp.detectorForTest.finishSearch()
        assertTrue(rp.activityTarget == 1f)
        rp.newStream(CodecClass.LOSSY, "/music/b.mp3")
        assertEquals("b keeps a's decision until its own first decision", 1f, rp.activityTarget, 0f)
        rp.newStream(CodecClass.LOSSY, "/music/a.mp3")
        assertEquals("cached → active at once", 1f, rp.activityTarget, 0f)
    }

    // A dark mix flagged lossy bypasses: out == float(in) delayed by L, bit for bit (limiter not engaged).
    @Test fun `gated material plays bit-exact with RenovaPure on`() {
        val dark = Signals.lowpass4(mix(70), fs, 14_000.0)
        val x = Signals.interleave(dark, dark)
        val off = DspEngine(cx = CrystalX(), rp = RenovaPure(), limiter = TruePeakLimiter()).apply { configure(fs, 2) }
        val a = x.copyOf()
        val b = x.copyOf()
        val ea = DspEngine(cx = CrystalX(), rp = RenovaPure(), limiter = TruePeakLimiter()).apply { configure(fs, 2) }
        ea.newStream(CodecClass.LOSSY, null)
        ea.setParams(DspParams(renovaPure = true))
        ea.process(a, fs * 6)
        off.process(b, fs * 6)
        for (i in 0 until fs * 6 * 2) assertEquals("sample $i", b[i].toRawBits(), a[i].toRawBits())
        assertEquals(0f, ea.pureAmount, 0f)
    }

    // The renderer hint reaches the engine in data order; no hint means LOSSLESS.
    @Test fun `the codec hint labels each stream`() {
        val seen = mutableListOf<CodecClass>()
        val gen =
            object : Generator by Generator.Dummy(0f) {
                override fun newStream(codec: CodecClass, mediaKey: String?) {
                    seen += codec
                }
            }
        val fake = FakeInnerSink()
        val sink = SeionAudioSink(fake.sink, engine = DspEngine(rp = gen))
        sink.setNextCodecHint(CodecHint.classify("audio/mpeg"))
        sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_16BIT, 44_100, 2)))
        Pcm.feed(sink, ByteArray(4000), 4, 44_100, java.util.Random(1))
        sink.setNextCodecHint(CodecHint.classify("audio/flac"))
        sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_16BIT, 44_100, 2)))
        Pcm.feed(sink, ByteArray(4000), 4, 44_100, java.util.Random(2))
        sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_16BIT, 44_100, 2))) // FFmpeg path: no hint
        assertEquals(listOf(CodecClass.LOSSY, CodecClass.LOSSLESS, CodecClass.LOSSLESS), seen)
        assertEquals(CodecClass.LOSSY, CodecHint.classify("audio/opus"))
        assertEquals(CodecClass.UNKNOWN, CodecHint.classify("audio/x-unknown"))
    }
}
