package org.seion.playback.dsp

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every Media3 PCM encoding, little- and big-endian, extremes, converted exactly. */
class PcmToFloatTest {
    private fun conv(bytes: ByteArray, encoding: Int): FloatArray {
        val n = bytes.size / PcmToFloat.bytesPerSample(encoding)
        val out = FloatArray(n + 2) { Float.NaN }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        PcmToFloat.convert(buf, n, encoding, out, 1)
        assertEquals(bytes.size, buf.position())
        assertTrue(out[0].isNaN() && out[n + 1].isNaN()) // writes exactly n samples at the offset
        return out.copyOfRange(1, n + 1)
    }

    private fun bits(expected: Float, actual: Float, what: String) =
        assertEquals("$what expected $expected got $actual", expected.toRawBits(), actual.toRawBits())

    @Test fun `8-bit unsigned`() {
        val f = conv(byteArrayOf(0, 128.toByte(), 255.toByte(), 64), C.ENCODING_PCM_8BIT)
        bits(-1f, f[0], "0")
        bits(0f, f[1], "128")
        bits(127f / 128f, f[2], "255")
        bits(-0.5f, f[3], "64")
    }

    @Test fun `16-bit both endians`() {
        val values = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE, 12345)
        val le = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putShort(it) } }.array()
        val be = ByteBuffer.allocate(values.size * 2).order(ByteOrder.BIG_ENDIAN).apply { values.forEach { putShort(it) } }.array()
        val fl = conv(le, C.ENCODING_PCM_16BIT)
        val fb = conv(be, C.ENCODING_PCM_16BIT_BIG_ENDIAN)
        values.forEachIndexed { i, s ->
            bits(s / 32768f, fl[i], "le $s")
            bits(s / 32768f, fb[i], "be $s")
        }
        bits(-1f, fl[0], "min")
    }

    @Test fun `24-bit packed both endians`() {
        val values = intArrayOf(-0x800000, -1, 0, 1, 0x7FFFFF, 0x123456)
        fun pack(v: Int, big: Boolean) = if (big) {
            byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        } else {
            byteArrayOf(
                v.toByte(),
                (
                    v shr
                        8
                    ).toByte(),
                (v shr 16).toByte()
            )
        }
        val le = values.flatMap { pack(it, false).toList() }.toByteArray()
        val be = values.flatMap { pack(it, true).toList() }.toByteArray()
        val fl = conv(le, C.ENCODING_PCM_24BIT)
        val fb = conv(be, C.ENCODING_PCM_24BIT_BIG_ENDIAN)
        values.forEachIndexed { i, v ->
            bits(v / 8388608f, fl[i], "le $v")
            bits(v / 8388608f, fb[i], "be $v")
        }
        bits(-1f, fl[0], "min")
    }

    @Test fun `32-bit uses Media3's factor`() {
        val values = intArrayOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE, 0x12345678)
        val le = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it) } }.array()
        val be = ByteBuffer.allocate(values.size * 4).order(ByteOrder.BIG_ENDIAN).apply { values.forEach { putInt(it) } }.array()
        val fl = conv(le, C.ENCODING_PCM_32BIT)
        val fb = conv(be, C.ENCODING_PCM_32BIT_BIG_ENDIAN)
        values.forEachIndexed { i, v ->
            val expected = (v * (1.0 / 0x7FFFFFFF)).toFloat()
            bits(expected, fl[i], "le $v")
            bits(expected, fb[i], "be $v")
        }
        bits(1f, fl[4], "max")
    }

    @Test fun `float copies bits and byte-swaps big-endian`() {
        val values = floatArrayOf(-1f, 0f, -0f, 1.5f, Float.MIN_VALUE, 0.123456789f, 3.0e-41f)
        val le = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putFloat(it) } }.array()
        val be = ByteBuffer.allocate(values.size * 4).order(ByteOrder.BIG_ENDIAN).apply { values.forEach { putFloat(it) } }.array()
        val fl = conv(le, C.ENCODING_PCM_FLOAT)
        val fb = conv(be, C.ENCODING_PCM_FLOAT_BIG_ENDIAN)
        values.forEachIndexed { i, v ->
            bits(v, fl[i], "le $v")
            bits(v, fb[i], "be $v")
        }
    }

    @Test fun `double rounds to float`() {
        val values = doubleArrayOf(-1.0, 0.0, 0.1, 1e-300, 0.7071067811865476)
        val le = ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putDouble(it) } }.array()
        val be = ByteBuffer.allocate(values.size * 8).order(ByteOrder.BIG_ENDIAN).apply { values.forEach { putDouble(it) } }.array()
        val fl = conv(le, C.ENCODING_PCM_DOUBLE)
        val fb = conv(be, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN)
        values.forEachIndexed { i, v ->
            bits(v.toFloat(), fl[i], "le $v")
            bits(v.toFloat(), fb[i], "be $v")
        }
    }

    @Test fun `supported encodings are exactly the eleven PCM constants`() {
        val pcm =
            intArrayOf(
                C.ENCODING_PCM_8BIT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN, C.ENCODING_PCM_24BIT,
                C.ENCODING_PCM_24BIT_BIG_ENDIAN, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT,
                C.ENCODING_PCM_FLOAT_BIG_ENDIAN, C.ENCODING_PCM_DOUBLE, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN
            )
        pcm.forEach { assertTrue("$it", PcmToFloat.isSupported(it)) }
        intArrayOf(C.ENCODING_INVALID, C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_MP3, -1).forEach {
            assertFalse("$it", PcmToFloat.isSupported(it))
        }
    }

    @Test fun `a buffer in big-endian order mode converts the same`() {
        val le = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(-2).putShort(300).array()
        val out = FloatArray(2)
        PcmToFloat.convert(ByteBuffer.wrap(le).order(ByteOrder.BIG_ENDIAN), 2, C.ENCODING_PCM_16BIT, out, 0)
        bits(-2 / 32768f, out[0], "a")
        bits(300 / 32768f, out[1], "b")
    }
}
