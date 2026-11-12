package org.seion.playback.dsp.testutil

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A minimal RIFF/WAVE reader and writer (PCM 16/24/32, float 32), for the render tool only. */
object WavIo {
    class Wav(val fs: Int, val channels: Int, val data: FloatArray)

    fun read(f: File): Wav {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        check(b.getInt(0) == 0x46464952 && b.getInt(8) == 0x45564157) { "not a WAVE file: $f" }
        var pos = 12
        var fmt = 0
        var ch = 0
        var fs = 0
        var bits = 0
        while (pos + 8 <= b.limit()) {
            val id = b.getInt(pos)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            if (id == 0x20746d66) { // "fmt "
                fmt = b.getShort(body).toInt() and 0xFFFF
                ch = b.getShort(body + 2).toInt()
                fs = b.getInt(body + 4)
                bits = b.getShort(body + 14).toInt()
                if (fmt == 0xFFFE) fmt = b.getShort(body + 24).toInt() and 0xFFFF
            } else if (id == 0x61746164) { // "data"
                val bytes = bits / 8
                val n = minOf(size, b.limit() - body) / bytes
                val out = FloatArray(n)
                for (i in 0 until n) {
                    val p = body + i * bytes
                    out[i] =
                        when {
                            fmt == 3 && bits == 32 -> b.getFloat(p)
                            bits == 16 -> b.getShort(p) / 32768f
                            bits == 24 -> (
                                (b.get(p).toInt() and 0xFF) or ((b.get(p + 1).toInt() and 0xFF) shl 8) or
                                    (b.get(p + 2).toInt() shl 16)
                                ) /
                                8388608f
                            bits == 32 -> (b.getInt(p) / 2147483648.0).toFloat()
                            else -> error("unsupported WAV: format $fmt, $bits bits")
                        }
                }
                return Wav(fs, ch, out)
            }
            pos = body + size + (size and 1)
        }
        error("no data chunk: $f")
    }

    /** Writes 16-bit PCM (TPDF-dithered, clipped). */
    fun write16(f: File, fs: Int, channels: Int, data: FloatArray, seed: Long = 1) {
        val r = java.util.Random(seed)
        val n = data.size
        val b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x46464952).putInt(36 + n * 2).putInt(0x45564157)
        b.putInt(0x20746d66).putInt(16).putShort(1).putShort(channels.toShort()).putInt(fs).putInt(fs * channels * 2)
            .putShort((channels * 2).toShort()).putShort(16)
        b.putInt(0x61746164).putInt(n * 2)
        for (v in data) {
            val d = (r.nextDouble() - r.nextDouble()) / 32768
            b.putShort((Math.round((v + d) * 32767).coerceIn(-32768, 32767)).toShort())
        }
        f.writeBytes(b.array())
    }
}
