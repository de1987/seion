package org.seion.playback.dsp

import java.lang.management.ManagementFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.playback.dsp.testutil.Signals

/** The full engine with both features: no allocation, determinism, denormal decay and NaN recovery. */
class EngineTest {
    private val fs = 48_000

    private fun engine(stats: DspStats? = null, cx: WetChain = CrystalX()): DspEngine {
        val rp = RenovaPure().apply { forceFc = 16_000.0 }
        return DspEngine(stats, cx = cx, rp = rp, limiter = TruePeakLimiter()).apply {
            configure(fs, 2)
            setParams(DspParams(crystalX = true, renovaPure = true))
        }
    }

    private fun run(e: DspEngine, x: FloatArray, block: Int = 256): FloatArray {
        val out = x.copyOf()
        val buf = FloatArray(block * 2)
        var f = 0
        while (f < x.size / 2) {
            val n = minOf(block, x.size / 2 - f)
            System.arraycopy(out, f * 2, buf, 0, n * 2)
            e.process(buf, n)
            System.arraycopy(buf, 0, out, f * 2, n * 2)
            f += n
        }
        return out
    }

    // 0 bytes allocated across 10^4 steady-state blocks with both features on.
    @Test fun `the steady state allocates nothing`() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val e = engine()
        val x = Signals.interleave(Signals.mix(fs, fs, 1), Signals.mix(fs, fs, 2))
        val buf = FloatArray(512)
        fun blocks(count: Int) {
            var f = 0
            repeat(count) {
                System.arraycopy(x, (f % (fs - 256)) * 2, buf, 0, 512)
                e.process(buf, 256)
                f += 256
            }
        }
        blocks(2000) // warm-up: JIT, first decisions
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        blocks(10_000)
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertEquals("bytes allocated in 10^4 blocks", 0L, allocated)
    }

    // The same input gives the same output, also after a flush and a replay, whatever the slicing.
    @Test fun `output is deterministic`() {
        val x = Signals.interleave(Signals.mix(fs * 2, fs, 3), Signals.mix(fs * 2, fs, 4))
        val a = run(engine(), x)
        val b = run(engine(), x, block = 100)
        assertArrayEquals(a, b, 0f)
        val e = engine()
        run(e, x)
        e.flush()
        val c1 = run(e, x)
        e.flush()
        val c2 = run(e, x)
        assertArrayEquals(c1, c2, 0f)
        assertTrue(a.all { it.isFinite() })
    }

    // 60 s of digital silence after a burst: the output decays to exactly 0 and blocks do not slow down.
    @Test fun `silence after a burst settles to exact zero without slowing down`() {
        val e = engine()
        val burst = Signals.interleave(Signals.pink(fs, 5, 0.3), Signals.pink(fs, 6, 0.3))
        run(e, burst)
        val silence = FloatArray(fs * 60 * 2)
        val t0 = System.nanoTime()
        val out = run(e, silence)
        val tSilence = (System.nanoTime() - t0).toDouble() / (fs * 60)
        val steady = Signals.interleave(Signals.pink(fs * 60, 7, 0.1), Signals.pink(fs * 60, 8, 0.1))
        val t1 = System.nanoTime()
        run(engine(), steady)
        val tSteady = (System.nanoTime() - t1).toDouble() / (fs * 60)
        assertTrue("silence ${tSilence}ns/frame vs steady ${tSteady}ns/frame", tSilence <= 1.2 * tSteady)
        assertTrue("tail is exactly zero", out.copyOfRange(out.size - fs * 2, out.size).all { it == 0f })
    }

    // A NaN from a wet stage never reaches the output; the block falls back to the dry signal.
    @Test fun `a NaN in a wet stage never reaches the output`() {
        var poison = false
        val nanChain =
            object : WetChain {
                private val real = CrystalX()

                override fun configure(fs: Int, ch: Int) = real.configure(fs, ch)

                override fun reset() = real.reset()

                override fun process(y1: FloatArray, out: FloatArray, frames: Int) {
                    real.process(y1, out, frames)
                    if (poison) out[3] = Float.NaN
                }
            }
        val stats = DspStats({ 0L })
        val e = engine(stats, nanChain)
        val x = Signals.interleave(Signals.mix(fs, fs, 9), Signals.mix(fs, fs, 10))
        run(e, x.copyOf())
        poison = true
        val y = run(e, x.copyOf(), block = 256).copyOfRange(0, 2048)
        poison = false
        val z = run(e, x.copyOf())
        assertTrue(y.all { it.isFinite() } && z.all { it.isFinite() })
        assertTrue("non-finite resets ${stats.nonFiniteResets}", stats.nonFiniteResets > 0)
    }

    // The idle self-check passes on the copy path and trips when the idle path is altered.
    @Test fun `the idle self-check trips on a broken idle path`() {
        val x = Signals.interleave(Signals.white(fs * 3, 1, 0.3), Signals.white(fs * 3, 2, 0.3))
        val ok = DspEngine().apply {
            configure(fs, 2)
            selfCheck = true
        }
        run(ok, x)
        assertTrue(!ok.selfCheckFailed)
        val broken = DspEngine().apply {
            configure(fs, 2)
            selfCheck = true
            breakIdlePath = true
        }
        run(broken, x)
        assertTrue(broken.selfCheckFailed)
    }
}
