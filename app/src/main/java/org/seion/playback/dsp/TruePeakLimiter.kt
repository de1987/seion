package org.seion.playback.dsp

import org.seion.playback.dsp.core.Denormal
import org.seion.playback.dsp.core.TpUpsampler

/**
 * The shared, final true-peak limiter: ceiling −1 dBTP, lookahead la = rint(5 ms·fs), BS.1770-4
 * detection (4× at ≤ 48 kHz, 2× at ≤ 96 kHz, 1× above), channels linked. It computes the gain for the D2-delayed
 * sample (D2 = la + 6); the engine applies `out = m[n − la − 6]·(1 − gL·(1 − g))`.
 *
 * Per input sample n (undelayed m):
 * 1. detection with integer alignment: the interpolated phases of base index k lie between k − 6 and k − 5
 *    (D_tp = 5.875) and are attributed to both, so `pk[n − 6] = max(v[n], v[n − 1])`;
 * 2. `need = min(1, c / pk)`; 3. `w = min(need over la + 1 samples)` (monotonic deque);
 * 4. hold 20 ms, then release toward w with α blended 80 → 400 ms by `busy` (one-pole 0.5 s of [w < 0.999]); r ≤ w;
 * 5. two cascaded box-cars k1 = rint(1 ms·fs), k2 = la + 2 − k1 (double running sums, re-synced every 65536);
 * 6. `g = min(boxcars, need[n − 6 − la])`: the final safety, no sample over.
 * Everything is preallocated for 192 kHz.
 */
class TruePeakLimiter : GainComputer {
    private val tp = TpUpsampler(DspEngine.MAX_CHANNELS)
    private var channels = 1
    private var la = 0
    private var k1 = 1
    private var k2 = 1
    private var holdFrames = 0
    private var alphaFast = 0.0
    private var alphaSlow = 0.0
    private var busyCoef = 0.0

    private val maxLa = DspEngine.maxLookahead()
    private val needRing = DoubleArray(maxLa + 2)
    private var needPos = 0
    private val dqIdx = LongArray(maxLa + 2)
    private val dqVal = DoubleArray(maxLa + 2)
    private var dqHead = 0
    private var dqSize = 0
    private val box1 = DoubleArray(Math.rint(0.001 * DspEngine.MAX_RATE).toInt() + 1)
    private val box2 = DoubleArray(maxLa + 2)
    private var b1Pos = 0
    private var b2Pos = 0
    private var sum1 = 0.0
    private var sum2 = 0.0
    private var sinceResync = 0
    private var vPrev = 0.0
    private var r = 1.0
    private var hold = 0
    private var busy = 0.0
    private var t = 0L

    override fun configure(fs: Int, ch: Int) {
        channels = ch
        tp.configure(fs)
        la = DspEngine.lookahead(fs)
        k1 = Math.rint(0.001 * fs).toInt().coerceAtLeast(1)
        k2 = (la + 2 - k1).coerceAtLeast(1)
        holdFrames = Math.rint(HOLD_S * fs).toInt()
        alphaFast = StrictMath.exp(-1.0 / (fs * RELEASE_FAST_S))
        alphaSlow = StrictMath.exp(-1.0 / (fs * RELEASE_SLOW_S))
        busyCoef = StrictMath.exp(-1.0 / (fs * BUSY_S))
        reset()
    }

    override fun reset() {
        tp.reset()
        needRing.fill(1.0)
        needPos = 0
        dqHead = 0
        dqSize = 0
        box1.fill(1.0)
        box2.fill(1.0)
        b1Pos = 0
        b2Pos = 0
        sum1 = k1.toDouble()
        sum2 = k2.toDouble()
        sinceResync = 0
        vPrev = 0.0
        r = 1.0
        hold = 0
        busy = 0.0
        t = 0
    }

    override fun gains(m: FloatArray, frames: Int, g: FloatArray) {
        val ch = channels
        val ringLen = la + 1
        for (f in 0 until frames) {
            // 1. detection (linked): the largest interpolated value of this base index over channels
            var v = 0.0
            for (c in 0 until ch) {
                val p = tp.push(m[f * ch + c].toDouble(), c)
                if (p > v) v = p
            }
            val pk = if (v > vPrev) v else vPrev
            vPrev = v
            // 2. need for sample j = t − 6
            val need = if (pk > CEILING) CEILING / pk else 1.0
            // the ring holds need[j − la .. j]; after writing need[j] its oldest slot is need[j − la]
            needRing[needPos] = need
            needPos = if (needPos + 1 == ringLen) 0 else needPos + 1
            val oldest = needRing[needPos]
            // 3. sliding minimum over need[j − la .. j]
            while (dqSize > 0 && dqVal[(dqHead + dqSize - 1) % dqVal.size] >= need) dqSize--
            dqIdx[(dqHead + dqSize) % dqIdx.size] = t
            dqVal[(dqHead + dqSize) % dqVal.size] = need
            dqSize++
            while (dqIdx[dqHead] <= t - ringLen) {
                dqHead = (dqHead + 1) % dqIdx.size
                dqSize--
            }
            val w = dqVal[dqHead]
            // 4. hold and programme-dependent release (upward only), r ≤ w
            busy = busyCoef * busy + (1 - busyCoef) * (if (w < 0.999) 1.0 else 0.0)
            if (w <= r) {
                r = w
                hold = holdFrames
            } else if (hold > 0) {
                hold--
            } else {
                val a = alphaFast + (alphaSlow - alphaFast) * minOf(1.0, 2 * busy)
                r += (1 - a) * (w - r)
            }
            // 5. triangular attack: two cascaded box-cars
            sum1 += r - box1[b1Pos]
            box1[b1Pos] = r
            b1Pos = if (b1Pos + 1 == k1) 0 else b1Pos + 1
            val o1 = sum1 / k1
            sum2 += o1 - box2[b2Pos]
            box2[b2Pos] = o1
            b2Pos = if (b2Pos + 1 == k2) 0 else b2Pos + 1
            val o2 = sum2 / k2
            if (++sinceResync == RESYNC) resync()
            // 6. final safety against the need of the sample now leaving the delay line (j − la)
            g[f] = (if (o2 < oldest) o2 else oldest).toFloat()
            t++
        }
        busy = Denormal.flush(busy)
    }

    /** The running sums drift by rounding: recompute them exactly every 65536 samples. */
    private fun resync() {
        sinceResync = 0
        var s = 0.0
        for (i in 0 until k1) s += box1[i]
        sum1 = s
        s = 0.0
        for (i in 0 until k2) s += box2[i]
        sum2 = s
    }

    companion object {
        /** 10^(−1/20): −1.0 dBTP. */
        const val CEILING = 0.8912509381337456
        const val HOLD_S = 0.020
        const val RELEASE_FAST_S = 0.080
        const val RELEASE_SLOW_S = 0.400
        const val BUSY_S = 0.5
        private const val RESYNC = 65_536

        /** The worst under-read of the detector at 20 kHz: −20·log10 cos(π·20 kHz/(L·fs)) dB. */
        fun underReadDb(fs: Int): Double {
            val l = if (fs <= 48_000) {
                4
            } else if (fs <= 96_000) {
                2
            } else {
                1
            }
            return -20 * StrictMath.log10(StrictMath.cos(Math.PI * 20_000.0 / (l * fs)))
        }
    }
}
