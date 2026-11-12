package org.seion.playback.dsp.testutil

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioSink
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * A recording inner sink: records configure calls, every byte it accepts with the pts of the buffer
 * it came from, flush/reset/EOS. It can accept random partial amounts (seeded, in whole frames) and enforces
 * DefaultAudioSink's rule that a partly consumed buffer is re-presented as the same object.
 */
class FakeInnerSink(private val partial: Random? = null, var refuseAll: Boolean = false) {
    data class Chunk(val pts: Long, val config: Int, val bytes: ByteArray)

    val configs = mutableListOf<AudioSink.AudioSinkConfig>()
    val events = mutableListOf<String>()
    val chunks = mutableListOf<Chunk>()
    private val stream = ByteArrayOutputStream()
    private var current: ByteBuffer? = null
    var ended = false
    var calls = 0

    val sink: AudioSink =
        Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, m, args ->
            when (m.name) {
                "configure" -> {
                    configs += args!![0] as AudioSink.AudioSinkConfig
                    events += "configure"
                    null
                }
                "handleBuffer" -> handle(args!![0] as ByteBuffer, args[1] as Long)
                "playToEndOfStream" -> {
                    events += "eos"
                    ended = true
                    null
                }
                "isEnded" -> ended
                "hasPendingData" -> false
                "flush" -> {
                    events += "flush"
                    current = null
                    ended = false
                    null
                }
                "reset" -> {
                    events += "reset"
                    current = null
                    null
                }
                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                "supportsFormat" -> true
                "getCurrentPositionUs" -> 0L
                else ->
                    when (m.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Long::class.javaPrimitiveType -> 0L
                        Int::class.javaPrimitiveType -> 0
                        Float::class.javaPrimitiveType -> 0f
                        else -> null
                    }
            }
        } as AudioSink

    private fun handle(buf: ByteBuffer, pts: Long): Boolean {
        calls++
        check(current == null || current === buf) { "a partly consumed buffer must be re-presented" }
        check(buf.order() == ByteOrder.LITTLE_ENDIAN)
        if (!buf.hasRemaining()) return true
        if (refuseAll) return false
        val frameBytes = frameBytes()
        val frames = buf.remaining() / frameBytes
        val take = if (partial == null) frames else partial.nextInt(frames + 1)
        if (take > 0) {
            val bytes = ByteArray(take * frameBytes)
            buf.get(bytes)
            stream.write(bytes)
            val ptsOfChunk = if (current == null) pts else C.TIME_UNSET
            chunks += Chunk(ptsOfChunk, configs.size - 1, bytes)
        }
        current = if (buf.hasRemaining()) buf else null
        return !buf.hasRemaining()
    }

    private fun frameBytes(): Int {
        val f = configs.last().format
        val bytes = if (f.pcmEncoding == C.ENCODING_PCM_FLOAT) {
            4
        } else if (f.pcmEncoding == C.ENCODING_PCM_16BIT) {
            2
        } else {
            1
        }
        return bytes * f.channelCount
    }

    fun bytes(): ByteArray = stream.toByteArray()

    /** Everything accepted, as floats (the inner sink is configured for float). */
    fun floats(): FloatArray {
        val b = ByteBuffer.wrap(bytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    /** Floats accepted under configure number [index]. */
    fun floatsFor(index: Int): FloatArray {
        val all = ByteArrayOutputStream()
        chunks.filter { it.config == index }.forEach { all.write(it.bytes) }
        val b = ByteBuffer.wrap(all.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }
}
