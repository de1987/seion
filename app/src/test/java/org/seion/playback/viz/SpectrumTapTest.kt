package org.seion.playback.viz

import androidx.media3.common.C
import java.lang.management.ManagementFactory
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.playback.dsp.DspControl
import org.seion.playback.dsp.DspEngine
import org.seion.playback.dsp.DspStats
import org.seion.playback.dsp.DspStatus
import org.seion.playback.dsp.SeionAudioSink
import org.seion.playback.dsp.testutil.FakeInnerSink
import org.seion.playback.dsp.testutil.Pcm

/** The audio-thread tap (lock-free, allocation-free, nothing at all while disabled). */
class SpectrumTapTest {
    private fun sinkWith(tap: SpectrumTap, fake: FakeInnerSink) =
        SeionAudioSink(fake.sink, DspStats({ 0L }), DspStatus(), DspControl(), spectrum = tap)

    @Test fun disabledCopiesNothing() {
        val tap = SpectrumTap()
        val fake = FakeInnerSink()
        val sink = sinkWith(tap, fake)
        sink.configure(Pcm.config(Pcm.format(C.ENCODING_PCM_FLOAT, 48_000, 2)))
        Pcm.feed(sink, Pcm.float32(Pcm.sine(20_000, 2, 48_000, 440.0)), 8, 48_000, Random(1))
        assertEquals(0L, tap.writtenFrames)
    }

    @Test fun `enabled, the tap sees what is heard and the output stays bit-exact`() {
        val fs = 48_000
        val sine = Pcm.sine(20_000, 2, fs, 440.0)
        val plain = FakeInnerSink()
        SeionAudioSink(plain.sink).apply {
            configure(Pcm.config(Pcm.format(C.ENCODING_PCM_FLOAT, fs, 2)))
            Pcm.feed(this, Pcm.float32(sine), 8, fs, Random(2))
        }
        val tap = SpectrumTap().apply { enabled = true }
        val tapped = FakeInnerSink()
        sinkWith(tap, tapped).apply {
            configure(Pcm.config(Pcm.format(C.ENCODING_PCM_FLOAT, fs, 2)))
            Pcm.feed(this, Pcm.float32(sine), 8, fs, Random(2))
        }
        assertTrue(tap.writtenFrames > 0)
        assertEquals(fs, tap.sampleRate)
        val a = plain.floats()
        val b = tapped.floats()
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals("sample $i", java.lang.Float.floatToRawIntBits(a[i]), java.lang.Float.floatToRawIntBits(b[i]))
        // The ring holds the delayed output: the last frames are the sine, downmixed (L = R here).
        val win = FloatArray(1024)
        assertTrue(tap.read(tap.writtenFrames, 1024, win))
        assertTrue(win.any { kotlin.math.abs(it) > 0.1f })
    }

    @Test fun downmixAndWrap() {
        val tap = SpectrumTap(capacity = 8).apply { enabled = true }
        tap.sampleRate = 48_000
        tap.write(floatArrayOf(1f, -1f, 0.5f, 0.5f, 0.2f, 0f), 3, 2, 0)
        val dst = FloatArray(3)
        assertTrue(tap.read(3, 3, dst))
        assertEquals(listOf(0f, 0.5f, 0.1f), dst.toList())
        // 7 more mono frames cross the ring's end (10 > 8): the newest 8 read back in order.
        tap.write(FloatArray(7) { (it + 1).toFloat() }, 7, 1, 1_000)
        val last = FloatArray(8)
        assertTrue(tap.read(10, 8, last))
        assertEquals(listOf(0.5f, 0.1f, 1f, 2f, 3f, 4f, 5f, 6f, 7f).takeLast(8), last.toList())
        assertEquals(10L, tap.writtenFrames)
        // Frame indices are monotonic: a later position maps to a later frame.
        assertTrue(tap.frameAt(1_000)!! > tap.frameAt(0)!!)
    }

    private val buf = FloatArray(DspEngine.MAX_CHANNELS * 1024) { (it % 17) / 17f }
    private var pts = 0L

    private fun writes(tap: SpectrumTap, n: Int) {
        for (i in 0 until n) {
            tap.write(buf, 1024, 2, pts)
            tap.audible(pts, pts * 1000)
            pts += 21_333
        }
    }

    // No allocation in the tap either: 0 bytes on the audio thread across 10^4 buffers, enabled or not.
    @Test fun noAllocationOnWrite() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val tap = SpectrumTap().apply { enabled = true }
        writes(tap, 20_000) // warm-up: JIT
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        writes(tap, 10_000)
        val enabledBytes = bean.getThreadAllocatedBytes(id) - before
        tap.enabled = false
        writes(tap, 20_000)
        val off = bean.getThreadAllocatedBytes(id)
        writes(tap, 10_000)
        val disabledBytes = bean.getThreadAllocatedBytes(id) - off
        assertEquals("bytes allocated in 10^4 writes (enabled)", 0L, enabledBytes)
        assertEquals("bytes allocated in 10^4 writes (disabled)", 0L, disabledBytes)
    }

    @Test fun audibleFrameMapping() {
        val tap = SpectrumTap().apply { enabled = true }
        tap.sampleRate = 48_000
        tap.write(FloatArray(4800), 4800, 1, 0)
        tap.write(FloatArray(4800), 4800, 1, 100_000)
        assertEquals(2400L, tap.frameAt(50_000))
        assertEquals(4800L + 480, tap.frameAt(110_000))
    }
}
