package org.seion.playback.dsp.core

/**
 * A real FFT behind an interface: a native implementation could replace [RealFft512] without
 * touching RenovaPure. Forward is unnormalised (numpy rfft convention); inverse includes 1/N (numpy irfft).
 */
interface Fft {
    val size: Int

    /** [x] (size) → [re], [im] (size/2 + 1 bins). */
    fun forward(x: FloatArray, re: FloatArray, im: FloatArray)

    /** [re], [im] (size/2 + 1 bins) → [x] (size). */
    fun inverse(re: FloatArray, im: FloatArray, x: FloatArray)
}

/**
 * Radix-2 complex FFT of size/2 plus the split for real input, in float, with twiddles precomputed from StrictMath and
 * no allocation per call. Works for any power of two (512 for RenovaPure, 4096 for the detector).
 */
class RealFft(override val size: Int) : Fft {
    private val m = size / 2
    private val zr = FloatArray(m)
    private val zi = FloatArray(m)
    private val cosM = FloatArray(m / 2)
    private val sinM = FloatArray(m / 2)
    private val cosN = FloatArray(m + 1)
    private val sinN = FloatArray(m + 1)
    private val rev = IntArray(m)

    init {
        check(size >= 4 && size and (size - 1) == 0) { "size must be a power of two" }
        for (k in 0 until m / 2) {
            cosM[k] = StrictMath.cos(2 * Math.PI * k / m).toFloat()
            sinM[k] = StrictMath.sin(2 * Math.PI * k / m).toFloat()
        }
        for (k in 0..m) {
            cosN[k] = StrictMath.cos(2 * Math.PI * k / size).toFloat()
            sinN[k] = StrictMath.sin(2 * Math.PI * k / size).toFloat()
        }
        var bits = 0
        while ((1 shl bits) < m) bits++
        for (i in 0 until m) {
            var r = 0
            var v = i
            for (b in 0 until bits) {
                r = (r shl 1) or (v and 1)
                v = v shr 1
            }
            rev[i] = r
        }
    }

    /** In-place complex FFT of (zr, zi); sign −1 forward, +1 inverse (unnormalised). */
    private fun complex(sign: Int) {
        for (i in 0 until m) {
            val j = rev[i]
            if (i < j) {
                var t = zr[i]
                zr[i] = zr[j]
                zr[j] = t
                t = zi[i]
                zi[i] = zi[j]
                zi[j] = t
            }
        }
        var len = 2
        while (len <= m) {
            val half = len / 2
            val step = m / len
            var i = 0
            while (i < m) {
                var k = 0
                for (j in 0 until half) {
                    val wr = cosM[k]
                    val wi = sign * sinM[k]
                    val a = i + j
                    val b = a + half
                    val tr = zr[b] * wr - zi[b] * wi
                    val ti = zr[b] * wi + zi[b] * wr
                    zr[b] = zr[a] - tr
                    zi[b] = zi[a] - ti
                    zr[a] += tr
                    zi[a] += ti
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    override fun forward(x: FloatArray, re: FloatArray, im: FloatArray) {
        for (n in 0 until m) {
            zr[n] = x[2 * n]
            zi[n] = x[2 * n + 1]
        }
        complex(-1)
        for (k in 0..m) {
            val a = k % m
            val b = (m - k) % m
            // E = (Z[k] + conj Z[m−k]) / 2, O = (Z[k] − conj Z[m−k]) / (2j)
            val er = 0.5f * (zr[a] + zr[b])
            val ei = 0.5f * (zi[a] - zi[b])
            val or = 0.5f * (zi[a] + zi[b])
            val oi = -0.5f * (zr[a] - zr[b])
            // X = E + W^k·O, W = e^{−j2π/N}
            val wr = cosN[k]
            val wi = -sinN[k]
            re[k] = er + wr * or - wi * oi
            im[k] = ei + wr * oi + wi * or
        }
    }

    override fun inverse(re: FloatArray, im: FloatArray, x: FloatArray) {
        for (k in 0 until m) {
            // E = (X[k] + conj X[m−k]) / 2, O = (X[k] − conj X[m−k]) / (2·W^k); Z = E + j·O
            val br = re[m - k]
            val bi = -im[m - k]
            val er = 0.5f * (re[k] + br)
            val ei = 0.5f * (im[k] + bi)
            val dr = 0.5f * (re[k] - br)
            val di = 0.5f * (im[k] - bi)
            // divide by W^k = multiply by conj(W^k) = e^{+j2πk/N}
            val wr = cosN[k]
            val wi = sinN[k]
            val or = dr * wr - di * wi
            val oi = dr * wi + di * wr
            zr[k] = er - oi
            zi[k] = ei + or
        }
        complex(1)
        val scale = 1f / m
        for (n in 0 until m) {
            x[2 * n] = zr[n] * scale
            x[2 * n + 1] = zi[n] * scale
        }
    }
}

/** The 512-point FFT of RenovaPure's STFT. */
typealias RealFft512 = RealFft
