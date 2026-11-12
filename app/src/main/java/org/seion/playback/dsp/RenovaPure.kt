package org.seion.playback.dsp

import org.seion.playback.dsp.core.Biquad
import org.seion.playback.dsp.core.FastMath
import org.seion.playback.dsp.core.Filters
import org.seion.playback.dsp.core.PhasorTable
import org.seion.playback.dsp.core.Rng
import org.seion.playback.dsp.core.Wola

/**
 * RenovaPure: the cutoff detector decides whether and where a codec cut the treble; the bandwidth extension
 * generates only the missing band on a 512/128 √Hann WOLA, phase-coherently translated (offsets
 * in multiples of 4 bins), shaped by a per-frame regression envelope with an onset-adaptive release, mixed tonal/noise,
 * filled only up to the envelope, flat to 20 kHz with a 20–21 kHz taper, and ducked −20 dB around onsets. The output
 * band is aligned with the engine's dry delay D1 (`gen[n] = duck[n − 511]·G[n − 511]`). Decisions are cached per media
 * id for the session, in a preallocated 512-entry LRU.
 */
class RenovaPure : Generator {
    private val detector = CutoffDetector()
    private var fs = 0
    private var ch = 2
    private var mediaKey: String? = null
    private var cachedForStream = false
    private var cachedFc = 0.0

    private val cacheKeys = arrayOfNulls<String>(CACHE_SIZE)
    private val cacheFc = DoubleArray(CACHE_SIZE)
    private val cacheAge = LongArray(CACHE_SIZE)
    private var clock = 0L

    // --- bandwidth extension state (preallocated for N 512)
    private val wola = Wola(N, HOP, 2)
    private val rng = Rng()
    private var gridFc = 0.0
    private var kc = 0
    private var kmax = 0
    private var w = 0
    private var ks = 0
    private var kx = 0
    private var regLo = 0
    private var regHi = 0
    private var regSx = 0.0
    private var regSxx = 0.0
    private val log2f = DoubleArray(BINS)
    private val taper = DoubleArray(BINS)
    private val env = Array(2) { DoubleArray(BINS) }
    private val envFresh = BooleanArray(2)
    private val sm = DoubleArray(BINS)
    private val box = DoubleArray(BINS)
    private val mRe = DoubleArray(BINS)
    private val mIm = DoubleArray(BINS)
    private val dRe = FloatArray(BINS)
    private val dIm = FloatArray(BINS)
    private val mono = FloatArray(2)

    // --- onset guard
    private val onsetBp = Array(4) { Biquad(1) }
    private val onsetCoeff = DoubleArray(20)
    private var onsetSections = 0
    private var onsetFc = -1.0
    private var envOnset = 0.0
    private var att = 0.0
    private var rel = 0.0
    private val envHist = DoubleArray(ENV_HIST)
    private var refractory = 0
    private val onsetTimes = LongArray(8) { Long.MIN_VALUE / 2 }
    private var onsetPos = 0
    private val duck = FloatArray(DUCK_RING)
    private var time = 0L

    /** Test hooks: force the cutoff, the noise share β, or the source width W (for the negative-control test). */
    internal var forceFc: Double? = null
    internal var forceBeta: Double? = null
    internal var forceW: Int? = null

    /** Test hook (sensitivity test): the onset duck can be disabled. */
    internal var duckEnabled = true

    /** Test hook (flutter test): 0 = the onset-adaptive hybrid, 1 = plain 5 ms, 2 = plain 20 ms. */
    internal var releaseMode = 0

    /** Test hook: use the clamped slope for L_fc too. */
    internal var clampedLevel = false

    /** Test views of the last analysed frame. */
    internal var lastSlope = 0.0
    internal var lastIntercept = 0.0
    internal var lastLevelFc = 0.0
    internal var lastBeta = 0.0
    internal val lastTarget = DoubleArray(BINS)
    internal val gridForTest: IntArray get() = intArrayOf(kc, kmax, w, kx)

    /** Test hook: analyses one frame [re]/[im] (replaced by Δ) as the first frame after a flush. */
    internal fun frameForTest(fs: Int, fc: Double, seed: Int, re: FloatArray, im: FloatArray) {
        configure(fs, 1)
        buildGrid(fc)
        rng.seed(seed)
        frame(0, re, im)
    }

    /** Test view: onsets detected (content times). */
    internal var onsetCount = 0
        private set

    override val supported: Boolean get() = DspEngine.renovaPureRate(fs) && ch in 1..2

    override val activityTarget: Float get() = if (supported && currentFc() > 0) 1f else 0f

    /** The applied cutoff (Hz, 0 = bypass), for the dump and the tests. */
    val cutoff: Double get() = currentFc()
    val codec: CodecClass get() = detector.codec
    override val cutoffHz: Double get() = currentFc()
    override val codecClass: CodecClass get() = detector.codec
    internal val detectorForTest: CutoffDetector get() = detector

    private fun currentFc(): Double {
        val forced = forceFc
        return if (forced != null) forced else detector.fc // no elvis: it would box
    }

    override fun configure(fs: Int, ch: Int) {
        this.fs = fs
        this.ch = ch
        detector.configure(fs, ch)
        att = StrictMath.exp(-1.0 / (fs * 0.0005))
        rel = StrictMath.exp(-1.0 / (fs * 0.020))
        rng.seed(SEED)
        gridFc = 0.0
        onsetFc = -1.0
        flush()
    }

    override fun newStream(codec: CodecClass, mediaKey: String?) {
        this.mediaKey = mediaKey
        cachedForStream = false
        detector.newStream(codec)
        val hit = lookup(mediaKey)
        if (hit >= 0) detector.applyCached(cacheFc[hit])
    }

    override fun flush() {
        rng.seed(SEED) // determinism: the same input after a flush gives the same output
        wola.reset()
        envFresh.fill(true)
        for (b in onsetBp) b.reset()
        envOnset = 0.0
        envHist.fill(0.0)
        refractory = 0
        onsetTimes.fill(Long.MIN_VALUE / 2)
        duck.fill(1f)
        time = 0
    }

    override fun idleReset() = flush()

    override fun reset() {
        detector.resetAll()
        flush()
    }

    override fun process(x: FloatArray, xFrom: Int, y1: FloatArray, gen: FloatArray, frames: Int) {
        detector.process(x, xFrom, frames)
        if (detector.hasStandardDecision && (!cachedForStream || cachedFc != detector.fc)) {
            cachedForStream = true
            cachedFc = detector.fc
            store(mediaKey, detector.fc)
        }
        val fc = currentFc()
        if (fc > 0 && fc != gridFc) buildGrid(fc)
        if (fc > 0 && fc != onsetFc) buildOnset(fc)
        val c = ch
        for (f in 0 until frames) {
            val base = xFrom + f * c
            // onset guard on the undelayed mid
            val m = if (c == 2) (x[base] + x[base + 1]) * 0.5 else x[base].toDouble()
            onsetSample(m)
            mono[0] = x[base]
            mono[1] = if (c == 2) x[base + 1] else x[base]
            wola.push(mono, 0, c, if (fc > 0) processor else null)
            // the generated band of content time n − 511, ducked around onsets
            val slot = ((time - (N - 1)) and (DUCK_RING - 1).toLong()).toInt()
            val g = if (time >= N - 1 && duckEnabled) duck[slot] else 1f
            duck[slot] = 1f
            for (k in 0 until c) gen[f * c + k] = if (fc > 0) g * wola.output(k) else 0f
            time++
        }
        for (b in onsetBp) b.flushDenormals()
    }

    // ------------------------------------------------------------------ onset guard

    private fun buildOnset(fc: Double) {
        onsetFc = fc
        val lo: Double
        val hi: Double
        if (fc < 9000) {
            lo = maxOf(4000.0, fc / 2)
            hi = fc
        } else {
            lo = 8000.0
            hi = minOf(16_000.0, fc, 0.45 * fs)
        }
        onsetSections = Filters.butterworth(4, Filters.Kind.BAND, lo, hi, fs, onsetCoeff)
        for (s in 0 until onsetSections) onsetBp[s].set(onsetCoeff, s * 5)
    }

    private fun onsetSample(m: Double) {
        var b = m
        for (s in 0 until onsetSections) b = onsetBp[s].process(b, 0)
        val a = kotlin.math.abs(b)
        envOnset = if (a > envOnset) att * envOnset + (1 - att) * a else rel * envOnset + (1 - rel) * a
        val d10 = (0.010 * fs).toInt()
        val idx = (time % ENV_HIST).toInt()
        val ref = if (time >= d10) envHist[((time - d10) % ENV_HIST).toInt()] else envHist[0]
        envHist[idx] = envOnset
        if (refractory > 0) refractory--
        if (refractory == 0 && envOnset > 4 * ref && envOnset > ONSET_FLOOR && time >= d10) {
            refractory = (0.020 * fs).toInt()
            onsetCount++
            onsetTimes[onsetPos] = time
            onsetPos = (onsetPos + 1) % onsetTimes.size
            // −20 dB from t0 − 6 ms to t0 + 0.5 ms, 1 ms linear ramps, as min() with the schedule
            val pre = (0.006 * fs).toInt()
            val post = (0.0005 * fs).toInt()
            val ramp = maxOf(1, (0.001 * fs).toInt())
            val s0 = time - pre
            val s1 = time + post
            for (t in s0 - ramp until s1 + ramp) {
                val gain =
                    when {
                        t < s0 -> 1f - (1f - DUCK_GAIN) * ((t - (s0 - ramp) + 1).toFloat() / (ramp + 1))
                        t < s1 -> DUCK_GAIN
                        else -> DUCK_GAIN + (1f - DUCK_GAIN) * ((t - s1 + 1).toFloat() / (ramp + 1))
                    }
                if (t <= time - (N - 1)) continue // already played (never the case: 7 ms < 511 samples)
                val slot = (t and (DUCK_RING - 1).toLong()).toInt()
                if (gain < duck[slot]) duck[slot] = gain
            }
        }
    }

    /** The fast-release rule (causal): a frame starting at s qualifies iff a detected onset o ∈ [s − 40 ms, s + hop]. */
    private fun fastRelease(frameStart: Long): Boolean {
        val lo = frameStart - (0.040 * fs).toLong()
        val hi = frameStart + HOP
        for (o in onsetTimes) if (o in lo..hi) return true
        return false
    }

    // ------------------------------------------------------------------ bandwidth extension

    private fun buildGrid(fc: Double) {
        gridFc = fc
        val df = fs.toDouble() / N
        kc = Math.round(fc / df).toInt()
        kmax = kotlin.math.floor(minOf(21_000.0, fs / 2.0 - 1050.0) / df).toInt()
        var width = minOf(kmax - kc, kc - Math.round(fc / 2 / df).toInt())
        width = maxOf(4, width / 4 * 4)
        val forcedW = forceW
        w = if (forcedW != null) forcedW else width
        ks = kc - w
        kx = kotlin.math.floor(1500.0 / df).toInt()
        val kt = kotlin.math.floor(1000.0 / df).toInt()
        for (k in 0 until BINS) {
            log2f[k] = if (k == 0) 0.0 else StrictMath.log(k * df) / StrictMath.log(2.0)
            taper[k] = 1.0
        }
        for (i in 0 until kt) {
            val k = kmax - kt + 1 + i
            if (k in 0 until BINS) taper[k] = 0.5 + 0.5 * StrictMath.cos(Math.PI * i / kt)
        }
        // regression bins: f ∈ [fc/2, fc·2^(−1/8)]
        regLo = kotlin.math.ceil(fc / 2 / df).toInt()
        regHi = kotlin.math.floor(fc * StrictMath.pow(2.0, -1.0 / 8) / df).toInt()
        regSx = 0.0
        regSxx = 0.0
        for (k in regLo..regHi) {
            regSx += log2f[k]
            regSxx += log2f[k] * log2f[k]
        }
        envFresh.fill(true)
    }

    private val processor = Wola.FrameProcessor { c, re, im -> frame(c, re, im) }

    private fun frame(c: Int, re: FloatArray, im: FloatArray) {
        val fc = gridFc
        if (fc <= 0 || kmax <= kc + 2) {
            java.util.Arrays.fill(re, 0f)
            java.util.Arrays.fill(im, 0f)
            return
        }
        val log2fc = StrictMath.log(fc) / StrictMath.log(2.0)
        // 3. regression of Λ = 10·log10(|X|² + 1e-14) against log2 f over the fit bins
        var sy = 0.0
        var sxy = 0.0
        val n = (regHi - regLo + 1).toDouble()
        for (k in regLo..regHi) {
            val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k] + 1e-14
            val l = FastMath.db10(p)
            sy += l
            sxy += l * log2f[k]
        }
        val slope = (n * sxy - regSx * sy) / (n * regSxx - regSx * regSx)
        val intercept = (sy - slope * regSx) / n
        val sc = slope.coerceIn(-18.0, 0.0)
        val levelFc = (if (clampedLevel) sc else slope) * log2fc + intercept
        lastSlope = slope
        lastIntercept = intercept
        lastLevelFc = levelFc
        // 5. release: frame start in content time
        val frameStart = wola.clock - N
        val fast = when (releaseMode) {
            1 -> true
            2 -> false
            else -> fastRelease(frameStart)
        }
        val rho = if (fast) RHO_FAST[rateIndex()] else RHO_SLOW[rateIndex()]
        val e = env[c]
        val fresh = envFresh[c]
        // 6. source flattening over the W-bin source band [ks, kc)
        for (j in 0 until w) {
            val k = ks + j
            val mag = kotlin.math.sqrt(re[k].toDouble() * re[k] + im[k].toDouble() * im[k]) + 1e-9
            sm[j] = mag
        }
        val kb = minOf(9, w - (1 - w % 2))
        val half = (kb - 1) / 2
        var logSum = 0.0
        var powSum = 0.0
        for (j in 0 until w) {
            var s = 0.0
            for (q in j - half..j - half + kb - 1) if (q in 0 until w) s += sm[q] * sm[q]
            box[j] = kotlin.math.sqrt(s / kb) + 1e-9
            val p = sm[j] * sm[j]
            logSum += FastMath.log2(p)
            powSum += p
        }
        // 7. tonal/noise mix: β from the spectral flatness of the source band
        val sfm = FastMath.exp2(logSum / w) / (powSum / w)
        val forcedBeta = forceBeta
        val beta = if (forcedBeta != null) forcedBeta else (0.25 + 0.6 * sfm).coerceIn(0.25, 0.85)
        lastBeta = beta
        val a = kotlin.math.sqrt(1 - beta)
        val b = kotlin.math.sqrt(beta)
        for (j in 0 until w) {
            val k = ks + j
            val fr = re[k] / box[j]
            val fi = im[k] / box[j]
            // |F| from the true |S| (the 1e-9 only guards the envelope): digital silence then generates exactly 0
            val fm = (sm[j] - 1e-9) / box[j]
            val th = rng.next() and (PhasorTable.SIZE - 1)
            mRe[j] = a * fr + b * fm * PhasorTable.cos[th]
            mIm[j] = a * fi + b * fm * PhasorTable.sin[th]
        }
        java.util.Arrays.fill(dRe, 0f)
        java.util.Arrays.fill(dIm, 0f)
        // 4./5./8. target, release and fill-to-envelope over [kc, kmax]
        for (k in kc..kmax) {
            val tau = levelFc + (sc + EXTRA_ROLLOFF) * (log2f[k] - log2fc) - MARGIN_DB
            val idx = k - kc
            e[idx] = if (fresh || tau > e[idx]) tau else rho * e[idx] + (1 - rho) * tau
            val t = FastMath.fromDb20(e[idx]) * taper[k]
            lastTarget[idx] = t
            val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            val fill = kotlin.math.sqrt(maxOf(t * t - p, 0.0))
            val j = (k - kc) % w
            dRe[k] = (mRe[j] * fill).toFloat()
            dIm[k] = (mIm[j] * fill).toFloat()
        }
        envFresh[c] = false
        // 9. crossover below fc: the regression line (no release, no extra roll-off), ramped in
        for (i in 0 until kx) {
            val k = kc - kx + i
            val s = ks - kx + i
            if (k <= 0 || s <= 0) continue
            val tx = FastMath.fromDb20(levelFc + sc * (log2f[k] - log2fc) - MARGIN_DB)
            val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            val wv = 0.5 - 0.5 * StrictMath.cos(Math.PI * ((i + 0.5) / (2.0 * kx / 3)).coerceIn(0.0, 1.0))
            val sMag = kotlin.math.sqrt(re[s].toDouble() * re[s] + im[s].toDouble() * im[s]) + 1e-9
            val amp = kotlin.math.sqrt(maxOf(tx * tx - p, 0.0)) * wv / sMag
            dRe[k] = (re[s] * amp).toFloat()
            dIm[k] = (im[s] * amp).toFloat()
        }
        // 10. synthesise the generated band only
        System.arraycopy(dRe, 0, re, 0, BINS)
        System.arraycopy(dIm, 0, im, 0, BINS)
    }

    private fun rateIndex() = if (fs == 48_000) 1 else 0

    private fun lookup(key: String?): Int {
        if (key == null) return -1
        for (i in 0 until CACHE_SIZE) {
            if (key == cacheKeys[i]) {
                cacheAge[i] = ++clock
                return i
            }
        }
        return -1
    }

    private fun store(key: String?, fc: Double) {
        if (key == null) return
        var slot = -1
        var oldest = Long.MAX_VALUE
        for (i in 0 until CACHE_SIZE) {
            if (key == cacheKeys[i] || cacheKeys[i] == null) {
                slot = i
                break
            }
            if (cacheAge[i] < oldest) {
                oldest = cacheAge[i]
                slot = i
            }
        }
        cacheKeys[slot] = key
        cacheFc[slot] = fc
        cacheAge[slot] = ++clock
    }

    companion object {
        const val CACHE_SIZE = 512
        const val N = 512
        const val HOP = 128
        const val BINS = N / 2 + 1
        const val MARGIN_DB = 1.5

        const val EXTRA_ROLLOFF = -3.0
        const val DUCK_GAIN = 0.1f // −20 dB
        val ONSET_FLOOR = StrictMath.pow(10.0, -70.0 / 20)
        private const val SEED = 0x5EED1234
        private const val ENV_HIST = 1024
        private const val DUCK_RING = 2048

        /** ρ = exp(−hop/(fs·τ)) for τ = 5 ms and 20 ms at 44.1 / 48 kHz. */
        private val RHO_FAST = doubleArrayOf(StrictMath.exp(-HOP / (44_100 * 0.005)), StrictMath.exp(-HOP / (48_000 * 0.005)))
        private val RHO_SLOW = doubleArrayOf(StrictMath.exp(-HOP / (44_100 * 0.020)), StrictMath.exp(-HOP / (48_000 * 0.020)))
    }
}

/** The codec class from the renderer's input MIME type: lossy, lossless, or unknown (treated as lossless). */
object CodecHint {
    fun classify(mime: String?): CodecClass = when (mime) {
        "audio/mpeg", "audio/mpeg-L1", "audio/mpeg-L2", "audio/mp4a-latm", "audio/vorbis", "audio/opus" -> CodecClass.LOSSY
        "audio/flac", "audio/raw", "audio/alac" -> CodecClass.LOSSLESS
        else -> CodecClass.UNKNOWN
    }
}
