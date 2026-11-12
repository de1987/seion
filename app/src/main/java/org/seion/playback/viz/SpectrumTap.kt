package org.seion.playback.viz

import java.util.concurrent.atomic.AtomicLong

/**
 * What the spectrum band reads: the audio thread copies what is heard (after the DSP engine) into a
 * preallocated mono ring, lock-free and without allocation, only while [enabled]. Each written buffer also records one
 * (ring frame, presentation time) pair, and the sink publishes the audible position, so the reader takes the window
 * that is playing now. A torn read is harmless (a frame of slightly stale samples). No `Visualizer`, no RECORD_AUDIO.
 */
class SpectrumTap(capacity: Int = CAPACITY) {
    init {
        require(capacity > 0 && capacity and (capacity - 1) == 0) { "capacity must be a power of two" }
    }

    /** Setting on, now playing expanded and visible, playing (or falling out), motion allowed. One read per buffer. */
    @Volatile var enabled = false

    @Volatile var sampleRate = 0

    private val ring = FloatArray(capacity)
    private val mask = capacity - 1L
    private val written = AtomicLong(0)
    private val pairFrame = LongArray(PAIRS)
    private val pairPts = LongArray(PAIRS)
    private val pairs = AtomicLong(0)

    /** The sink's current position (its presentation timebase) and when it was read. */
    @Volatile private var audibleUs = Long.MIN_VALUE

    @Volatile private var audibleAtNs = 0L

    val writtenFrames: Long get() = written.get()

    /** Audio thread: [frames] interleaved frames of [x] presented at [ptsUs]. Downmixed to mono. */
    fun write(x: FloatArray, frames: Int, channels: Int, ptsUs: Long) {
        if (!enabled) return
        val w = written.get()
        val p = pairs.get()
        val slot = (p and (PAIRS - 1L)).toInt()
        pairFrame[slot] = w
        pairPts[slot] = ptsUs
        pairs.lazySet(p + 1)
        if (channels == 2) {
            for (f in 0 until frames) ring[((w + f) and mask).toInt()] = 0.5f * (x[2 * f] + x[2 * f + 1])
        } else {
            for (f in 0 until frames) ring[((w + f) and mask).toInt()] = x[f * channels]
        }
        written.lazySet(w + frames)
    }

    /** Audio/playback thread: the position the sink reports (what is audible now). */
    fun audible(positionUs: Long, nowNs: Long) {
        if (!enabled) return
        audibleUs = positionUs
        audibleAtNs = nowNs
    }

    /** Reader: the audible position extrapolated to [nowNs] while [playing], or null before the first report. */
    fun audibleNow(nowNs: Long, playing: Boolean): Long? {
        val us = audibleUs
        if (us == Long.MIN_VALUE) return null
        return if (playing) us + (nowNs - audibleAtNs) / 1000 else us
    }

    /** Reader: the ring frame that plays at [positionUs]: the nearest recorded pair, plus (pos − pts)·fs. */
    fun frameAt(positionUs: Long): Long? {
        val n = pairs.get()
        val fs = sampleRate
        if (n == 0L || fs <= 0) return null
        var best = -1
        var bestDist = Long.MAX_VALUE
        for (i in maxOf(0L, n - PAIRS) until n) {
            val slot = (i and (PAIRS - 1L)).toInt()
            val d = kotlin.math.abs(positionUs - pairPts[slot])
            if (d < bestDist) {
                bestDist = d
                best = slot
            }
        }
        return pairFrame[best] + (positionUs - pairPts[best]) * fs / 1_000_000L
    }

    /** Reader: the [count] frames ending at [endFrame] into [dst] (clamped to what was written); false if too few. */
    fun read(endFrame: Long, count: Int, dst: FloatArray): Boolean {
        val end = minOf(endFrame, written.get())
        val start = end - count
        if (start < 0 || count > ring.size) return false
        for (i in 0 until count) dst[i] = ring[((start + i) and mask).toInt()]
        return true
    }

    companion object {
        const val CAPACITY = 32_768
        const val PAIRS = 64
    }
}
