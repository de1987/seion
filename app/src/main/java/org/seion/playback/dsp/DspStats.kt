package org.seion.playback.dsp

/**
 * The measurement hook: preallocated, written only by the playback thread, read by
 * `PlaybackService.dump()` from any thread (tear-tolerant). [cpuClock] is the thread CPU clock
 * (`android.os.Debug.threadCpuTimeNanos()` on the device, a fake in the JVM tests); [wallClock] is monotonic.
 */
class DspStats(private val cpuClock: () -> Long, private val wallClock: () -> Long = System::nanoTime) {
    @Volatile var cpuNanos = 0L
        private set

    @Volatile var audioSeconds = 0.0
        private set

    @Volatile var blocks = 0L
        private set

    @Volatile var maxBlockNanos = 0L
        private set

    @Volatile var delegatedStreams = 0L
        private set

    @Volatile var nonFiniteResets = 0L
        private set

    private val histogram = LongArray(BUCKETS)

    /** Set from any thread; honoured by the playback thread at the next call (single writer). */
    @Volatile private var resetRequested = false

    private var cpuStart = 0L
    private var wallStart = 0L

    fun requestReset() {
        resetRequested = true
    }

    /** Playback thread: before the DSP work of one handleBuffer call. */
    fun beginCall() {
        if (resetRequested) {
            resetRequested = false
            cpuNanos = 0
            audioSeconds = 0.0
            blocks = 0
            maxBlockNanos = 0
            delegatedStreams = 0
            nonFiniteResets = 0
            histogram.fill(0)
        }
        cpuStart = cpuClock()
    }

    /** Playback thread: after it, with the audio it covered. */
    fun endCall(frames: Int, sampleRate: Int) {
        cpuNanos += cpuClock() - cpuStart
        if (sampleRate > 0) audioSeconds += frames.toDouble() / sampleRate
    }

    fun beginBlock() {
        wallStart = wallClock()
    }

    fun endBlock() {
        val ns = wallClock() - wallStart
        histogram[bucketOf(ns)]++
        blocks++
        if (ns > maxBlockNanos) maxBlockNanos = ns
    }

    fun onDelegatedStream() {
        delegatedStreams++
    }

    fun onNonFiniteReset() {
        nonFiniteResets++
    }

    /** Engine thread-CPU over audio time, in %. */
    fun loadPercent(): Double = if (audioSeconds > 0) cpuNanos / 1e9 / audioSeconds * 100 else 0.0

    /** The [q] quantile of the block wall time in µs (upper edge of the histogram bucket). */
    fun blockQuantileMicros(q: Double): Double {
        val counts = histogram.copyOf()
        val total = counts.sum()
        if (total == 0L) return 0.0
        val target = q * total
        var acc = 0L
        for (i in counts.indices) {
            acc += counts[i]
            if (acc >= target) return upperNanos(i) / 1000.0
        }
        return upperNanos(BUCKETS - 1) / 1000.0
    }

    companion object {
        const val BUCKETS = 64

        /** Quarter-octave buckets from 256 ns (bucket 0) to ~16 ms (bucket 63). */
        fun bucketOf(ns: Long): Int {
            if (ns < 256) return 0
            val e = 63 - java.lang.Long.numberOfLeadingZeros(ns)
            val m = ((ns shr (e - 2)) and 3).toInt()
            return (e * 4 + m - 32).coerceIn(0, BUCKETS - 1)
        }

        fun upperNanos(bucket: Int): Double {
            val idx = bucket + 32 + 1
            val e = idx / 4
            val m = idx % 4
            return (1L shl e) * (1 + m / 4.0)
        }
    }
}
