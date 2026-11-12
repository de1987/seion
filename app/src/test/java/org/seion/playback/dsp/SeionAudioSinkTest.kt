package org.seion.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.playback.dsp.testutil.FakeInnerSink
import org.seion.playback.dsp.testutil.Pcm

/** The sample-synchronous float sink. */
class SeionAudioSinkTest {
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

    private fun delayed(input: FloatArray, latencyFrames: Int, channels: Int): FloatArray =
        FloatArray(input.size + latencyFrames * channels) { i ->
            if (i <
                latencyFrames * channels
            ) {
                0f
            } else {
                input[i - latencyFrames * channels]
            }
        }

    // Both features off = an exact copy of the float input delayed by L, every encoding and rate.
    @Test fun `null bypass is bit-exact for every encoding at 44_1, 48 and 96 kHz`() {
        val rnd = Random(1)
        for (fs in intArrayOf(44_100, 48_000, 96_000)) {
            for (ch in 1..2) {
                for (enc in Pcm.ENCODINGS) {
                    val fake = FakeInnerSink()
                    val sink = SeionAudioSink(fake.sink)
                    sink.configure(Pcm.config(Pcm.format(enc, fs, ch)))
                    val frames = 5000 + rnd.nextInt(3000)
                    val (bytes, expected) = Pcm.random(enc, frames * ch, rnd)
                    Pcm.feed(sink, bytes, Pcm.bytesPer(enc) * ch, fs, rnd)
                    Pcm.endOfStream(sink)
                    val l = DspEngine.latencyFor(fs)
                    assertEquals(l, sink.latencyFrames)
                    val inner = fake.configs.single().format
                    assertEquals(C.ENCODING_PCM_FLOAT, inner.pcmEncoding)
                    assertEquals(0, inner.encoderDelay)
                    assertEquals(0, inner.encoderPadding)
                    assertBits(delayed(expected, l, ch), fake.floats(), "enc $enc fs $fs ch $ch")
                }
            }
        }
    }

    @Test fun `latency is 737, 757 and 486 frames at 44_1, 48 and 96 kHz`() {
        assertEquals(737, DspEngine.latencyFor(44_100))
        assertEquals(757, DspEngine.latencyFor(48_000))
        assertEquals(486, DspEngine.latencyFor(96_000))
    }

    // Random partial acceptance, 10^5 calls: byte-identical to an accept-all reference.
    @Test fun `back-pressure with random partial acceptance gives the same stream`() {
        val rnd = Random(3)
        val (bytes, _) = Pcm.random(C.ENCODING_PCM_16BIT, 2 * 700_000, rnd)
        val reference = FakeInnerSink()
        SeionAudioSink(reference.sink).apply {
            configure(Pcm.config(Pcm.format(C.ENCODING_PCM_16BIT, 44_100, 2)))
            Pcm.feed(this, bytes, 4, 44_100, Random(9))
            Pcm.endOfStream(this)
        }
        val partial = FakeInnerSink(partial = Random(4))
        SeionAudioSink(partial.sink).apply {
            configure(Pcm.config(Pcm.format(C.ENCODING_PCM_16BIT, 44_100, 2)))
            Pcm.feed(this, bytes, 4, 44_100, Random(9), maxFrames = 12)
            Pcm.endOfStream(this)
        }
        assertTrue("calls ${partial.calls}", partial.calls >= 100_000)
        assertArrayEquals(reference.bytes(), partial.bytes())
    }

    /** One stream = d junk frames, the music, p junk frames (encoder delay and padding). */
    private fun stream(music: FloatArray, ch: Int, d: Int, p: Int): ByteArray {
        val junk = { n: Int -> FloatArray(n * ch) { 0.9f } }
        return Pcm.float32(junk(d) + music + junk(p))
    }

    // Gapless with trimming: two streams of one sine, delay 1105 / padding 1000 junk frames each.
    @Test fun `gapless reconfigure with trimming gives the continuous sine and no junk`() {
        val fs = 44_100
        val ch = 2
        val sine = Pcm.sine(30_000, ch, fs, 997.0)
        val a = sine.copyOfRange(0, 13_000 * ch)
        val b = sine.copyOfRange(13_000 * ch, sine.size)
        val fake = FakeInnerSink(partial = Random(5))
        val sink = SeionAudioSink(fake.sink)
        val rnd = Random(6)
        sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_FLOAT, fs, ch, delay = 1105, padding = 1000)))
        Pcm.feed(sink, stream(a, ch, 1105, 1000), 8, fs, rnd)
        sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_FLOAT, fs, ch, delay = 1105, padding = 1000)))
        sink.handleDiscontinuity()
        Pcm.feed(sink, stream(b, ch, 1105, 1000), 8, fs, rnd, startPtsUs = 1_000_000)
        Pcm.endOfStream(sink)
        assertEquals("the inner sink reuses its configuration (gapless)", 2, fake.configs.size)
        assertEquals(fake.configs[0].format, fake.configs[1].format)
        val l = DspEngine.latencyFor(fs)
        assertBits(delayed(sine, l, ch), fake.floats(), "continuous sine")
    }

    // The end padding is ABSENT at a true end of stream. This is a deliberate deviation from Media3 1.11.1,
    // which emits it: encoder padding is not music.
    @Test fun `end padding is discarded at end of stream - deviation from Media3 by design`() {
        val fs = 48_000
        val music = Pcm.sine(9_000, 1, fs, 440.0)
        val fake = FakeInnerSink()
        val sink = SeionAudioSink(fake.sink)
        sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_FLOAT, fs, 1, delay = 0, padding = 1000)))
        Pcm.feed(sink, stream(music, 1, 0, 1000), 4, fs, Random(7))
        Pcm.endOfStream(sink)
        assertBits(delayed(music, DspEngine.latencyFor(fs), 1), fake.floats(), "no padding at EOS")
    }

    // Media3 parity: a flush drops the withheld end buffer and does not re-trim the start after a seek.
    @Test fun `flush drops the withheld padding and a seek does not re-trim`() {
        val fs = 44_100
        val fake = FakeInnerSink()
        val sink = SeionAudioSink(fake.sink)
        sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_FLOAT, fs, 1, delay = 100, padding = 500)))
        val first = FloatArray(3000) { 0.25f }
        Pcm.feed(sink, Pcm.float32(first), 4, fs, Random(8))
        assertEquals(100L, sink.trimmedFrames)
        sink.flush()
        val before = fake.floats().size
        val second = FloatArray(2000) { 0.5f }
        Pcm.feed(sink, Pcm.float32(second), 4, fs, Random(8), startPtsUs = 10_000_000)
        Pcm.endOfStream(sink)
        assertEquals("no re-trim after the seek", 0L, sink.trimmedFrames)
        val afterFlush = fake.floats().copyOfRange(before, fake.floats().size)
        val l = DspEngine.latencyFor(fs)
        // After the flush: L zeros (the delay lines were cleared, none of the first stream's withheld padding), then
        // the second buffer minus its own last 500 frames (end padding, discarded at EOS), nothing re-trimmed.
        assertEquals(l + 1500, afterFlush.size)
        assertTrue(afterFlush.take(l).all { it == 0f })
        assertTrue(afterFlush.drop(l).all { it == 0.5f })
    }

    // Pts on the post-trim timeline; after a flush ptsOut equals the input pts.
    @Test fun `timestamps follow the post-trim timeline and reset on flush`() {
        val fs = 44_100
        val fake = FakeInnerSink()
        val sink = SeionAudioSink(fake.sink)
        sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_16BIT, fs, 2, delay = 2112, padding = 0)))
        val (bytes, _) = Pcm.random(C.ENCODING_PCM_16BIT, 2 * 40_000, Random(10))
        Pcm.feed(sink, bytes, 4, fs, Random(11), startPtsUs = 0)
        var frames = 0L
        var last = -1L
        for (c in fake.chunks) {
            if (c.pts != C.TIME_UNSET) {
                val expected = frames * 1_000_000 / fs
                assertTrue("pts ${c.pts} vs $expected", kotlin.math.abs(c.pts - expected) <= 1_000_000 / fs + 1)
                assertTrue(c.pts > last)
                last = c.pts
            }
            frames += c.bytes.size / 8
        }
        sink.flush()
        assertEquals(0L, sink.trimmedFrames)
        val before = fake.chunks.size
        val seekPts = 20_000_000L
        val (more, _) = Pcm.random(C.ENCODING_PCM_16BIT, 2 * 5_000, Random(12))
        Pcm.feed(sink, more, 4, fs, Random(13), startPtsUs = seekPts)
        assertEquals("ptsOut after the seek equals the input pts (no d/fs offset)", seekPts, fake.chunks[before].pts)
    }

    // Delegation for >2 channels, encoded audio, offload, 352.8 kHz and padding beyond the ring.
    @Test fun `unsupported streams are delegated with their original config and bytes`() {
        val cases =
            listOf(
                "6 channels" to Pcm.format(C.ENCODING_PCM_16BIT, 48_000, 6, delay = 10, padding = 20),
                "352.8 kHz" to Pcm.format(C.ENCODING_PCM_24BIT, 352_800, 2, delay = 10, padding = 20),
                "padding 10000" to Pcm.format(C.ENCODING_PCM_16BIT, 44_100, 2, delay = 576, padding = 10_000),
                "encoded" to
                    Format
                        .Builder()
                        .setSampleMimeType(MimeTypes.AUDIO_AC3)
                        .setSampleRate(48_000)
                        .setChannelCount(2)
                        .build()
            )
        for ((name, format) in cases) {
            val fake = FakeInnerSink()
            val sink = SeionAudioSink(fake.sink)
            val cfg = Pcm.config(format)
            sink.configure(cfg)
            assertSame(name, cfg, fake.configs.single())
            val raw = ByteArray(48 * format.channelCount) { (it * 7).toByte() }
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            assertTrue(name, sink.handleBuffer(buf, 0, 1))
            assertArrayEquals(name, raw, fake.bytes())
        }
        // Offload requested: delegated too.
        val fake = FakeInnerSink()
        val sink = SeionAudioSink(fake.sink)
        sink.setOffloadMode(AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED)
        val cfg = Pcm.config(Pcm.format(C.ENCODING_PCM_16BIT, 44_100, 2))
        sink.configure(cfg)
        assertSame(cfg, fake.configs.single())
    }

    @Test fun `delegated bytes reach the inner sink untouched`() {
        val fake = FakeInnerSink()
        val sink = SeionAudioSink(fake.sink)
        val cfg = Pcm.config(Pcm.format(C.ENCODING_PCM_16BIT, 44_100, 2, padding = 10_000))
        sink.configure(cfg)
        val (bytes, _) = Pcm.random(C.ENCODING_PCM_16BIT, 2 * 3000, Random(14))
        Pcm.feed(sink, bytes, 4, 44_100, Random(15))
        assertArrayEquals(bytes, fake.bytes())
    }

    @Test fun `format support is direct for 1-2 channel PCM up to 192 kHz and delegated otherwise`() {
        val fake = FakeInnerSink()
        val sink = SeionAudioSink(fake.sink)
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(Pcm.format(C.ENCODING_PCM_FLOAT, 44_100, 2)))
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(Pcm.format(C.ENCODING_PCM_24BIT, 192_000, 1)))
        assertTrue(sink.supportsFormat(Pcm.format(C.ENCODING_PCM_FLOAT, 48_000, 2)))
    }

    // A configure with a new rate followed directly by EOS still drains and reconfigures.
    @Test fun `reconfigure followed directly by end of stream drains the old tail under the old config`() {
        val fake = FakeInnerSink()
        val sink = SeionAudioSink(fake.sink)
        sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_FLOAT, 44_100, 2)))
        val music = Pcm.sine(4000, 2, 44_100, 1000.0)
        Pcm.feed(sink, Pcm.float32(music), 8, 44_100, Random(16))
        val ptsBefore = fake.chunks.last { it.pts != C.TIME_UNSET }.pts
        sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_FLOAT, 48_000, 2)))
        assertEquals("not reconfigured before the tail", 1, fake.configs.size)
        Pcm.endOfStream(sink)
        assertEquals(2, fake.configs.size)
        assertEquals(48_000, fake.configs[1].format.sampleRate)
        assertBits(delayed(music, DspEngine.latencyFor(44_100), 2), fake.floatsFor(0), "old stream incl. tail")
        val oldPts = fake.chunks.filter { it.config == 0 && it.pts != C.TIME_UNSET }.map { it.pts }
        assertEquals("tail keeps the old stream's (increasing) pts", oldPts.sorted(), oldPts)
        assertTrue(oldPts.last() > ptsBefore)
        assertTrue(sink.isEnded())
    }

    // 44.1 -> 48 -> 96 kHz: each old tail fully delivered before the inner reconfigure, L per rate.
    @Test fun `rate changes deliver each tail before reconfiguring`() {
        val fake = FakeInnerSink(partial = Random(17))
        val sink = SeionAudioSink(fake.sink)
        val rates = intArrayOf(44_100, 48_000, 96_000)
        val inputs = mutableListOf<FloatArray>()
        for ((i, fs) in rates.withIndex()) {
            sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_FLOAT, fs, 2)))
            val music = Pcm.sine(6000, 2, fs, 1000.0 + i)
            inputs += music
            Pcm.feed(sink, Pcm.float32(music), 8, fs, Random(18L + i))
            assertEquals(DspEngine.latencyFor(fs), sink.latencyFrames)
        }
        Pcm.endOfStream(sink)
        assertEquals(3, fake.configs.size)
        for ((i, fs) in rates.withIndex()) {
            val got = fake.floatsFor(i)
            assertTrue(got.all { it.isFinite() })
            assertBits(delayed(inputs[i], DspEngine.latencyFor(fs), 2), got, "rate $fs")
        }
    }

    // The last 20 ms are present at the output; when idle a flush gives no fade (bit-exact).
    @Test fun `the last 20 ms reach the output and an idle flush is bit-exact`() {
        val fs = 48_000
        val fake = FakeInnerSink()
        val sink = SeionAudioSink(fake.sink)
        sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_FLOAT, fs, 1)))
        val music = Pcm.sine(24_000, 1, fs, 700.0)
        Pcm.feed(sink, Pcm.float32(music), 4, fs, Random(19))
        Pcm.endOfStream(sink)
        val out = fake.floats()
        val tail = out.copyOfRange(out.size - 960, out.size)
        val ref = music.copyOfRange(music.size - 960, music.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in tail.indices) {
            dot += tail[i] * ref[i]
            na += tail[i] * tail[i]
            nb += ref[i] * ref[i]
        }
        assertTrue(dot / kotlin.math.sqrt(na * nb) >= 0.999)

        sink.flush()
        val before = fake.floats().size
        val more = Pcm.sine(5000, 1, fs, 300.0)
        Pcm.feed(sink, Pcm.float32(more), 4, fs, Random(20), startPtsUs = 5_000_000)
        Pcm.endOfStream(sink)
        val after = fake.floats().copyOfRange(before, fake.floats().size)
        assertBits(delayed(more, DspEngine.latencyFor(fs), 1), after, "after an idle flush")
    }

    @Test fun `float output refused by the inner sink falls back to delegation`() {
        val fake = FakeInnerSink()
        val refusing =
            java.lang.reflect.Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, m, args ->
                if (m.name == "configure") {
                    val cfg = args!![0] as AudioSink.AudioSinkConfig
                    if (cfg.format.pcmEncoding == C.ENCODING_PCM_FLOAT) throw AudioSink.ConfigurationException("no float", cfg.format)
                }
                m.invoke(fake.sink, *(args ?: emptyArray()))
            } as AudioSink
        val sink = SeionAudioSink(refusing)
        val cfg = Pcm.config(Pcm.format(C.ENCODING_PCM_16BIT, 44_100, 2))
        sink.configure(cfg)
        assertSame(cfg, fake.configs.single())
    }

    @Test fun `the dump reports engine load from the thread CPU clock`() {
        var cpu = 0L
        val stats = DspStats(cpuClock = { cpu })
        val status = DspStatus()
        val fake = FakeInnerSink()
        val sink = SeionAudioSink(fake.sink, stats, status)
        sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_FLOAT, 48_000, 2)))
        val buf = ByteBuffer.wrap(Pcm.float32(FloatArray(2 * 48_000))).order(ByteOrder.LITTLE_ENDIAN)
        // Fake CPU clock: every reading advances 5 ms, so 1 s of audio in 48000/2048 slices costs 24 × 5 ms.
        val clock = DspStats(cpuClock = {
            cpu += 5_000_000
            cpu
        })
        val sink2 = SeionAudioSink(FakeInnerSink().sink, clock, status)
        sink2.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_FLOAT, 48_000, 2)))
        while (!sink2.handleBuffer(buf, 0, 1)) Unit
        assertEquals(1.0, clock.audioSeconds, 1e-9)
        assertEquals(24 * 0.5, clock.loadPercent(), 1e-6)
        val lines = DspReport.lines(clock, status)
        assertTrue(lines.joinToString("\n"), lines.any { it.startsWith("engine load: 12.00 %") })
        assertTrue(lines.any { it.contains("latency 757 frames") })
        clock.requestReset()
        clock.beginCall()
        assertEquals(0.0, clock.audioSeconds, 0.0)
        assertSame(sink, sink) // keep the first sink referenced
        assertEquals(0L, stats.blocks)
    }

    // The debug tap reads a -6 dBFS 1 kHz tone at -6 dBFS, 1 kHz, nothing else above -60 dBFS.
    @Test fun `the output tap measures level and spectrum`() {
        val status = DspStatus().apply { tapEnabled = true }
        val fake = FakeInnerSink()
        val sink = SeionAudioSink(fake.sink, DspStats({ 0L }), status)
        sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_16BIT, 44_100, 2)))
        val tone = Pcm.sine(44_100, 2, 44_100, 1000.0, amp = 0.5)
        val pcm = ByteBuffer.allocate(tone.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        tone.forEach { pcm.putShort(Math.round(it * 32767f).toShort()) }
        Pcm.feed(sink, pcm.array(), 4, 44_100, Random(21))
        val a = DspReport.analyse(status.tapSnapshot(), 44_100)
        assertEquals(-6.02, a.peakDb, 0.1)
        assertEquals(1000.0, a.peakHz, 44_100.0 / DspStatus.TAP_FRAMES)
        assertTrue("spur ${a.spurDb}", a.spurDb < -60)
        assertTrue(DspReport.lines(DspStats({ 0L }), status).last().startsWith("tap: "))
    }
}
