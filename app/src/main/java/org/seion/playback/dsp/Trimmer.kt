package org.seion.playback.dsp

/**
 * Encoder delay/padding trimming on interleaved float frames, owned by [SeionAudioSink] because the inner sink would
 * trim *our delayed output*. It mirrors Media3's `TrimmingAudioProcessor`:
 * - [configure] records d and p and marks a reconfiguration pending (Media3: `reconfigurationPending`); the withheld
 *   end padding of the previous stream is discarded there (parity: a gapless reconfigure trims the end buffer);
 * - the next [flush] or the first [process] after a configure arms "drop the next d frames" (parity: `onFlush`);
 * - the last p frames are always withheld in a preallocated ring and released in order when more audio of the same
 *   stream arrives (parity);
 * - [flush] (seek) drops the withheld frames (parity) and does not re-trim (parity, the b/77292509 comment);
 * - [discardWithheld] at a true end of stream drops them too: a **deliberate deviation**; Media3 emits them.
 * Streams whose padding exceeds [capacityFrames] are delegated by the sink, never clamped.
 */
class Trimmer(maxChannels: Int = 2, val capacityFrames: Int = CAPACITY_FRAMES) {
    private val ring = FloatArray(capacityFrames * maxChannels)
    private var channels = 1
    private var delay = 0
    private var padding = 0
    private var reconfigurationPending = false
    private var startTrimRemaining = 0
    private var held = 0
    private var readFrame = 0

    /** Frames trimmed from the start since the last flush (Media3: `getTrimmedFrameCount`, reset on flush). */
    var trimmedFrames = 0L
        private set

    val withheldFrames: Int get() = held

    fun configure(delayFrames: Int, paddingFrames: Int, channelCount: Int) {
        check(paddingFrames <= capacityFrames) { "padding exceeds the ring" }
        held = 0
        readFrame = 0
        delay = delayFrames.coerceAtLeast(0)
        padding = paddingFrames.coerceAtLeast(0)
        channels = channelCount
        reconfigurationPending = true
    }

    fun flush() {
        if (reconfigurationPending) {
            reconfigurationPending = false
            startTrimRemaining = delay
        }
        held = 0
        readFrame = 0
        trimmedFrames = 0
    }

    fun reset() {
        reconfigurationPending = false
        startTrimRemaining = 0
        held = 0
        readFrame = 0
        trimmedFrames = 0
        delay = 0
        padding = 0
    }

    /** The end-of-stream deviation: the withheld padding is not music and is dropped. */
    fun discardWithheld() {
        held = 0
        readFrame = 0
    }

    /**
     * Consumes [frames] frames of [src] (from 0) and writes the frames to output into [dst] (from 0); returns how
     * many. Never more than [frames].
     */
    fun process(src: FloatArray, frames: Int, dst: FloatArray): Int {
        if (reconfigurationPending) {
            reconfigurationPending = false
            startTrimRemaining = delay
        }
        val ch = channels
        var inFrame = 0
        if (startTrimRemaining > 0) {
            val t = minOf(startTrimRemaining, frames)
            startTrimRemaining -= t
            trimmedFrames += t
            inFrame = t
        }
        val k = frames - inFrame
        if (k == 0) return 0
        if (padding == 0) {
            System.arraycopy(src, inFrame * ch, dst, 0, k * ch)
            return k
        }
        // Output = surplus of (withheld + new) over the padding, oldest first.
        val surplus = held + k - padding
        var out = 0
        if (surplus > 0) {
            val fromRing = minOf(surplus, held)
            var i = 0
            while (i < fromRing) {
                val r = ((readFrame + i) % capacityFrames) * ch
                for (c in 0 until ch) dst[out * ch + c] = ring[r + c]
                out++
                i++
            }
            readFrame = (readFrame + fromRing) % capacityFrames
            held -= fromRing
            val fromInput = surplus - fromRing
            if (fromInput > 0) {
                System.arraycopy(src, inFrame * ch, dst, out * ch, fromInput * ch)
                out += fromInput
                inFrame += fromInput
            }
        }
        // Withhold the rest of the input.
        while (inFrame < frames) {
            val w = ((readFrame + held) % capacityFrames) * ch
            for (c in 0 until ch) ring[w + c] = src[inFrame * ch + c]
            held++
            inFrame++
        }
        return out
    }

    companion object {
        /** The ring holds up to 8192 frames of end padding (streams with larger paddings are delegated). */
        const val CAPACITY_FRAMES = 8192
    }
}
