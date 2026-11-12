package org.seion.playback.dsp

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.seion.playback.dsp.testutil.Signals

/**
 * An always-on smoke test (10 s with both features in < 1 s) and the opt-in benchmark
 * (-Pseion.bench=true: HotSpot after 10 s of warm-up; both on at 48 kHz stereo ≥ 40× real time, CrystalX+ only at
 * 96 kHz ≥ 60×, idle ≥ 1000×).
 */
class DspBenchmarkTest {
    private fun engine(fs: Int, cx: Boolean, rp: Boolean): DspEngine {
        val pure = RenovaPure().apply { if (rp) forceFc = 16_000.0 }
        return DspEngine(cx = CrystalX(), rp = pure, limiter = TruePeakLimiter()).apply {
            configure(fs, 2)
            setParams(DspParams(crystalX = cx, renovaPure = rp))
        }
    }

    /** Seconds of audio per second of CPU for [seconds] of a stereo mix, after [warmup] seconds. */
    private fun speed(fs: Int, cx: Boolean, rp: Boolean, seconds: Int, warmup: Int): Double {
        val e = engine(fs, cx, rp)
        val x = Signals.interleave(Signals.mix(fs, fs, 1), Signals.mix(fs, fs, 2))
        val buf = FloatArray(4096)
        fun run(sec: Int) {
            var f = 0
            while (f < fs * sec) {
                System.arraycopy(x, (f % (fs - 2048)) * 2, buf, 0, 4096)
                e.process(buf, 2048)
                f += 2048
            }
        }
        run(warmup)
        val t0 = System.nanoTime()
        run(seconds)
        return seconds / ((System.nanoTime() - t0) / 1e9)
    }

    @Test fun `smoke - 10 s with both features in under 1 s`() {
        val s = speed(48_000, cx = true, rp = true, seconds = 10, warmup = 2)
        println("Benchmark smoke: both on, 48 kHz stereo: ${"%.1f".format(s)}x real time")
        assertTrue("${s}x", s >= 10)
    }

    @Test fun `benchmark - both on, CrystalX at 96 kHz, idle`() {
        assumeTrue(System.getProperty("seion.bench") == "true")
        val both = speed(48_000, cx = true, rp = true, seconds = 30, warmup = 10)
        val cx96 = speed(96_000, cx = true, rp = false, seconds = 30, warmup = 10)
        val cx48 = speed(48_000, cx = true, rp = false, seconds = 30, warmup = 10)
        val pa48 = speed(48_000, cx = false, rp = true, seconds = 30, warmup = 10)
        val idle = speed(48_000, cx = false, rp = false, seconds = 120, warmup = 10)
        println(
            "Benchmark: both on 48 kHz ${"%.1f".format(
                both
            )}x; CrystalX+ 48 kHz ${"%.1f".format(
                cx48
            )}x; RenovaPure 48 kHz ${"%.1f".format(pa48)}x; CrystalX+ 96 kHz ${"%.1f".format(cx96)}x; idle ${"%.0f".format(idle)}x"
        )
        assertTrue("both ${both}x", both >= 40)
        assertTrue("CrystalX+ 96 kHz ${cx96}x", cx96 >= 60)
        assertTrue("idle ${idle}x", idle >= 1000)
    }
}
