package org.seion.playback.dsp

import org.seion.playback.dsp.core.RealFft

/**
 * RenovaPure's cutoff detector: finds the steep "wall" a lossy codec leaves in the spectrum. Runs only while
 * RenovaPure is on, only at 44.1/48 kHz.
 * - Welch on the undelayed stream: FFT 4096, periodic Hann, hop 1024, L and R alternating per hop into the same
 *   per-second slot (an estimate of (|L|² + |R|²)/2 at no extra FFT cost; anti-phase HF cannot fake a wall), one-sided
 *   density scaling. The window PSD is the last 1–3 slots; smoothing is a 150 Hz moving average in dB ('same').
 * - Edge per window: candidates from 7 kHz (lossy) or 13 kHz (lossless/unknown) to
 *   0.94·Nyquist; W = 25 dB (lossy) / 40 dB (lossless); the edge search is amortised over the following blocks.
 * - Decisions once per second: provisional (1 s and 2 s windows, W + 10 dB, fc = edge − 200 Hz), then standard from
 *   3 s (P10 of the window edges, walls in ≥ 50 % of non-silent windows); bypass at ≥ 19.5 kHz. Hysteresis: an fc
 *   change needs |Δ| ≥ 300 Hz three decisions in a row; wall → bypass needs three bypass decisions.
 * Medians and percentiles follow numpy (linear interpolation). Everything is preallocated.
 */
class CutoffDetector {
    private val fft = RealFft(N)
    private val window = FloatArray(N) { (0.5 - 0.5 * StrictMath.cos(2 * Math.PI * it / N)).toFloat() }
    private val windowPower = window.sumOf { it.toDouble() * it }
    private val ring = Array(2) { FloatArray(N) }
    private var ringPos = 0
    private var sinceHop = 0
    private var channelTurn = 0
    private val frame = FloatArray(N)
    private val re = FloatArray(BINS)
    private val im = FloatArray(BINS)
    private val slots = Array(3) { DoubleArray(BINS) }
    private val slotCount = IntArray(3)
    private var slotIndex = 0
    private var secondsDone = 0
    private var inSecond = 0

    private val psd = DoubleArray(BINS)
    private val level = DoubleArray(BINS)
    private val smooth = DoubleArray(BINS)
    private val scratch = DoubleArray(BINS)
    private val freq = DoubleArray(BINS)

    private var fs = 44_100
    private var channels = 2
    private var smoothBins = 13
    var codec = CodecClass.LOSSLESS
        private set

    // Amortised edge search state for the window being analysed.
    private var searching = false
    private var searchProvisional = false
    private var searchI = 0
    private var searchEnd = 0
    private var searchWall = 0.0
    private var searchRef = 0.0

    private val edges = DoubleArray(MAX_EDGES)
    private var edgeCount = 0
    private var nonSilentWindows = 0
    private var wallWindows = 0

    /** The applied cutoff in Hz, or 0 for bypass. */
    var fc = 0.0
        private set
    private var pendingFc = 0.0
    private var pendingCount = 0
    private var bypassCount = 0
    private var firstDecisionOfStream = true

    /** Test/debug: the edge of the last analysed window (0 = none, −1 = silence) and the last raw decision. */
    var lastEdge = 0.0
        private set
    var decisions = 0
        private set

    val supported: Boolean get() = DspEngine.renovaPureRate(fs)

    fun configure(fs: Int, ch: Int) {
        this.fs = fs
        channels = ch
        for (k in 0 until BINS) freq[k] = k * fs.toDouble() / N
        smoothBins = maxOf(1, (150.0 / freq[1]).toInt())
        resetAll()
    }

    /** A new stream: analysis restarts; the applied fc is kept until the new stream's first decision. */
    fun newStream(codec: CodecClass) {
        this.codec = codec
        restartAnalysis()
        firstDecisionOfStream = true
    }

    /** Applies a cached decision at once (cached fc, or 0 = bypass). */
    fun applyCached(cachedFc: Double) {
        fc = cachedFc
        firstDecisionOfStream = false
        pendingCount = 0
        bypassCount = 0
    }

    fun resetAll() {
        restartAnalysis()
        fc = 0.0
        pendingCount = 0
        bypassCount = 0
        firstDecisionOfStream = true
    }

    private fun restartAnalysis() {
        for (r in ring) r.fill(0f)
        ringPos = 0
        sinceHop = 0
        channelTurn = 0
        for (s in slots) s.fill(0.0)
        slotCount.fill(0)
        slotIndex = 0
        secondsDone = 0
        inSecond = 0
        searching = false
        edgeCount = 0
        nonSilentWindows = 0
        wallWindows = 0
        lastEdge = 0.0
        decisions = 0
    }

    /** True once the current stream reached a standard (3 s) decision: only then is it worth caching. */
    val hasStandardDecision: Boolean get() = secondsDone >= 3 && decisions >= 3

    /** Feeds [frames] interleaved frames of undelayed [x] from [from]. */
    fun process(x: FloatArray, from: Int, frames: Int) {
        if (!supported) return
        val ch = channels
        for (f in 0 until frames) {
            val base = from + f * ch
            ring[0][ringPos] = x[base]
            ring[1][ringPos] = if (ch == 2) x[base + 1] else x[base]
            ringPos = (ringPos + 1) and (N - 1)
            if (++sinceHop == HOP) {
                sinceHop = 0
                analyseHop()
            }
            if (++inSecond == fs) {
                inSecond = 0
                completeSecond()
            }
        }
        if (searching) searchStep()
    }

    private fun analyseHop() {
        val src = ring[channelTurn]
        channelTurn = if (channels == 2) 1 - channelTurn else 0
        for (i in 0 until N) frame[i] = src[(ringPos + i) and (N - 1)] * window[i]
        fft.forward(frame, re, im)
        val slot = slots[slotIndex]
        val scale = 1.0 / (fs * windowPower)
        for (k in 0 until BINS) {
            val p = (re[k].toDouble() * re[k] + im[k].toDouble() * im[k]) * scale
            slot[k] += if (k == 0 || k == BINS - 1) p else 2 * p
        }
        slotCount[slotIndex]++
    }

    private fun completeSecond() {
        secondsDone++
        // window PSD: the last min(3, seconds) slots
        val used = minOf(3, secondsDone)
        psd.fill(0.0)
        var count = 0
        for (s in 0 until used) {
            val idx = (slotIndex - s + 3) % 3
            val slot = slots[idx]
            for (k in 0 until BINS) psd[k] += slot[k]
            count += slotCount[idx]
        }
        slotIndex = (slotIndex + 1) % 3
        slots[slotIndex].fill(0.0)
        slotCount[slotIndex] = 0
        if (count == 0) return
        for (k in 0 until BINS) psd[k] /= count
        startWindow(provisional = secondsDone < 3)
    }

    /** Loads a window PSD (density) for analysis; exposed for the frozen-vector test. */
    internal fun loadWindow(p: DoubleArray, provisional: Boolean) {
        System.arraycopy(p, 0, psd, 0, BINS)
        startWindow(provisional)
    }

    private fun startWindow(provisional: Boolean) {
        for (k in 0 until BINS) level[k] = 10 * StrictMath.log10(psd[k] + 1e-20)
        // numpy convolve(L, ones(k)/k, mode="same"), zero padded
        val k = smoothBins
        val start = (k - 1) / 2
        for (i in 0 until BINS) {
            var s = 0.0
            val lo = i + start - k + 1
            for (m in lo..i + start) if (m in 0 until BINS) s += level[m]
            smooth[i] = s / k
        }
        searchRef = median(level, idxGe(1000.0), idxGe(4000.0))
        if (searchRef < -110) {
            windowDone(-1.0, provisional)
            return
        }
        val wall = if (codec == CodecClass.LOSSY) WALL_LOSSY else WALL_LOSSLESS
        searchWall = if (provisional) wall + PROVISIONAL_EXTRA else wall
        val floor = if (codec == CodecClass.LOSSY) FLOOR_LOSSY else FLOOR_LOSSLESS
        searchI = (floor / freq[1]).toInt()
        searchEnd = (0.94 * fs / 2 / freq[1]).toInt()
        searchProvisional = provisional
        searching = true
    }

    /** Runs the edge search to completion (tests, the render tool). */
    internal fun finishSearch() {
        while (searching) searchStep(Int.MAX_VALUE)
    }

    private fun searchStep(budget: Int = CANDIDATES_PER_BLOCK) {
        val nyq = fs / 2.0
        val df = freq[1]
        var n = 0
        while (searchI < searchEnd && n < budget) {
            val i = searchI
            n++
            searchI++
            val fc = freq[i]
            val below = median(smooth, idxGe(fc - 1200), idxGe(fc - 300))
            val aLo = idxGt(fc + 300)
            val aHi = idxGt(minOf(fc + 1500, nyq * 0.99))
            if (aHi - aLo < 4) continue
            val steep = smooth[maxOf(0, i - (300 / df).toInt())] - smooth[minOf(BINS - 1, i + (300 / df).toInt())]
            if (steep < 20) continue
            if (below - percentile(smooth, aLo, aHi, 90.0) < searchWall) continue
            if (median(smooth, aLo, aHi) - searchRef > -35) continue
            val tHi = idxGt(0.985 * nyq)
            if (tHi - aLo <= 0) continue
            if (below - percentile(smooth, aLo, tHi, 95.0) < searchWall - 5) continue
            // the first (lowest) wall: refine to the first bin 6 dB under the plateau, from fc − 1200 Hz upward
            val plateau = median(smooth, idxGe(fc - 2000), idxGe(fc - 400))
            var j = idxGe(fc - 1200)
            while (j < BINS - 1 && smooth[j] > plateau - 6) j++
            searching = false
            windowDone(freq[j], searchProvisional)
            return
        }
        if (searchI >= searchEnd) {
            searching = false
            windowDone(0.0, searchProvisional)
        }
    }

    /** One window's result ([edge] Hz, 0 = no wall, −1 = silence) → a decision. */
    private fun windowDone(edge: Double, provisional: Boolean) {
        lastEdge = edge
        decisions++
        val candidate: Double
        if (provisional) {
            candidate = if (edge > 0) edge - 200 else 0.0
            if (candidate <= 0 && !firstDecisionOfStream) return // before 3 s only a wall can engage
        } else {
            if (edge >= 0) nonSilentWindows++
            if (edge > 0) {
                wallWindows++
                if (edgeCount < MAX_EDGES) edges[edgeCount++] = edge
            }
            candidate = if (nonSilentWindows > 0 && wallWindows * 2 >= nonSilentWindows && edgeCount > 0) {
                percentile(edges, 0, edgeCount, 10.0)
            } else {
                0.0
            }
        }
        decide(if (candidate >= BYPASS_HZ) 0.0 else candidate)
    }

    private fun decide(candidate: Double) {
        if (firstDecisionOfStream) {
            // a new stream's first decision replaces the previous stream's state at once (then the 250 ms ramp)
            firstDecisionOfStream = false
            fc = candidate
            pendingCount = 0
            bypassCount = 0
            return
        }
        if (candidate <= 0) {
            pendingCount = 0
            if (fc > 0 && ++bypassCount >= 3) {
                fc = 0.0
                bypassCount = 0
            }
            return
        }
        bypassCount = 0
        if (fc <= 0) {
            fc = candidate
            pendingCount = 0
            return
        }
        if (kotlin.math.abs(candidate - fc) >= HYSTERESIS_HZ) {
            if (pendingCount > 0 && kotlin.math.abs(candidate - pendingFc) < HYSTERESIS_HZ) pendingCount++ else pendingCount = 1
            pendingFc = candidate
            if (pendingCount >= 3) {
                fc = candidate
                pendingCount = 0
            }
        } else {
            pendingCount = 0
        }
    }

    // ---- numpy-compatible index ranges, medians and percentiles (sorted copies in the scratch array)

    /** First bin with f ≥ [hz]. */
    private fun idxGe(hz: Double): Int {
        var k = kotlin.math.ceil(hz / freq[1]).toInt().coerceIn(0, BINS)
        while (k > 0 && freq[k - 1] >= hz) k--
        while (k < BINS && freq[k] < hz) k++
        return k
    }

    /** First bin with f > [hz]. */
    private fun idxGt(hz: Double): Int {
        var k = idxGe(hz)
        while (k < BINS && freq[k] <= hz) k++
        return k
    }

    private fun median(a: DoubleArray, from: Int, to: Int): Double = percentile(a, from, to, 50.0)

    /** numpy-compatible percentile (linear interpolation) of a[from, to); internal for the frozen-vector test. */
    internal fun percentile(a: DoubleArray, from: Int, to: Int, q: Double): Double {
        val n = to - from
        if (n <= 0) return Double.NaN
        System.arraycopy(a, from, scratch, 0, n)
        val pos = q / 100 * (n - 1)
        val lo = pos.toInt()
        val frac = pos - lo
        val vLo = select(n, lo)
        if (lo + 1 >= n || frac == 0.0) return vLo
        // after select(), everything right of lo is ≥ scratch[lo]: the next order statistic is their minimum
        var vHi = Double.MAX_VALUE
        for (i in lo + 1 until n) if (scratch[i] < vHi) vHi = scratch[i]
        return vLo + frac * (vHi - vLo)
    }

    /**
     * The [k]-th smallest of scratch[0, n), in place (Hoare quickselect, middle pivot). No allocation: Arrays.sort on
     * a double[] may allocate a merge buffer for presorted runs.
     */
    private fun select(n: Int, k: Int): Double {
        var left = 0
        var right = n - 1
        while (left < right) {
            val pivot = scratch[(left + right) ushr 1]
            var i = left
            var j = right
            while (i <= j) {
                while (scratch[i] < pivot) i++
                while (scratch[j] > pivot) j--
                if (i <= j) {
                    val t = scratch[i]
                    scratch[i] = scratch[j]
                    scratch[j] = t
                    i++
                    j--
                }
            }
            if (k <= j) {
                right = j
            } else if (k >= i) {
                left = i
            } else {
                return scratch[k]
            }
        }
        return scratch[k]
    }

    companion object {
        const val N = 4096
        const val HOP = 1024
        const val BINS = N / 2 + 1
        const val WALL_LOSSY = 25.0
        const val WALL_LOSSLESS = 40.0
        const val PROVISIONAL_EXTRA = 10.0
        const val FLOOR_LOSSY = 7000.0
        const val FLOOR_LOSSLESS = 13_000.0
        const val BYPASS_HZ = 19_500.0
        const val HYSTERESIS_HZ = 300.0

        // The cheap steepness test runs first, so most candidates cost a few loads: 256 per block ends a window's
        // search within ~30 ms, so a provisional decision lands within about 1.1 s.
        const val CANDIDATES_PER_BLOCK = 256
        private const val MAX_EDGES = 2048
    }
}
