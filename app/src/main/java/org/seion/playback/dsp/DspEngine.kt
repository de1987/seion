package org.seion.playback.dsp

/**
 * The sample-synchronous DSP engine: n frames in, n frames out, in place, with a constant latency
 * [latency] = RenovaPure's dry delay D1 (511 at 44.1/48 kHz, else 0) + D2 = the limiter lookahead la = rint(5 ms × fs)
 * + 6 frames of true-peak detector alignment. Everything is allocated in the constructor, for the largest supported
 * configuration (2 channels, 192 kHz); [configure] only resets state.
 *
 * Signal flow per block of ≤ [BLOCK] frames:
 * `y1 = D1(x) + gP·gen` (RenovaPure adds only its generated band, aligned with D1), `m = (1 − gC)·y1 + gC·cx(y1)`
 * (CrystalX+, equal-gain and linear), `out = D2(m)·(1 − gL·(1 − g))` (the shared limiter's gain g).
 * While idle (both features OFF, gL = 0) [process] is a pure copy through D1 and D2, so the output is the float
 * input delayed by [latency], bit for bit, and no wet stage runs. Zero amounts are skipped, not multiplied,
 * so a stage at amount 0 can never change a bit (or turn 0 × Inf into NaN).
 *
 * Control: each feature runs OFF → WARM (20 ms, wet computed, amount 0) → RAMP_IN (30 ms) → ON →
 * RAMP_OUT (30 ms) → OFF; a toggle during a ramp reverses from the current amount. The limiter amount gL ramps up
 * during WARM and down over 30 ms once neither feature needs it. RenovaPure's activity d(t) ramps over 250 ms.
 *
 * ReplayGain: a linear gain on the undelayed input x, before D1, RenovaPure and CrystalX+, so every stage and the
 * limiter see the levelled signal. Its target comes from the stream's tags ([setStreamGains], at configure, in data
 * order) and the switch/mode/pre-amp ([setParams]); exactly 1.0 when off or untagged, so the idle copy path (and its
 * bit-exact output) is untouched then. A change ramps linearly over [RG_RAMP_S] from the first frame it applies to (a
 * gapless boundary: the new stream's first frame), or is set at once when no audio is in the engine (after a configure
 * or flush). A rise that needs the limiter ([ReplayGain.needsLimiter]) waits until the limiter amount is fully in, and
 * the limiter stays in until the boosted audio has left the delay lines.
 */
class DspEngine(
    private val stats: DspStats? = null,
    private val cx: WetChain = WetChain.Dummy(),
    private val rp: Generator = Generator.Dummy(),
    private val limiter: GainComputer = GainComputer.Unity
) {
    var sampleRate = 0
        private set
    var channels = 1
        private set

    /** RenovaPure's dry delay D1 (frames). */
    var pureDelay = 0
        private set

    /** The limiter's lookahead plus detector alignment D2 (frames). */
    var limiterDelay = 0
        private set

    val latency: Int get() = pureDelay + limiterDelay

    /** Whether any audio entered since the last configure/flush (a tail must then be drained). */
    var hasAudio = false
        private set

    private val d1 = FloatArray(PURE_DELAY * MAX_CHANNELS)
    private var d1Pos = 0
    private val d2 = FloatArray((maxLookahead() + DETECTOR_ALIGN) * MAX_CHANNELS)
    private var d2Pos = 0

    /** D2 of the dry y1, kept while active: the non-finite fallback. */
    private val dry2 = FloatArray(d2.size)

    private val y1 = FloatArray(BLOCK * MAX_CHANNELS)
    private val m = FloatArray(BLOCK * MAX_CHANNELS)
    private val gen = FloatArray(BLOCK * MAX_CHANNELS)
    private val wet = FloatArray(BLOCK * MAX_CHANNELS)
    private val gLim = FloatArray(BLOCK)
    private val aC = FloatArray(BLOCK)
    private val aP = FloatArray(BLOCK)
    private val aL = FloatArray(BLOCK)
    private val aG = FloatArray(BLOCK)

    private val cxState = Feature()
    private val rpState = Feature()
    private var gl = 0.0
    private var activity = 0.0
    private var voicing = Voicing.HEADPHONES
    private var active = false
    private var cxOffFrames = 0
    private var rpOffFrames = 0
    private var idleResetFrames = 0
    private var fadeLen = 1
    private var fadePos = -1
    private var fadeArmed = false
    private var absFrame = 0L

    // ReplayGain: the stream's tags, the user's mode and pre-amp, the applied gain and its target.
    private var rgTrackDb = Double.NaN
    private var rgTrackPeak = Double.NaN
    private var rgAlbumDb = Double.NaN
    private var rgAlbumPeak = Double.NaN
    private var rgMode = ReplayGainMode.OFF
    private var rgPreamp = 0f
    private var rgGain = 1.0
    private var rgTarget = 1.0
    private var rgStep = 0.0
    private var rgLimiter = false
    private var rgLimitFrames = 0
    private var rgRampFrames = 1

    /** Test/debug view of the amounts at the end of the last block. */
    val crystalAmount: Float get() = cxState.amount.toFloat()
    val pureAmount: Float get() = (rpState.amount * activity).toFloat()
    val limiterAmount: Float get() = gl.toFloat()
    val isIdle: Boolean get() = !active

    /** The ReplayGain gain the engine is heading for (1 = unchanged) and whether it engages the limiter. */
    val replayGainTarget: Float get() = rgTarget.toFloat()
    val replayGainLimiter: Boolean get() = rgLimiter
    val renovaPureCutoff: Double get() = rp.cutoffHz
    val renovaPureCodec: CodecClass get() = rp.codecClass

    fun configure(fs: Int, ch: Int) {
        check(ch in 1..MAX_CHANNELS && fs in 1..MAX_RATE) { "unsupported stream" }
        sampleRate = fs
        channels = ch
        pureDelay = if (renovaPureRate(fs)) PURE_DELAY else 0
        limiterDelay = lookahead(fs) + DETECTOR_ALIGN
        cxState.configure(Math.rint(WARM_S * fs).toInt(), Math.rint(RAMP_S * fs).toInt())
        rpState.configure(Math.rint(WARM_S * fs).toInt(), Math.rint(RAMP_S * fs).toInt())
        idleResetFrames = Math.rint(IDLE_RESET_S * fs).toInt()
        fadeLen = Math.rint(FADE_S * fs).toInt().coerceAtLeast(1)
        rgRampFrames = Math.rint(RG_RAMP_S * fs).toInt().coerceAtLeast(1)
        cx.configure(fs, ch)
        cx.setVoicing(voicing)
        rp.configure(fs, ch)
        limiter.configure(fs, ch)
        flush()
    }

    /** Seek: delay lines, wet histories and the limiter are zeroed; switch states and decisions are kept. */
    fun flush() {
        d1.fill(0f)
        d2.fill(0f)
        dry2.fill(0f)
        d1Pos = 0
        d2Pos = 0
        absFrame = 0
        probeFrame = -1
        probeSample = 0
        hasAudio = false
        cx.reset()
        rp.flush()
        limiter.reset()
        // When not idle, a 3 ms linear fade-in starts at the first non-zero output sample.
        fadeArmed = active
        fadePos = -1
    }

    fun reset() {
        cxState.off()
        rpState.off()
        gl = 0.0
        activity = 0.0
        active = false
        rgTrackDb = Double.NaN
        rgTrackPeak = Double.NaN
        rgAlbumDb = Double.NaN
        rgAlbumPeak = Double.NaN
        rgGain = 1.0
        rgTarget = 1.0
        rgStep = 0.0
        rgLimiter = false
        rgLimitFrames = 0
        rp.reset()
        flush()
    }

    /** A new stream at a gapless boundary: filters and histories stay, RenovaPure's detector restarts. */
    fun newStream(codec: CodecClass, mediaKey: String?) {
        rp.newStream(codec, mediaKey)
    }

    /**
     * The ReplayGain values of the stream that starts now (the sink calls it at configure, after [configure] or
     * [newStream]): the next frame processed is that stream's first, so the change lands sample-accurately.
     */
    fun setStreamGains(tags: ReplayGainTags) {
        rgTrackDb = tags.trackDb
        rgTrackPeak = tags.trackPeak
        rgAlbumDb = tags.albumDb
        rgAlbumPeak = tags.albumPeak
        updateReplayGain()
    }

    /** Applies a control snapshot (read once per handleBuffer): switch changes become ramp targets. */
    fun setParams(p: DspParams) {
        cxState.setSwitch(p.crystalX)
        rpState.setSwitch(p.renovaPure && rp.supported)
        if (p.voicing != voicing) {
            voicing = p.voicing
            cx.setVoicing(p.voicing)
        }
        if (p.replayGain != rgMode || p.preampDb != rgPreamp) {
            rgMode = p.replayGain
            rgPreamp = p.preampDb
            updateReplayGain()
        }
    }

    /** A new ReplayGain target: set at once while no audio is in the engine, else ramped from the next frame. */
    private fun updateReplayGain() {
        val target = ReplayGain.linear(rgMode, rgPreamp, rgTrackDb, rgAlbumDb)
        rgLimiter = ReplayGain.needsLimiter(target, ReplayGain.selectedPeak(rgMode, rgTrackDb, rgTrackPeak, rgAlbumDb, rgAlbumPeak))
        rgTarget = target
        if (!hasAudio) {
            // After a configure or flush the delay lines are silent: nothing to click against, and the limiter (reset,
            // its detector ahead of every sample) can be fully in from the first frame.
            rgGain = target
            rgStep = 0.0
            if (rgLimiter) gl = 1.0
        } else {
            rgStep = Math.abs(target - rgGain) / rgRampFrames
        }
    }

    /** Processes [frames] interleaved frames of [buf] in place, in blocks of at most [BLOCK] frames. */
    fun process(buf: FloatArray, frames: Int) {
        if (frames <= 0) return
        hasAudio = true
        var start = 0
        while (start < frames) {
            // blocks end on absolute multiples of BLOCK, so block-rate decisions do not depend on the slicing
            val n = minOf(BLOCK - (absFrame % BLOCK).toInt(), frames - start)
            absFrame += n
            stats?.beginBlock()
            // RenovaPure's activity d is a decision about the stream: it is kept (frozen) while idle, so switching RenovaPure
            // back on for an analysed track only needs the 30 ms ramp.
            val wantsActive =
                cxState.state != Feature.OFF || rpState.state != Feature.OFF || gl > 0.0 || rgGain != 1.0 || rgTarget != 1.0
            if (!wantsActive) {
                active = false
                copyBlock(buf, start * channels, n * channels)
            } else {
                if (!active) {
                    probeFrame = -1 // an idle probe in flight is void once the path is active
                    active = true
                    // In idle the D2 ring holds the dry history: it seeds the non-finite fallback line.
                    System.arraycopy(d2, 0, dry2, 0, d2.size)
                }
                activeBlock(buf, start * channels, n)
            }
            if (fadeArmed || fadePos >= 0) fade(buf, start * channels, n)
            stats?.endBlock()
            start += n
        }
    }

    /**
     * Debug builds: once per second while idle, the value entering D1 is remembered and compared
     * bit for bit with the sample that leaves D2 [latency] frames later; a mismatch raises [selfCheckFailed].
     */
    var selfCheck = false
    var selfCheckFailed = false
        private set
    private var probeFrame = -1L
    private var probeBits = 0
    private var probeSample = 0L

    /** Test hook: a deliberately broken idle path (the self-check must trip). */
    internal var breakIdlePath = false

    /** The idle path: write into each delay ring and read the sample back. No arithmetic. */
    private fun copyBlock(buf: FloatArray, from: Int, count: Int) {
        if (selfCheck) selfCheckBlock(buf, from, count, before = true)
        val n1 = pureDelay * channels
        val n2 = limiterDelay * channels
        var p1 = d1Pos
        var p2 = d2Pos
        val end = from + count
        var i = from
        while (i < end) {
            var x = buf[i]
            if (n1 > 0) {
                val y = d1[p1]
                d1[p1] = x
                x = y
                if (++p1 == n1) p1 = 0
            }
            val z = d2[p2]
            d2[p2] = x
            buf[i] = z
            if (++p2 == n2) p2 = 0
            i++
        }
        d1Pos = p1
        d2Pos = p2
        if (breakIdlePath) for (i in from until from + count) buf[i] *= 0.999f
        if (selfCheck) selfCheckBlock(buf, from, count, before = false)
        val frames = count / channels
        if (cxOffFrames < idleResetFrames) {
            cxOffFrames += frames
            if (cxOffFrames >= idleResetFrames) cx.reset()
        }
        if (rpOffFrames < idleResetFrames) {
            rpOffFrames += frames
            if (rpOffFrames >= idleResetFrames) rp.idleReset()
        }
    }

    private fun selfCheckBlock(buf: FloatArray, from: Int, count: Int, before: Boolean) {
        val frames = count / channels
        val start = absFrame - frames // absolute index of this block's first frame
        if (before) {
            if (probeFrame < 0 && sampleRate > 0 && start - probeSample >= sampleRate) {
                probeSample = start
                probeFrame = start + latency
                probeBits = buf[from].toRawBits()
            }
        } else if (probeFrame in start until start + frames) {
            if (buf[from + ((probeFrame - start) * channels).toInt()].toRawBits() != probeBits) selfCheckFailed = true
            probeFrame = -1
        }
    }

    private fun activeBlock(buf: FloatArray, from: Int, n: Int) {
        val ch = channels
        val count = n * ch
        val rpOn = rpState.state != Feature.OFF
        val cxOn = cxState.state != Feature.OFF
        // ReplayGain keeps the limiter in while its target needs it and until boosted audio has left D1 and D2.
        if (rgLimiter) {
            rgLimitFrames = latency + rgRampFrames + n
        } else if (rgLimitFrames > 0) {
            rgLimitFrames -= minOf(n, rgLimitFrames)
        }
        val lTarget = if (cxOn || (rpOn && (rp.activityTarget > 0f || activity > 0.0)) || rgLimitFrames > 0) 1.0 else 0.0
        val lUp = cxState.warmStep()
        val lDown = cxState.rampStep()
        val dStep = 1.0 / (ACTIVITY_S * sampleRate)
        val pTarget = rp.activityTarget.toDouble()
        var anyC = false
        var anyP = false
        var anyL = false
        var anyG = false
        for (f in 0 until n) {
            aC[f] = cxState.step().toFloat()
            activity = if (activity < pTarget) minOf(pTarget, activity + dStep) else maxOf(pTarget, activity - dStep)
            aP[f] = (rpState.step() * activity).toFloat()
            gl = if (gl < lTarget) minOf(lTarget, gl + lUp) else maxOf(lTarget, gl - lDown)
            if (gl > 1 - 1e-9 && lTarget == 1.0) gl = 1.0
            if (gl < 1e-9 && lTarget == 0.0) gl = 0.0
            aL[f] = gl.toFloat()
            if (rgGain != rgTarget) {
                // A rise that needs the limiter waits until it is fully in; a cut starts at once.
                if (rgTarget > rgGain) {
                    if (!rgLimiter || gl >= 1.0) rgGain = minOf(rgTarget, rgGain + rgStep)
                } else {
                    rgGain = maxOf(rgTarget, rgGain - rgStep)
                }
                if (Math.abs(rgGain - rgTarget) < 1e-12) rgGain = rgTarget
            }
            aG[f] = rgGain.toFloat()
            if (rgGain != 1.0) anyG = true
            if (aC[f] != 0f) anyC = true
            if (aP[f] != 0f) anyP = true
            if (gl != 0.0) anyL = true
        }
        if (cxOn) {
            cxOffFrames = 0
        } else if (cxOffFrames < idleResetFrames) {
            cxOffFrames += n
        }
        if (rpOn) {
            rpOffFrames = 0
        } else if (rpOffFrames < idleResetFrames) {
            rpOffFrames += n
        }

        // ReplayGain on the undelayed x (in place): RenovaPure, D1 and everything after see the levelled input.
        if (anyG) {
            for (i in 0 until count) {
                val g = aG[i / ch]
                if (g != 1f) buf[from + i] *= g
            }
        }
        // y1 = D1(x); RenovaPure sees the undelayed x and adds gP·gen.
        val n1 = pureDelay * ch
        var p1 = d1Pos
        for (i in 0 until count) {
            val x = buf[from + i]
            if (n1 > 0) {
                y1[i] = d1[p1]
                d1[p1] = x
                if (++p1 == n1) p1 = 0
            } else {
                y1[i] = x
            }
        }
        d1Pos = p1
        if (rpOn) {
            rp.process(buf, from, y1, gen, n)
            if (anyP) {
                for (i in 0 until count) {
                    val a = aP[i / ch]
                    if (a != 0f) y1[i] += a * gen[i]
                }
            }
        }
        // m = (1 − gC)·y1 + gC·cx(y1).
        System.arraycopy(y1, 0, m, 0, count)
        if (cxOn) {
            cx.setRenovaPureAmounts(if (anyP) aP else null)
            cx.process(y1, wet, n)
            if (anyC) {
                for (i in 0 until count) {
                    val a = aC[i / ch]
                    if (a != 0f) m[i] = (1f - a) * y1[i] + a * wet[i]
                }
            }
        }
        // The limiter's gain for the D2-delayed samples, from the undelayed m (its detector runs while active).
        if (anyL || lTarget > 0.0) limiter.gains(m, n, gLim) else java.util.Arrays.fill(gLim, 0, n, 1f)
        // out = D2(m)·(1 − gL·(1 − g)); the dry y1 runs through its own D2 line for the non-finite fallback.
        val n2 = limiterDelay * ch
        var p2 = d2Pos
        var finite = true
        for (i in 0 until count) {
            val z = d2[p2]
            d2[p2] = m[i]
            wet[i] = dry2[p2]
            dry2[p2] = y1[i]
            val a = aL[i / ch]
            val o = if (a == 0f) z else z * (1f - a * (1f - gLim[i / ch]))
            if (!o.isFinite()) finite = false
            buf[from + i] = o
            if (++p2 == n2) p2 = 0
        }
        d2Pos = p2
        if (!finite) {
            // A non-finite sample: reset the wet state and play the dry signal for this block; the poisoned m history goes too.
            stats?.onNonFiniteReset()
            for (i in 0 until count) buf[from + i] = if (wet[i].isFinite()) wet[i] else 0f
            System.arraycopy(dry2, 0, d2, 0, d2.size)
            cx.reset()
            rp.flush()
            limiter.reset()
        }
    }

    /** The 3 ms fade-in after a flush while active, starting at the first non-zero output frame. */
    private fun fade(buf: FloatArray, from: Int, n: Int) {
        val ch = channels
        for (f in 0 until n) {
            val base = from + f * ch
            if (fadeArmed) {
                var nonZero = false
                for (c in 0 until ch) if (buf[base + c] != 0f) nonZero = true
                if (!nonZero) continue
                fadeArmed = false
                fadePos = 0
            }
            if (fadePos < 0) return
            val g = fadePos.toFloat() / fadeLen
            for (c in 0 until ch) buf[base + c] *= g
            if (++fadePos >= fadeLen) {
                fadePos = -1
                return
            }
        }
    }

    companion object {
        const val MAX_CHANNELS = 2
        const val MAX_RATE = 192_000
        const val BLOCK = 256

        /** STFT latency N − 1 for N = 512 (a streaming WOLA sample is final only N − 1 samples later). */
        const val PURE_DELAY = 511

        /** Integer alignment of the BS.1770-4 interpolator (D_tp = 5.875 base samples). */
        const val DETECTOR_ALIGN = 6

        const val WARM_S = 0.020
        const val RAMP_S = 0.030
        const val ACTIVITY_S = 0.250
        const val IDLE_RESET_S = 0.100
        const val FADE_S = 0.003

        /** ReplayGain changes ramp linearly over this time. */
        const val RG_RAMP_S = 0.030

        fun renovaPureRate(fs: Int) = fs == 44_100 || fs == 48_000

        /** la = rint(0.005 × fs): 220 at 44.1 kHz, 240 at 48 kHz, 480 at 96 kHz, 960 at 192 kHz. */
        fun lookahead(fs: Int): Int = Math.rint(0.005 * fs).toInt()

        fun maxLookahead(): Int = lookahead(MAX_RATE)

        fun latencyFor(fs: Int): Int = (if (renovaPureRate(fs)) PURE_DELAY else 0) + lookahead(fs) + DETECTOR_ALIGN
    }
}

/** One feature's switch state machine and amount. */
class Feature {
    var state = OFF
        private set
    var amount = 0.0
        private set
    private var warm = 1
    private var ramp = 1
    private var warmLeft = 0

    fun configure(warmFrames: Int, rampFrames: Int) {
        warm = warmFrames.coerceAtLeast(1)
        ramp = rampFrames.coerceAtLeast(1)
    }

    fun warmStep(): Double = 1.0 / warm

    fun rampStep(): Double = 1.0 / ramp

    fun off() {
        state = OFF
        amount = 0.0
    }

    fun setSwitch(on: Boolean) {
        if (on) {
            when (state) {
                OFF -> {
                    state = WARM
                    warmLeft = warm
                }
                RAMP_OUT -> state = RAMP_IN
            }
        } else {
            when (state) {
                WARM -> off()
                RAMP_IN, ON -> state = RAMP_OUT
            }
        }
    }

    /** Advances one frame and returns the amount for it. */
    fun step(): Double {
        when (state) {
            WARM -> if (--warmLeft <= 0) state = RAMP_IN
            RAMP_IN -> {
                amount += 1.0 / ramp
                if (amount >= 1 - 1e-9) {
                    amount = 1.0
                    state = ON
                }
            }
            RAMP_OUT -> {
                amount -= 1.0 / ramp
                if (amount <= 1e-9) {
                    amount = 0.0
                    state = OFF
                }
            }
        }
        return amount
    }

    companion object {
        const val OFF = 0
        const val WARM = 1
        const val RAMP_IN = 2
        const val ON = 3
        const val RAMP_OUT = 4
    }
}

/** How a stream was coded, for RenovaPure's gating: from the renderer hint; no hint (FFmpeg) = LOSSLESS. */
enum class CodecClass { LOSSY, LOSSLESS, UNKNOWN }

/** CrystalX+'s zero-latency chain on y1. */
interface WetChain {
    fun configure(fs: Int, ch: Int)

    fun reset()

    fun setVoicing(v: Voicing) {}

    /** RenovaPure's per-frame amounts gP for the next process() (null = 0): the exciter is 6 dB quieter, scaled by gP. */
    fun setRenovaPureAmounts(gP: FloatArray?) {}

    fun process(y1: FloatArray, out: FloatArray, frames: Int)

    /** A test placeholder: −6 dB. */
    class Dummy : WetChain {
        private var ch = 1

        override fun configure(fs: Int, ch: Int) {
            this.ch = ch
        }

        override fun reset() {}

        override fun process(y1: FloatArray, out: FloatArray, frames: Int) {
            for (i in 0 until frames * ch) out[i] = 0.5f * y1[i]
        }
    }
}

/** RenovaPure: from the undelayed x, the generated band aligned with D1, and the activity target d. */
interface Generator {
    fun configure(fs: Int, ch: Int)

    /** Whether the configured stream is one RenovaPure may process (44.1/48 kHz, 1–2 channels). */
    val supported: Boolean

    /** The detector's decision: 1 when a wall is decided, else 0 (the engine ramps it over 250 ms). */
    val activityTarget: Float

    fun newStream(codec: CodecClass, mediaKey: String?) {}

    /** The applied cutoff in Hz (0 = bypass) and the stream's codec class, for the dump. */
    val cutoffHz: Double get() = 0.0
    val codecClass: CodecClass get() = CodecClass.LOSSLESS

    /** Seek: histories zeroed, the decision kept. */
    fun flush()

    /** 100 ms after the switch went off: wet state zeroed so the next enable starts clean. */
    fun idleReset() {}

    /** Everything, including decisions. */
    fun reset()

    /** [x] from [xFrom] is the undelayed input, [y1] the D1-delayed dry; writes the aligned band into [gen]. */
    fun process(x: FloatArray, xFrom: Int, y1: FloatArray, gen: FloatArray, frames: Int)

    /** A test placeholder: gen = −6 dB of the dry, decided as [target]. */
    class Dummy(var target: Float = 1f) : Generator {
        private var ch = 1
        private var fs = 0
        override val supported: Boolean get() = DspEngine.renovaPureRate(fs)
        override val activityTarget: Float get() = target

        override fun configure(fs: Int, ch: Int) {
            this.fs = fs
            this.ch = ch
        }

        override fun flush() {}

        override fun reset() {}

        override fun process(x: FloatArray, xFrom: Int, y1: FloatArray, gen: FloatArray, frames: Int) {
            for (i in 0 until frames * ch) gen[i] = 0.5f * y1[i]
        }
    }
}

/** The shared limiter's gain for the D2-delayed samples, from the undelayed m. */
interface GainComputer {
    fun configure(fs: Int, ch: Int)

    fun reset()

    /** Consumes [frames] frames of undelayed [m]; writes the gain (≤ 1) of each output frame into [g]. */
    fun gains(m: FloatArray, frames: Int, g: FloatArray)

    /** A test placeholder: unity gain. */
    object Unity : GainComputer {
        override fun configure(fs: Int, ch: Int) {}

        override fun reset() {}

        override fun gains(m: FloatArray, frames: Int, g: FloatArray) {
            java.util.Arrays.fill(g, 0, frames, 1f)
        }
    }
}
