package org.seion.playback.dsp

import java.util.Random
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.playback.dsp.testutil.Meters
import org.seion.playback.dsp.testutil.Signals

/**
 * Loudness of CrystalX+ (headphones, +1.0 dB makeup, with the limiter) on synthetic masters: pink noise plus a
 * 40–110 Hz sub band, stereo. Class bounds: typical within
 * ±1.5 LU and never above +0.5; brickwalled (PLR ≈ 7 dB) within [−3.5, +0.5]; quiet (≤ −17 LUFS) within [−0.5, +1.25].
 */
class CrystalLoudnessTest {
    private val fs = 48_000
    private val n = fs * 20

    private fun master(lufs: Double, bassDb: Double, seed: Long, brickwall: Boolean = false): FloatArray {
        val r = Random(seed)
        val b = doubleArrayOf(0.049922035, -0.095993537, 0.050612699, -0.004408786)
        val a = doubleArrayOf(1.0, -2.494956002, 2.017265875, -0.522189400)
        val p = DoubleArray(n)
        val xh = DoubleArray(4)
        val yh = DoubleArray(4)
        for (i in 0 until n) {
            val w = r.nextGaussian()
            val y = b[0] * w + b[1] * xh[0] + b[2] * xh[1] + b[3] * xh[2] - a[1] * yh[0] - a[2] * yh[1] - a[3] * yh[2]
            xh[2] = xh[1]
            xh[1] = xh[0]
            xh[0] = w
            yh[2] = yh[1]
            yh[1] = yh[0]
            yh[0] = y
            p[i] = y
        }
        val sd = kotlin.math.sqrt(p.sumOf { it * it } / n)
        val c = DoubleArray(10)
        val sections = org.seion.playback.dsp.core.Filters.butterworth(
            2,
            org.seion.playback.dsp.core.Filters.Kind.BAND,
            40.0,
            110.0,
            fs,
            c
        )
        val noise = FloatArray(n) { r.nextGaussian().toFloat() }
        var sub = noise
        for (s in 0 until sections) sub = Signals.biquad(sub, c.copyOfRange(s * 5, s * 5 + 5))
        val subSd = Signals.rms(sub)
        val mono = DoubleArray(n) { p[it] / sd + 10.0.pow(bassDb / 20) * sub[it] / subSd }
        // soft-clipped for the brickwalled class: PLR about 7 dB
        val shaped = if (brickwall) DoubleArray(n) { kotlin.math.tanh(mono[it] * 0.3) } else mono
        val stereo = FloatArray(n * 2) { i ->
            val k = i / 2
            if (i % 2 == 0) shaped[k].toFloat() else (shaped[k] * 0.9 + 0.1 * shaped[(k - 7 + n) % n]).toFloat()
        }
        var peak = 0f
        for (v in stereo) peak = maxOf(peak, kotlin.math.abs(v))
        for (i in stereo.indices) stereo[i] *= 0.5f / peak
        val g = 10.0.pow((lufs - Meters.lufs(stereo, 2, fs)) / 20)
        return FloatArray(stereo.size) { (stereo[it] * g).toFloat() }
    }

    private fun delta(x: FloatArray): Double {
        val e = DspEngine(cx = CrystalX(), limiter = TruePeakLimiter()).apply { configure(fs, 2) }
        e.setParams(DspParams(crystalX = true))
        val y = x.copyOf()
        var f = 0
        val buf = FloatArray(512)
        while (f < n) {
            val k = minOf(256, n - f)
            System.arraycopy(y, f * 2, buf, 0, k * 2)
            e.process(buf, k)
            System.arraycopy(buf, 0, y, f * 2, k * 2)
            f += k
        }
        val l = e.latency
        val skip = fs / 2
        val inSeg = x.copyOfRange(skip * 2, (n - l) * 2)
        val outSeg = y.copyOfRange((skip + l) * 2, n * 2)
        return Meters.lufs(outSeg, 2, fs) - Meters.lufs(inSeg, 2, fs)
    }

    @Test fun `loudness stays inside its class bounds`() {
        val typical = delta(master(-14.0, 0.0, 1))
        assertTrue("typical −14 LUFS: $typical LU", typical in -1.5..0.5)
        val bw = master(-7.0, 0.0, 2, brickwall = true)
        val plr = Meters.truePeakDb(bw, 2, fs) - Meters.lufs(bw, 2, fs)
        assertTrue("brickwalled PLR $plr", plr in 5.5..9.0)
        val brick = delta(bw)
        assertTrue("brickwalled: $brick LU", brick in -3.5..0.5)
        val quiet = StringBuilder()
        for ((lufs, bass) in listOf(-20.0 to 0.0, -23.0 to 0.0, -20.0 to 10.0)) {
            val d = delta(master(lufs, bass, 3))
            quiet.append(" quiet $lufs/+$bass $d LU;")
            assertTrue("quiet $lufs LUFS bass +$bass: $d LU", d in -0.5..1.25)
        }
        println("CrystalX+ loudness: typical $typical LU, brickwalled $brick LU (PLR $plr);$quiet")
    }
}
