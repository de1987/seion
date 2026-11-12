package org.seion.playback.dsp.testutil

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/** Test-side PCM helpers: random samples per encoding with their exact float value, and a renderer. */
object Pcm {
    val ENCODINGS =
        intArrayOf(
            C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT,
            C.ENCODING_PCM_FLOAT_BIG_ENDIAN, C.ENCODING_PCM_DOUBLE, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN
        )

    fun bytesPer(encoding: Int) = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
        C.ENCODING_PCM_DOUBLE, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> 8
        else -> 4
    }

    /** Random samples of [encoding]: the encoded bytes and the exact float each one converts to. */
    fun random(encoding: Int, samples: Int, rnd: Random): Pair<ByteArray, FloatArray> {
        val big =
            encoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN ||
                encoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN ||
                encoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN ||
                encoding == C.ENCODING_PCM_FLOAT_BIG_ENDIAN ||
                encoding == C.ENCODING_PCM_DOUBLE_BIG_ENDIAN
        val bb = ByteBuffer.allocate(samples * bytesPer(encoding)).order(if (big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val f = FloatArray(samples)
        for (i in 0 until samples) {
            when (encoding) {
                C.ENCODING_PCM_8BIT -> {
                    val b = rnd.nextInt(256)
                    bb.put(b.toByte())
                    f[i] = (b - 128) / 128f
                }
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> {
                    val s = rnd.nextInt(65536) - 32768
                    bb.putShort(s.toShort())
                    f[i] = s / 32768f
                }
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                    val s = rnd.nextInt(1 shl 24) - (1 shl 23)
                    if (big) {
                        bb.put((s shr 16).toByte()).put((s shr 8).toByte()).put(s.toByte())
                    } else {
                        bb.put(s.toByte()).put((s shr 8).toByte()).put((s shr 16).toByte())
                    }
                    f[i] = s / 8388608f
                }
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> {
                    val s = rnd.nextInt()
                    bb.putInt(s)
                    f[i] = (s * (1.0 / 0x7FFFFFFF)).toFloat()
                }
                C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> {
                    val v = rnd.nextFloat() * 2 - 1
                    bb.putFloat(v)
                    f[i] = v
                }
                else -> {
                    val v = rnd.nextDouble() * 2 - 1
                    bb.putDouble(v)
                    f[i] = v.toFloat()
                }
            }
        }
        return bb.array() to f
    }

    fun float32(samples: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bb.putFloat(it) }
        return bb.array()
    }

    fun sine(frames: Int, channels: Int, fs: Int, hz: Double, amp: Double = 0.5, phase0: Long = 0): FloatArray =
        FloatArray(frames * channels) { i -> (amp * sin(2 * PI * hz * ((i / channels) + phase0) / fs)).toFloat() }

    fun format(encoding: Int, fs: Int, channels: Int, delay: Int = 0, padding: Int = 0): Format = Format
        .Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(encoding)
        .setSampleRate(fs)
        .setChannelCount(channels)
        .setEncoderDelay(delay)
        .setEncoderPadding(padding)
        .build()

    fun config(format: Format): AudioSink.AudioSinkConfig = AudioSink.AudioSinkConfig.Builder(format).build()

    /**
     * Feeds [bytes] (whole frames of [frameBytes]) to [sink] in random buffer sizes like a renderer: each buffer is
     * presented again until the sink reports it consumed. Returns the pts of each buffer.
     */
    fun feed(
        sink: AudioSink,
        bytes: ByteArray,
        frameBytes: Int,
        fs: Int,
        rnd: Random,
        startPtsUs: Long = 0,
        maxFrames: Int = 4096
    ): List<Long> {
        val pts = mutableListOf<Long>()
        var pos = 0
        var frameIndex = 0L
        while (pos < bytes.size) {
            val frames = minOf(1 + rnd.nextInt(maxFrames), (bytes.size - pos) / frameBytes)
            val buf = ByteBuffer.wrap(bytes, pos, frames * frameBytes).slice().order(ByteOrder.LITTLE_ENDIAN)
            val t = startPtsUs + frameIndex * 1_000_000 / fs
            pts += t
            var guard = 0
            while (!sink.handleBuffer(buf, t, 1)) check(++guard < 1_000_000) { "sink never consumed the buffer" }
            pos += frames * frameBytes
            frameIndex += frames
        }
        return pts
    }

    /** Calls playToEndOfStream until the sink reports ended. */
    fun endOfStream(sink: AudioSink) {
        var guard = 0
        do {
            sink.playToEndOfStream()
        } while (!sink.isEnded() && ++guard < 1_000_000)
        check(sink.isEnded())
    }
}
