package org.seion.playback.dsp.core

import kotlin.math.PI

/**
 * Filter design at configure time: second-order shelves and Butterworth low/high/band-pass by the bilinear
 * transform with pre-warping, the same construction as scipy.signal.butter (analog prototype → lp2lp/lp2hp/lp2bp →
 * bilinear), so the Python reference (tools/dsp/frozen_vectors.py) gives the same coefficients. Coefficients come
 * from StrictMath.
 * Sections are written as (b0, b1, b2, a1, a2) per biquad; first-order sections have b2 = a2 = 0.
 */
object Filters {
    /** A second-order shelving biquad by the bilinear transform (shelf slope [s]) into [out]. */
    fun shelf(fs: Int, f0: Double, gainDb: Double, low: Boolean, s: Double, out: DoubleArray, off: Int = 0) {
        val a = StrictMath.pow(10.0, gainDb / 40)
        val w = 2 * PI * f0 / fs
        val cw = StrictMath.cos(w)
        val sw = StrictMath.sin(w)
        val alpha = sw / 2 * StrictMath.sqrt((a + 1 / a) * (1 / s - 1) + 2)
        val sa = 2 * StrictMath.sqrt(a) * alpha
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        if (low) {
            b0 = a * ((a + 1) - (a - 1) * cw + sa)
            b1 = 2 * a * ((a - 1) - (a + 1) * cw)
            b2 = a * ((a + 1) - (a - 1) * cw - sa)
            a0 = (a + 1) + (a - 1) * cw + sa
            a1 = -2 * ((a - 1) + (a + 1) * cw)
            a2 = (a + 1) + (a - 1) * cw - sa
        } else {
            b0 = a * ((a + 1) + (a - 1) * cw + sa)
            b1 = -2 * a * ((a - 1) + (a + 1) * cw)
            b2 = a * ((a + 1) + (a - 1) * cw - sa)
            a0 = (a + 1) - (a - 1) * cw + sa
            a1 = 2 * ((a - 1) - (a + 1) * cw)
            a2 = (a + 1) - (a - 1) * cw - sa
        }
        out[off] = b0 / a0
        out[off + 1] = b1 / a0
        out[off + 2] = b2 / a0
        out[off + 3] = a1 / a0
        out[off + 4] = a2 / a0
    }

    enum class Kind { LOW, HIGH, BAND }

    /**
     * Butterworth of [order] (band-pass: 2·order poles) into [out] as biquad sections; returns the section count.
     * [f1] is the cutoff (or the lower band edge), [f2] the upper band edge for [Kind.BAND].
     */
    fun butterworth(order: Int, kind: Kind, f1: Double, f2: Double, fs: Int, out: DoubleArray): Int {
        val n = order
        val np = if (kind == Kind.BAND) 2 * n else n
        val pr = DoubleArray(np)
        val pi = DoubleArray(np)
        val fs2 = 2.0 * fs
        fun warp(f: Double) = fs2 * StrictMath.tan(PI * f / fs)
        val w1 = warp(f1)
        // Analog prototype poles: -exp(j·π·m/(2n)), m = -n+1, -n+3, …, n-1 (scipy buttap).
        var k = 1.0
        var count = 0
        var zerosAtOrigin = 0
        for (i in 0 until n) {
            val m = -n + 1 + 2 * i
            val ang = PI * m / (2 * n)
            val re = -StrictMath.cos(ang)
            val im = -StrictMath.sin(ang)
            when (kind) {
                Kind.LOW -> {
                    pr[count] = w1 * re
                    pi[count] = w1 * im
                    count++
                }
                Kind.HIGH -> {
                    // wo / p
                    val d = re * re + im * im
                    pr[count] = w1 * re / d
                    pi[count] = -w1 * im / d
                    count++
                }
                Kind.BAND -> {
                    val w2 = warp(f2)
                    val bw = w2 - w1
                    val wo2 = w1 * w2
                    val hr = re * bw / 2
                    val hi = im * bw / 2
                    // sqrt(h² − wo²)
                    val qr = hr * hr - hi * hi - wo2
                    val qi = 2 * hr * hi
                    val mag = StrictMath.sqrt(StrictMath.sqrt(qr * qr + qi * qi))
                    val ph = StrictMath.atan2(qi, qr) / 2
                    val sr = mag * StrictMath.cos(ph)
                    val si = mag * StrictMath.sin(ph)
                    pr[count] = hr + sr
                    pi[count] = hi + si
                    count++
                    pr[count] = hr - sr
                    pi[count] = hi - si
                    count++
                }
            }
        }
        when (kind) {
            Kind.LOW -> k = StrictMath.pow(w1, n.toDouble())
            Kind.HIGH -> {
                // k = real(prod(-z)/prod(-p)) with no finite prototype zeros: 1/prod(-p) (prototype poles).
                var rr = 1.0
                var ii = 0.0
                for (i in 0 until n) {
                    val m = -n + 1 + 2 * i
                    val ang = PI * m / (2 * n)
                    val re = StrictMath.cos(ang) // -p
                    val im = StrictMath.sin(ang)
                    val t = rr * re - ii * im
                    ii = rr * im + ii * re
                    rr = t
                }
                k = rr / (rr * rr + ii * ii)
                zerosAtOrigin = n
            }
            Kind.BAND -> {
                k = StrictMath.pow(warp(f2) - w1, n.toDouble())
                zerosAtOrigin = n
            }
        }
        // Bilinear: k_d = k · prod(fs2 − z) / prod(fs2 − p); zeros at the origin give fs2 each.
        var nr = StrictMath.pow(fs2, zerosAtOrigin.toDouble())
        var dr = 1.0
        var di = 0.0
        val zr = DoubleArray(np)
        val zi = DoubleArray(np)
        for (i in 0 until np) {
            val ar = fs2 - pr[i]
            val aIm = -pi[i]
            val t = dr * ar - di * aIm
            di = dr * aIm + di * ar
            dr = t
            // z = (fs2 + p) / (fs2 − p)
            val br = fs2 + pr[i]
            val bi = pi[i]
            val d = ar * ar + aIm * aIm
            zr[i] = (br * ar + bi * aIm) / d
            zi[i] = (bi * ar - br * aIm) / d
        }
        val kd = k * nr * dr / (dr * dr + di * di)
        nr = 0.0
        // Pair conjugate poles into sections; zeros: LOW → −1, HIGH → +1, BAND → one of each per section.
        val used = BooleanArray(np)
        var sections = 0
        for (i in 0 until np) {
            if (used[i]) continue
            used[i] = true
            var j = -1
            if (kotlin.math.abs(zi[i]) > 1e-12) {
                var best = Double.MAX_VALUE
                for (q in 0 until np) {
                    if (used[q]) continue
                    val e = kotlin.math.abs(zr[q] - zr[i]) + kotlin.math.abs(zi[q] + zi[i])
                    if (e < best) {
                        best = e
                        j = q
                    }
                }
            } else {
                for (q in 0 until np) {
                    if (!used[q] && kotlin.math.abs(zi[q]) <= 1e-12) {
                        j = q
                        break
                    }
                }
            }
            val o = sections * 5
            if (j >= 0) {
                used[j] = true
                out[o + 3] = -(zr[i] + zr[j])
                out[o + 4] = zr[i] * zr[j] - zi[i] * zi[j]
                when (kind) {
                    Kind.LOW -> set3(out, o, 1.0, 2.0, 1.0)
                    Kind.HIGH -> set3(out, o, 1.0, -2.0, 1.0)
                    Kind.BAND -> set3(out, o, 1.0, 0.0, -1.0)
                }
            } else {
                out[o + 3] = -zr[i]
                out[o + 4] = 0.0
                when (kind) {
                    Kind.LOW -> set3(out, o, 1.0, 1.0, 0.0)
                    else -> set3(out, o, 1.0, -1.0, 0.0)
                }
            }
            sections++
        }
        for (i in 0 until 3) out[i] *= kd
        return sections
    }

    private fun set3(o: DoubleArray, off: Int, a: Double, b: Double, c: Double) {
        o[off] = a
        o[off + 1] = b
        o[off + 2] = c
    }

    /** H(e^{jω}) of one section at [hz] into [out] (re, im). */
    fun response(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double, hz: Double, fs: Int, out: DoubleArray) {
        val w = 2 * PI * hz / fs
        val c1 = StrictMath.cos(w)
        val s1 = -StrictMath.sin(w)
        val c2 = StrictMath.cos(2 * w)
        val s2 = -StrictMath.sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = b1 * s1 + b2 * s2
        val dr = 1 + a1 * c1 + a2 * c2
        val di = a1 * s1 + a2 * s2
        val d = dr * dr + di * di
        out[0] = (nr * dr + ni * di) / d
        out[1] = (ni * dr - nr * di) / d
    }

    /** |H| in dB of [sections] cascaded sections from [c]. */
    fun cascadeDb(c: DoubleArray, sections: Int, hz: Double, fs: Int): Double {
        val o = DoubleArray(2)
        var re = 1.0
        var im = 0.0
        for (s in 0 until sections) {
            response(c[s * 5], c[s * 5 + 1], c[s * 5 + 2], c[s * 5 + 3], c[s * 5 + 4], hz, fs, o)
            val t = re * o[0] - im * o[1]
            im = re * o[1] + im * o[0]
            re = t
        }
        return 10 * kotlin.math.log10(re * re + im * im)
    }
}
