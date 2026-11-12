package org.seion.playback.dsp.core

/**
 * Sample-synchronous weighted overlap-add: periodic √Hann analysis window, N 512, hop 128;
 * synthesis window w/(Σw²/hop) = w/2. [push] takes one input sample per channel; every hop a full frame is analysed
 * ([FrameProcessor]), synthesised and overlap-added; [output] returns the sample of time n − (N − 1), the minimal
 * constant latency of a streaming WOLA. With an identity processor the output is the input delayed by 511.
 */
class Wola(val n: Int = 512, val hop: Int = 128, private val channels: Int = 2) {
    fun interface FrameProcessor {
        /** [re]/[im] hold the analysed frame of channel [ch]; modify them in place. */
        fun process(ch: Int, re: FloatArray, im: FloatArray)
    }

    val analysis = FloatArray(n) { StrictMath.sqrt(0.5 - 0.5 * StrictMath.cos(2 * Math.PI * it / n)).toFloat() }
    val synthesis = FloatArray(n)
    private val fft = RealFft(n)
    private val input = Array(channels) { FloatArray(n) }
    private val ola = Array(channels) { FloatArray(n) }
    private val fifo = Array(channels) { FloatArray(FIFO) }
    private val frame = FloatArray(n)
    val re = FloatArray(n / 2 + 1)
    val im = FloatArray(n / 2 + 1)
    private var inPos = 0
    private var sinceHop = 0
    private var time = 0L

    /** Absolute index of the next input sample (per channel, all advance together). */
    val clock: Long get() = time

    init {
        var sum = 0.0
        for (w in analysis) sum += w.toDouble() * w
        val norm = sum / hop
        for (i in 0 until n) synthesis[i] = (analysis[i] / norm).toFloat()
    }

    fun reset() {
        for (c in 0 until channels) {
            input[c].fill(0f)
            ola[c].fill(0f)
            fifo[c].fill(0f)
        }
        inPos = 0
        sinceHop = 0
        time = 0
    }

    /** Writes one frame of input (all [ch] channels from [x] at [off]); runs the frame processor on hop boundaries. */
    fun push(x: FloatArray, off: Int, ch: Int, proc: FrameProcessor?) {
        for (c in 0 until ch) input[c][inPos] = x[off + c]
        inPos = if (inPos + 1 == n) 0 else inPos + 1
        time++
        if (++sinceHop == hop) {
            sinceHop = 0
            for (c in 0 until ch) frameFor(c, proc)
        }
    }

    private fun frameFor(c: Int, proc: FrameProcessor?) {
        // Oldest first: the ring position inPos is the oldest sample.
        val buf = input[c]
        for (i in 0 until n) frame[i] = buf[(inPos + i) % n] * analysis[i]
        fft.forward(frame, re, im)
        proc?.process(c, re, im)
        fft.inverse(re, im, frame)
        val o = ola[c]
        for (i in 0 until n) o[i] += frame[i] * synthesis[i]
        // The oldest hop of the OLA is final: frame ended at time−1, final samples are times [time−n, time−n+hop).
        val f = fifo[c]
        val first = time - n
        for (i in 0 until hop) f[((first + i) and (FIFO - 1).toLong()).toInt()] = o[i]
        System.arraycopy(o, hop, o, 0, n - hop)
        java.util.Arrays.fill(o, n - hop, n, 0f)
    }

    /** The synthesised sample of time (clock − 1) − (N − 1) for channel [c] (call after [push]). */
    fun output(c: Int): Float {
        val t = time - 1 - (n - 1)
        if (t < 0) return 0f
        return fifo[c][(t and (FIFO - 1).toLong()).toInt()]
    }

    companion object {
        private const val FIFO = 1024
    }
}
