package org.seion.playback.dsp

import org.seion.playback.dsp.core.Biquad
import org.seion.playback.dsp.core.EnvFollower
import org.seion.playback.dsp.core.FastMath
import org.seion.playback.dsp.core.Filters
import org.seion.playback.dsp.core.Halfband2x

/**
 * CrystalX+: a zero-latency chain on y1, mixed by the engine with `m = (1 − gC)·y1 + gC·cx`. Per channel, control
 * rate 32 samples, gains interpolated linearly per sample:
 * 1. pre-gain −3 dB; 2. high shelf f0 = min(9 kHz, 0.4·fs), +1.5 dB, S 0.7;
 * 3. dynamic low shelf in parallel-blend form `y += s·a(t)·(H(y) − y)`, H = low shelf 121 Hz +5.5 dB S 0.3,
 *    a(t) from a mono sidechain (after stage 2) → Butterworth LP2 120 Hz → block peak → 10/150 ms follower →
 *    `a = 1 − clamp(20·log10 e − T, 0, R)/R`;
 * 4. parallel exciter: Butterworth BP 2.5–6 kHz → Up31 → `(tanh(2.5b) + 0.15·tanh²(2.5b))/2.5 − b` → Down95 →
 *    HP2 5 kHz, added at 10^((−20 − 6·gP)/20)·duck, duck = 1/(1 + (e_b/0.1)²), e_b a 2/60 ms follower of the band;
 * 5. stereo stage: crossfeed (amount x, g = −7.5 dB, first-order 700 Hz) and/or width (M/S, `S += (w − 1)·HP2₃₀₀(S)`);
 * 6. makeup +1.0 dB.
 * Voicings (HEADPHONES / SPEAKER / LINE) are continuous parameters (s, T, R, x, w) that morph linearly over 30 ms.
 */
class CrystalX : WetChain {
    private var fs = 48_000
    private var ch = 2
    private val coeff = DoubleArray(20)

    private val highShelf = Biquad()
    private val lowShelf = Biquad()
    private val sideLp = Biquad(1)
    private val exciter = Exciter()
    private val xfLp = Biquad()
    private val xfHp = Biquad()
    private val widthHp = Biquad(1)
    private val sideEnv = EnvFollower(CONTROL)
    private val bandEnv = EnvFollower(CONTROL)

    // Control-rate values: previous and current block, interpolated per sample.
    private var amountPrev = 1.0
    private var amountCur = 1.0
    private var duckPrev = 1.0
    private var duckCur = 1.0
    private var sidePeak = 0.0
    private var bandPeak = 0.0
    private var exciterGain = EXCITER_DB_TO_LIN
    private var lastGp = 0f

    // Voicing parameters (current values and targets), morphing over 30 ms.
    private val cur = DoubleArray(5)
    private val target = DoubleArray(5)
    private var morphStep = 0.0

    // The stage-4 output of the current frame, and the position in the control block (persists across calls).
    private val stage = DoubleArray(2)
    private var ctrlPos = 0

    override fun configure(fs: Int, ch: Int) {
        this.fs = fs
        this.ch = ch
        Filters.shelf(fs, minOf(9000.0, 0.40 * fs), 1.5, false, 0.7, coeff)
        highShelf.set(coeff)
        Filters.shelf(fs, 121.0, 5.5, true, 0.3, coeff)
        lowShelf.set(coeff)
        Filters.butterworth(2, Filters.Kind.LOW, 120.0, 0.0, fs, coeff)
        sideLp.set(coeff)
        exciter.configure(fs)
        Filters.butterworth(1, Filters.Kind.LOW, 700.0, 0.0, fs, coeff)
        xfLp.set(coeff)
        Filters.butterworth(1, Filters.Kind.HIGH, 700.0, 0.0, fs, coeff)
        xfHp.set(coeff)
        Filters.butterworth(2, Filters.Kind.HIGH, 300.0, 0.0, fs, coeff)
        widthHp.set(coeff)
        sideEnv.configure(fs, 0.010, 0.150)
        bandEnv.configure(fs, 0.002, 0.060)
        morphStep = 1.0 / (DspEngine.RAMP_S * fs)
        System.arraycopy(target, 0, cur, 0, 5)
        reset()
    }

    override fun reset() {
        highShelf.reset()
        lowShelf.reset()
        sideLp.reset()
        exciter.reset()
        xfLp.reset()
        xfHp.reset()
        widthHp.reset()
        sideEnv.reset()
        bandEnv.reset()
        amountPrev = 1.0
        amountCur = 1.0
        duckPrev = 1.0
        duckCur = 1.0
        ctrlPos = 0
        sidePeak = 0.0
        bandPeak = 0.0
        System.arraycopy(target, 0, cur, 0, 5)
    }

    init {
        setVoicing(Voicing.HEADPHONES)
        System.arraycopy(target, 0, cur, 0, 5)
    }

    override fun setVoicing(v: Voicing) {
        val p = VOICINGS[v.ordinal]
        System.arraycopy(p, 0, target, 0, 5)
        morphing = true
    }

    private var morphing = true

    /** RenovaPure's per-frame amount for the next [process] call (the exciter is 6 dB quieter, scaled by gP). */
    private var pureAmounts: FloatArray? = null

    override fun setRenovaPureAmounts(gP: FloatArray?) {
        pureAmounts = gP
    }

    /**
     * The control grid (32 samples) runs continuously across calls, so the output does not depend on how the stream
     * is sliced into calls.
     */
    override fun process(y1: FloatArray, out: FloatArray, frames: Int) {
        val c = ch
        for (f in 0 until frames) {
            ctrlPos++
            val t = ctrlPos.toDouble() / CONTROL
            val amount = amountPrev + (amountCur - amountPrev) * t
            val duck = duckPrev + (duckCur - duckPrev) * t
            // voicing morph (per sample, linear, 30 ms)
            for (k in 0 until 5) {
                val d = target[k] - cur[k]
                val step = morphStep * MORPH_RANGE[k]
                cur[k] = if (d > step) {
                    cur[k] + step
                } else if (d < -step) {
                    cur[k] - step
                } else {
                    target[k]
                }
            }
            val rp = pureAmounts
            val gp = if (rp != null) rp[f] else 0f
            if (gp != lastGp) {
                lastGp = gp
                exciterGain = if (gp == 0f) EXCITER_DB_TO_LIN else FastMath.fromDb20(EXCITER_DB - 6.0 * gp)
            }
            var side = 0.0
            for (k in 0 until c) {
                val i = f * c + k
                var y = y1[i] * PRE_GAIN
                y = highShelf.process(y, k)
                side += y
                // 3. dynamic low shelf (parallel blend)
                val h = lowShelf.process(y, k)
                y += cur[S] * amount * (h - y)
                // 4. parallel exciter
                val hx = exciter.process(y, k)
                if (exciter.lastBand > bandPeak) bandPeak = exciter.lastBand
                if (exciterEnabled) y += exciterGain * duck * hx
                stage[k] = y
            }
            val sl = kotlin.math.abs(sideLp.process(side / c, 0))
            if (sl > sidePeak) sidePeak = sl
            // 5. stereo stage and 6. makeup
            val o = f * c
            if (c == 2) {
                val l = stage[0]
                val r = stage[1]
                val x = cur[X]
                // The crossfeed filters always run, so a voicing morph starts from a live state.
                val xl = (l + XF_G * xfHp.process(l, 0) + XF_G * xfLp.process(r, 0)) / (1 + XF_G)
                val xr = (r + XF_G * xfHp.process(r, 1) + XF_G * xfLp.process(l, 1)) / (1 + XF_G)
                val ol = (1 - x) * l + x * xl
                val or = (1 - x) * r + x * xr
                val m = (ol + or) / 2
                var sd = (ol - or) / 2
                sd += (cur[W] - 1) * widthHp.process(sd, 0)
                out[o] = ((m + sd) * MAKEUP).toFloat()
                out[o + 1] = ((m - sd) * MAKEUP).toFloat()
            } else {
                out[o] = (stage[0] * MAKEUP).toFloat()
            }
            if (ctrlPos == CONTROL) controlUpdate()
        }
        highShelf.flushDenormals()
        lowShelf.flushDenormals()
        sideLp.flushDenormals()
        exciter.flushDenormals()
        xfLp.flushDenormals()
        xfHp.flushDenormals()
        widthHp.flushDenormals()
    }

    /** Control rate: the sidechain and the band envelope set the next block's targets. */
    private fun controlUpdate() {
        ctrlPos = 0
        amountPrev = amountCur
        val e = sideEnv.feed(sidePeak)
        val over = (20 * kotlin.math.log10(e + 1e-9) - cur[T]).coerceIn(0.0, cur[R])
        val computed = 1 - over / cur[R]
        val r = replay
        // explicit branches: an elvis over Double? would box on the audio thread
        val forced = forcedAmount
        amountCur = if (r != null && controlIndex < r.size) {
            r[controlIndex]
        } else if (forced != null) {
            forced
        } else {
            computed
        }
        trace?.let { if (controlIndex < it.size) it[controlIndex] = amountCur }
        controlIndex++
        duckPrev = duckCur
        val q = bandEnv.feed(bandPeak) / 0.1
        duckCur = 1 / (1 + q * q)
        sidePeak = 0.0
        bandPeak = 0.0
    }

    /** Test hook: when set, the low-shelf amount a(t) follows it at control rate instead of the sidechain. */
    internal var forcedAmount: Double? = null

    /** Test hooks: record the per-control-block amounts, or replay recorded ones. */
    internal var trace: DoubleArray? = null
    internal var replay: DoubleArray? = null
    private var controlIndex = 0

    /** Test hook: the exciter can be left out to test the dynamic shelf alone. */
    internal var exciterEnabled = true

    /** Test view: the current low-shelf amount a(t) and voicing parameters (s, T, R, x, w). */
    internal val bassAmount: Double get() = amountCur
    internal val voicingValues: DoubleArray get() = cur.copyOf()

    companion object {
        const val CONTROL = 32
        const val UP_TAPS = 31
        const val DOWN_TAPS = 95
        const val DRIVE = 2.5
        const val EXCITER_DB = -20.0
        val EXCITER_DB_TO_LIN = StrictMath.pow(10.0, EXCITER_DB / 20)
        val PRE_GAIN = StrictMath.pow(10.0, -3.0 / 20)
        val MAKEUP = StrictMath.pow(10.0, 1.0 / 20)
        val XF_G = StrictMath.pow(10.0, -7.5 / 20)

        private const val S = 0
        private const val T = 1
        private const val R = 2
        private const val X = 3
        private const val W = 4

        /** Per voicing: shelf scale s, sidechain threshold T (dBFS), range R (dB), crossfeed x, width w. */
        val VOICINGS =
            arrayOf(
                doubleArrayOf(1.0, -20.0, 10.0, 1.0, 1.0), // HEADPHONES
                doubleArrayOf(0.55, -22.0, 8.0, 0.0, 1.15), // SPEAKER
                doubleArrayOf(1.0, -20.0, 10.0, 0.0, 1.0) // LINE
            )

        /** The largest change of each parameter between voicings, so every parameter morphs in 30 ms. */
        private val MORPH_RANGE = doubleArrayOf(0.45, 2.0, 2.0, 1.0, 0.15)
    }
}

/**
 * The exciter's product path h (stage 4 above): Butterworth BP 2.5–6 kHz → Up31 → shaper with the linear term removed →
 * Down95 → Butterworth HP2 5 kHz. [oversample] = false is a test-only 1× shaper (to check that oversampling matters).
 */
class Exciter(private val oversample: Boolean = true) {
    private val bp = arrayOf(Biquad(), Biquad())
    private val post = Biquad()
    private val up = Array(2) { Halfband2x(CrystalX.UP_TAPS, maxChannels = 1) }
    private val down = Array(2) { Halfband2x(CrystalX.DOWN_TAPS, maxChannels = 1) }
    private val coeff = DoubleArray(10)
    private val two = DoubleArray(2)

    /** |b| of the last processed sample (for the duck's envelope). */
    var lastBand = 0.0
        private set

    fun configure(fs: Int) {
        Filters.butterworth(2, Filters.Kind.BAND, 2500.0, minOf(6000.0, 0.45 * fs), fs, coeff)
        bp[0].set(coeff, 0)
        bp[1].set(coeff, 5)
        Filters.butterworth(2, Filters.Kind.HIGH, minOf(5000.0, 0.45 * fs), 0.0, fs, coeff)
        post.set(coeff)
        reset()
    }

    fun reset() {
        bp[0].reset()
        bp[1].reset()
        post.reset()
        for (h in up) h.reset()
        for (h in down) h.reset()
        lastBand = 0.0
    }

    fun flushDenormals() {
        bp[0].flushDenormals()
        bp[1].flushDenormals()
        post.flushDenormals()
        for (h in up) h.flushDenormals()
        for (h in down) h.flushDenormals()
    }

    fun process(y: Double, ch: Int): Double {
        var b = bp[0].process(y, ch)
        b = bp[1].process(b, ch)
        lastBand = kotlin.math.abs(b)
        val p =
            if (oversample) {
                up[ch].up(b, 0, two, 0)
                down[ch].down(shaper(two[0]), shaper(two[1]), 0)
            } else {
                shaper(b)
            }
        return post.process(p, ch)
    }

    /** The nonlinearity with the linear term removed (odd + a little even). */
    private fun shaper(b: Double): Double {
        val th = FastMath.tanh(CrystalX.DRIVE * b)
        return (th + 0.15 * th * th) / CrystalX.DRIVE - b
    }
}
