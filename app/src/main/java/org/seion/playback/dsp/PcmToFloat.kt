package org.seion.playback.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Decoded PCM of any Media3 encoding → interleaved float. Exact for 8/16/24-bit and float; 32-bit
 * uses Media3's own factor (1/0x7FFFFFFF, as `ToFloatPcmAudioProcessor`); double rounds to float. Reads with
 * absolute gets assembled byte by byte, so the source buffer's byte order setting does not matter, and nothing is
 * allocated.
 */
@OptIn(UnstableApi::class)
object PcmToFloat {
    fun isSupported(encoding: Int): Boolean = bytesPerSample(encoding) > 0

    /** Bytes per sample of [encoding], or 0 when it is not a PCM encoding this converter handles. */
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> 4
        C.ENCODING_PCM_DOUBLE, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> 8
        else -> 0
    }

    /**
     * Converts [samples] samples starting at [src]'s position into [dst] from [dstOffset], and advances the position.
     */
    fun convert(src: ByteBuffer, samples: Int, encoding: Int, dst: FloatArray, dstOffset: Int) {
        var p = src.position()
        var o = dstOffset
        val end = dstOffset + samples
        when (encoding) {
            C.ENCODING_PCM_8BIT -> while (o < end) {
                dst[o++] = ((src.get(p++).toInt() and 0xFF) - 128) * INV_128
            }
            C.ENCODING_PCM_16BIT -> while (o < end) {
                dst[o++] = ((src.get(p).toInt() and 0xFF) or (src.get(p + 1).toInt() shl 8)).toShort() * INV_32768
                p += 2
            }
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> while (o < end) {
                dst[o++] = ((src.get(p + 1).toInt() and 0xFF) or (src.get(p).toInt() shl 8)).toShort() * INV_32768
                p += 2
            }
            C.ENCODING_PCM_24BIT -> while (o < end) {
                val v = (src.get(p).toInt() and 0xFF) or ((src.get(p + 1).toInt() and 0xFF) shl 8) or (src.get(p + 2).toInt() shl 16)
                dst[o++] = v * INV_8388608
                p += 3
            }
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> while (o < end) {
                val v = (src.get(p + 2).toInt() and 0xFF) or ((src.get(p + 1).toInt() and 0xFF) shl 8) or (src.get(p).toInt() shl 16)
                dst[o++] = v * INV_8388608
                p += 3
            }
            C.ENCODING_PCM_32BIT -> while (o < end) {
                dst[o++] = (le32(src, p) * INT32_FACTOR).toFloat()
                p += 4
            }
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> while (o < end) {
                dst[o++] = (be32(src, p) * INT32_FACTOR).toFloat()
                p += 4
            }
            C.ENCODING_PCM_FLOAT -> while (o < end) {
                dst[o++] = Float.fromBits(le32(src, p))
                p += 4
            }
            C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> while (o < end) {
                dst[o++] = Float.fromBits(be32(src, p))
                p += 4
            }
            C.ENCODING_PCM_DOUBLE -> while (o < end) {
                val bits = (le32(src, p).toLong() and 0xFFFFFFFFL) or (le32(src, p + 4).toLong() shl 32)
                dst[o++] = Double.fromBits(bits).toFloat()
                p += 8
            }
            C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> while (o < end) {
                val bits = (be32(src, p + 4).toLong() and 0xFFFFFFFFL) or (be32(src, p).toLong() shl 32)
                dst[o++] = Double.fromBits(bits).toFloat()
                p += 8
            }
            else -> check(false) { "unsupported encoding" }
        }
        src.position(p)
    }

    private fun le32(b: ByteBuffer, p: Int): Int =
        (b.get(p).toInt() and 0xFF) or ((b.get(p + 1).toInt() and 0xFF) shl 8) or ((b.get(p + 2).toInt() and 0xFF) shl 16) or
            (b.get(p + 3).toInt() shl 24)

    private fun be32(b: ByteBuffer, p: Int): Int =
        (b.get(p + 3).toInt() and 0xFF) or ((b.get(p + 2).toInt() and 0xFF) shl 8) or ((b.get(p + 1).toInt() and 0xFF) shl 16) or
            (b.get(p).toInt() shl 24)

    private const val INV_128 = 1f / 128f
    private const val INV_32768 = 1f / 32768f
    private const val INV_8388608 = 1f / 8388608f

    /** Media3's `PCM_32_BIT_INT_TO_PCM_32_BIT_FLOAT_FACTOR`. */
    private const val INT32_FACTOR = 1.0 / 0x7FFFFFFF
}
