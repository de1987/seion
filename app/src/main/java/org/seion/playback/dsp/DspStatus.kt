package org.seion.playback.dsp

/**
 * State out of the playback thread: volatile primitives written by the sink/engine, read anywhere
 * (the dump, debug session extras). Tear-tolerant by design.
 */
class DspStatus {
    @Volatile var sampleRate = 0

    @Volatile var channels = 0

    @Volatile var latencyFrames = 0

    /** True while the current stream runs through the engine (false: delegated to the inner sink unchanged). */
    @Volatile var processing = false

    /** Debug builds: the largest output true peak of the last 30 s window (dBTP) and the idle self-check. */
    @Volatile var maxTruePeakDb = -200.0

    @Volatile var idleSelfCheckOk = true

    /** RenovaPure: the amount gP now (0 = not generating), the applied cutoff (Hz, 0 = none) and the codec class. */
    @Volatile var renovaPureAmount = 0f

    @Volatile var renovaPureCutoff = 0.0

    @Volatile var codec = CodecClass.LOSSLESS

    /** ReplayGain: the gain the engine applies for the current stream (1 = unchanged) and whether it needs the limiter. */
    @Volatile var replayGainTarget = 1f

    @Volatile var replayGainLimiter = false

    /**
     * Debug builds only: the last [TAP_FRAMES] frames of the sink's float output, channel 0, so the
     * dump can check level and spectrum per format. Written by the playback thread into a preallocated ring.
     */
    @Volatile var tapEnabled = false
    private val tap = FloatArray(TAP_FRAMES)
    private var tapPos = 0

    /** Frames still to tap before the ring freezes (−1: never), so a dump can show a stream boundary. */
    private var freezeIn = -1

    /** Debug: freeze the tap [frames] frames from now (set at a gapless boundary: half the window on each side). */
    fun armTapFreeze(frames: Int) {
        freezeIn = frames
    }

    /** Debug: let the tap run again. */
    fun releaseTap() {
        freezeIn = -1
    }

    fun tap(buf: FloatArray, frames: Int, channels: Int) {
        if (freezeIn == 0) return
        var p = tapPos
        val n = if (freezeIn > 0) minOf(frames, freezeIn) else frames
        if (freezeIn > 0) freezeIn -= n
        var i = 0
        while (i < n) {
            tap[p] = buf[i * channels]
            if (++p == TAP_FRAMES) p = 0
            i++
        }
        tapPos = p
    }

    /** A copy of the tap, oldest first (any thread; racy by design). */
    fun tapSnapshot(): FloatArray {
        val p = tapPos
        return FloatArray(TAP_FRAMES) { tap[(p + it) % TAP_FRAMES] }
    }

    companion object {
        const val TAP_FRAMES = 16_384
    }
}

/** The process-wide DSP objects, held by `AppContainer` and shared by the service and the audio sink. */
class DspHub(cpuClock: () -> Long) {
    val stats = DspStats(cpuClock)
    val status = DspStatus()
    val control = DspControl()

    /** The spectrum band's lock-free tap. */
    val spectrum = org.seion.playback.viz.SpectrumTap()
}

/** The text `adb shell dumpsys activity service …PlaybackService` prints, pure for the tests. */
object DspReport {
    fun lines(stats: DspStats, status: DspStatus, params: DspParams? = null): List<String> {
        val period = if (status.sampleRate > 0) DspEngine.BLOCK * 1e6 / status.sampleRate else 0.0
        return listOf(
            "Seion DSP",
            "engine load: %.2f %% of one core (thread CPU %.3f s over %.1f s of audio)".format(
                java.util.Locale.ROOT,
                stats.loadPercent(),
                stats.cpuNanos / 1e9,
                stats.audioSeconds
            ),
            "block wall time (256 frames, period %.0f us): p50 %.0f us, p99 %.0f us, max %.0f us, blocks %d".format(
                java.util.Locale.ROOT,
                period,
                stats.blockQuantileMicros(0.5),
                stats.blockQuantileMicros(0.99),
                stats.maxBlockNanos / 1000.0,
                stats.blocks
            ),
            "stream: ${status.sampleRate} Hz, ${status.channels} ch, latency ${status.latencyFrames} frames, " +
                (if (status.processing) "processed" else "delegated/idle"),
            "delegated streams: ${stats.delegatedStreams}, non-finite resets: ${stats.nonFiniteResets}",
            "Seion RenovaPure: " + (if (status.renovaPureAmount > 0f) "active" else "inactive") +
                ", cutoff ${status.renovaPureCutoff.toInt()} Hz, codec ${status.codec}",
            "ReplayGain: %+.2f dB applied%s".format(
                java.util.Locale.ROOT,
                20 * kotlin.math.log10(status.replayGainTarget.toDouble().coerceAtLeast(1e-10)),
                if (status.replayGainLimiter) ", limiter in" else ""
            )
        ) +
            (
                if (status.tapEnabled) {
                    listOf(
                        "debug: output true peak (last 30 s) %.2f dBTP; idle self-check %s".format(
                            java.util.Locale.ROOT,
                            status.maxTruePeakDb,
                            if (status.idleSelfCheckOk) "ok" else "FAILED"
                        )
                    )
                } else {
                    emptyList()
                }
                ) +
            switchLines(params) +
            if (status.tapEnabled && status.sampleRate > 0) listOf(tapLine(status.tapSnapshot(), status.sampleRate)) else emptyList()
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private fun switchLines(p: DspParams?): List<String> = if (p ==
        null
    ) {
        emptyList()
    } else {
        listOf(
            "switches: CrystalX+ ${onOff(p.crystalX)}, Seion RenovaPure ${onOff(p.renovaPure)}; voicing ${p.voicing}; " +
                "ReplayGain ${p.replayGain}, pre-amp ${p.preampDb.toInt()} dB"
        )
    }

    /**
     * Level/spectrum of the output tap: flat-top window, amplitude spectrum normalised so a full-scale sine
     * reads 0 dBFS; the peak's level and frequency, and the largest other component (outside ±10 bins).
     */
    fun tapLine(x: FloatArray, fs: Int): String {
        val a = analyse(x, fs)
        return "tap: rms %.2f dBFS, peak %.2f dBFS at %.1f Hz (bin %.1f Hz), largest other component %.1f dBFS at %.0f Hz".format(
            java.util.Locale.ROOT,
            a.rmsDb,
            a.peakDb,
            a.peakHz,
            fs.toDouble() / x.size,
            a.spurDb,
            a.spurHz
        )
    }

    data class TapAnalysis(val rmsDb: Double, val peakDb: Double, val peakHz: Double, val spurDb: Double, val spurHz: Double)

    fun analyse(x: FloatArray, fs: Int): TapAnalysis {
        val n = x.size
        var sumSq = 0.0
        for (v in x) sumSq += v.toDouble() * v
        val rms = kotlin.math.sqrt(sumSq / n)
        // Flat-top window (ISO 18431-2): < 0.01 dB scalloping, sidelobes about -93 dB.
        val w = DoubleArray(n) { i ->
            val t = 2 * Math.PI * i / n
            0.21557895 - 0.41663158 * kotlin.math.cos(t) + 0.277263158 * kotlin.math.cos(2 * t) -
                0.083578947 * kotlin.math.cos(3 * t) + 0.006947368 * kotlin.math.cos(4 * t)
        }
        val wSum = w.sum()
        val re = DoubleArray(n) { x[it] * w[it] }
        val im = DoubleArray(n)
        fft(re, im)
        val half = n / 2
        val amp = DoubleArray(half) { k -> 2 * kotlin.math.hypot(re[k], im[k]) / wSum }
        var peak = 1
        for (k in 1 until half) if (amp[k] > amp[peak]) peak = k
        var spur = -1
        for (k in 1 until half) if (kotlin.math.abs(k - peak) > 10 && (spur < 0 || amp[k] > amp[spur])) spur = k
        fun db(v: Double) = 20 * kotlin.math.log10(v.coerceAtLeast(1e-12))
        return TapAnalysis(db(rms), db(amp[peak]), peak.toDouble() * fs / n, db(amp[spur]), spur.toDouble() * fs / n)
    }

    /** In-place radix-2 FFT (debug dump only, allocation is fine here). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val cr = kotlin.math.cos(ang * k)
                    val ci = kotlin.math.sin(ang * k)
                    val tRe = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val tIm = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - tRe
                    im[i + k + len / 2] = im[i + k] - tIm
                    re[i + k] += tRe
                    im[i + k] += tIm
                }
            }
            len = len shl 1
        }
    }
}
