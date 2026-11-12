package org.seion.playback.viz

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import org.seion.playback.dsp.core.RealFft

/**
 * The analyzer behind the spectrum band, pure and unit-tested: a Hann FFT (2048 points, 4096 above
 * 88.2 kHz), [BANDS] log bands from 40 Hz to 16 kHz, a +3 dB/oct tilt pivoted at 1 kHz, −60…0 dBFS -> 0…1, a 15 ms
 * attack / 180 ms release, and peak caps that hold 500 ms then fall under constant gravity (a full-height fall in
 * 0.7 s). Buffers are preallocated; [analyze] and [advance] allocate nothing.
 */
class SpectrumModel(val sampleRate: Int) {
    val fftSize = if (sampleRate > 88_200) 4096 else 2048
    private val fft = RealFft(fftSize)
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2 * Math.PI * it / fftSize)).toFloat() }
    private val x = FloatArray(fftSize)
    private val re = FloatArray(fftSize / 2 + 1)
    private val im = FloatArray(fftSize / 2 + 1)

    /** Band edges in Hz (BANDS + 1, log-spaced). */
    val edges = DoubleArray(BANDS + 1) { LOW_HZ * (HIGH_HZ / LOW_HZ).pow(it.toDouble() / BANDS) }

    /** First and last FFT bin of each band (a band narrower than a bin takes the bin nearest its centre). */
    val firstBin = IntArray(BANDS)
    val lastBin = IntArray(BANDS)

    /** The tilt per bin, as a power factor. */
    private val tilt = FloatArray(fftSize / 2 + 1)

    /** The analysed level per band, 0..1 (what the bars move towards). */
    val target = FloatArray(BANDS)

    /** The bars after the ballistics, and their caps. */
    val levels = FloatArray(BANDS)
    val caps = FloatArray(BANDS)
    private val capHold = FloatArray(BANDS)
    private val capSpeed = FloatArray(BANDS)
    private var quietSec = 0f

    init {
        val binHz = sampleRate.toDouble() / fftSize
        for (b in 0 until BANDS) {
            var lo = kotlin.math.ceil(edges[b] / binHz).toInt()
            var hi = kotlin.math.ceil(edges[b + 1] / binHz).toInt() - 1
            if (hi < lo) {
                val centre = kotlin.math.round(kotlin.math.sqrt(edges[b] * edges[b + 1]) / binHz).toInt()
                lo = centre
                hi = centre
            }
            firstBin[b] = lo.coerceIn(1, fftSize / 2)
            lastBin[b] = hi.coerceIn(firstBin[b], fftSize / 2)
        }
        for (k in 1..fftSize / 2) tilt[k] = 10.0.pow(TILT_DB_PER_OCT * log2(k * binHz / PIVOT_HZ) / 10).toFloat()
    }

    /**
     * [samples] (at least [fftSize], the newest last) -> [target]. A band's level is its strongest bin, tilted, against
     * a full-scale sine on a bin centre (Hann peak N/4), so a −6 dBFS sine reads −6 dB in its band.
     */
    fun analyze(samples: FloatArray) {
        val off = samples.size - fftSize
        for (i in 0 until fftSize) x[i] = samples[off + i] * window[i]
        fft.forward(x, re, im)
        val ref = (fftSize / 4.0).pow(2)
        for (b in 0 until BANDS) {
            var peak = 0f
            for (k in firstBin[b]..lastBin[b]) {
                val p = (re[k] * re[k] + im[k] * im[k]) * tilt[k]
                if (p > peak) peak = p
            }
            val db = if (peak <= 0f) FLOOR_DB else 10 * log10(peak / ref)
            target[b] = ((db - FLOOR_DB) / -FLOOR_DB).toFloat().coerceIn(0f, 1f)
        }
    }

    /** Silence in (paused or stopped): the bars release and the caps fall. */
    fun silence() = target.fill(0f)

    /** Moves the bars and caps by [dtSec] of real time towards [target]. */
    fun advance(dtSec: Float) {
        val attack = 1f - exp(-dtSec / ATTACK_S)
        val release = 1f - exp(-dtSec / RELEASE_S)
        var energy = 0f
        for (b in 0 until BANDS) {
            val t = target[b]
            val v = levels[b]
            levels[b] = v + (t - v) * (if (t > v) attack else release)
            if (levels[b] >= caps[b]) {
                caps[b] = levels[b]
                capHold[b] = HOLD_S
                capSpeed[b] = 0f
            } else if (capHold[b] > 0f) {
                capHold[b] -= dtSec
            } else {
                capSpeed[b] += GRAVITY * dtSec
                caps[b] = (caps[b] - capSpeed[b] * dtSec).coerceAtLeast(levels[b])
            }
            energy += levels[b] + caps[b]
        }
        if (held) {
            held = false
            revealing = false
            reveal = 0f
        } else {
            if (!revealing && energy > 0f) revealing = true
            if (revealing) reveal = (reveal + dtSec / REVEAL_S).coerceAtMost(1f)
        }
        for (b in 0 until BANDS) {
            shown[b] = levels[b] * reveal
            shownCaps[b] = caps[b] * reveal
        }
        quietSec = if (energy < QUIET_ENERGY * 2 * BANDS) quietSec + dtSec else 0f
    }

    /**
     * What the bars and caps draw: the levels scaled by a 0 -> 1 reveal that starts with the first signal after a
     * reset, so the bars grow in over [REVEAL_S] instead of jumping to full height in one frame; the
     * close is the release and the caps' fall.
     */
    val shown = FloatArray(BANDS)
    val shownCaps = FloatArray(BANDS)
    private var reveal = 0f
    private var revealing = false
    private var held = false

    /** Everything below 1 % for 1.2 s: the band may close. */
    val fellQuiet: Boolean get() = quietSec >= QUIET_S

    /** Keeps the bars hidden (the reveal at its start) through the next [advance]: the band is still opening. */
    fun holdReveal() {
        held = true
    }

    fun reset() {
        target.fill(0f)
        levels.fill(0f)
        caps.fill(0f)
        capHold.fill(0f)
        capSpeed.fill(0f)
        shown.fill(0f)
        shownCaps.fill(0f)
        reveal = 0f
        revealing = false
        held = false
        quietSec = 0f
    }

    companion object {
        /** The number of bars (and analysed bands) over the full 40 Hz–16 kHz range. */
        const val BANDS = 14
        const val LOW_HZ = 40.0
        const val HIGH_HZ = 16_000.0
        const val PIVOT_HZ = 1_000.0
        const val TILT_DB_PER_OCT = 3.0
        const val FLOOR_DB = -60.0
        const val ATTACK_S = 0.015f
        const val RELEASE_S = 0.180f
        const val HOLD_S = 0.5f
        const val FALL_S = 0.7f

        /** Constant acceleration that drops a cap the full height in [FALL_S]: h = g t² / 2. */
        val GRAVITY = (2.0 / (FALL_S * FALL_S)).toFloat()
        const val QUIET_ENERGY = 0.01f
        const val QUIET_S = 1.2f

        /** The bars grow in over this when the signal first arrives (symmetric with the ~0.2–1 s fall-out). */
        const val REVEAL_S = 0.3f
    }
}
