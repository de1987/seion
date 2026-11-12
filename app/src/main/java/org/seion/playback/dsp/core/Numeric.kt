package org.seion.playback.dsp.core

/** xorshift32, seeded per configure: a deterministic random sequence. */
class Rng(seed: Int = 0x2545F491) {
    private var s = if (seed == 0) 0x2545F491 else seed

    fun seed(v: Int) {
        s = if (v == 0) 0x2545F491 else v
    }

    fun next(): Int {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        s = x
        return x
    }
}

/** 1024 unit phasors (cos, sin) from StrictMath, for the noise mix. */
object PhasorTable {
    const val SIZE = 1024
    val cos = FloatArray(SIZE) { StrictMath.cos(2 * Math.PI * it / SIZE).toFloat() }
    val sin = FloatArray(SIZE) { StrictMath.sin(2 * Math.PI * it / SIZE).toFloat() }
}

/**
 * Deterministic fast functions: identical on the JVM and ART (arithmetic and bit operations only).
 * - [tanh]: Padé [7/6], exactly odd, saturating at |x| ≥ 5.5; max abs error 9.7e-5 on [−6, 6].
 * - [log2] / [exp2]: exponent/mantissa split and a 5th-order polynomial; log2 abs error ≤ 1.5e-5, exp2 rel ≤ 1.1e-7.
 */
object FastMath {
    fun tanh(x: Double): Double {
        val a = kotlin.math.abs(x)
        if (a >= 5.5) return if (x > 0) 1.0 else -1.0
        val x2 = x * x
        val r = x * (135135 + x2 * (17325 + x2 * (378 + x2))) / (135135 + x2 * (62370 + x2 * (3150 + 28 * x2)))
        return r.coerceIn(-1.0, 1.0)
    }

    fun log2(x: Double): Double {
        val bits = java.lang.Double.doubleToRawLongBits(x)
        val e = ((bits ushr 52) and 0x7FF).toInt() - 1023
        val m = java.lang.Double.longBitsToDouble((bits and 0x000FFFFFFFFFFFFFL) or 0x3FF0000000000000L)
        val t = m - 1
        val p = ((((L5 * t + L4) * t + L3) * t + L2) * t + L1) * t + L0
        return e + p
    }

    fun exp2(x: Double): Double {
        val fl = kotlin.math.floor(x)
        val f = x - fl
        val p = ((((E5 * f + E4) * f + E3) * f + E2) * f + E1) * f + E0
        val e = fl.toInt().coerceIn(-1022, 1023)
        return p * java.lang.Double.longBitsToDouble((e + 1023).toLong() shl 52)
    }

    /** 10·log10(x) via [log2]. */
    fun db10(x: Double): Double = LOG10_2 * 10 * log2(x)

    /** 10^(db/20) via [exp2]. */
    fun fromDb20(db: Double): Double = exp2(db / 20 / LOG10_2)

    private const val LOG10_2 = 0.30102999566398120

    private const val L5 = 0.04392862784792689
    private const val L4 = -0.1898324465266344
    private const val L3 = 0.4115614823104316
    private const val L2 = -0.707253433574346
    private const val L1 = 1.4415920772065483
    private const val L0 = 1.4390929995722335e-05
    private const val E5 = 0.001895107290974383
    private const val E4 = 0.008946214666404847
    private const val E3 = 0.05586328265920048
    private const val E2 = 0.24014077009185347
    private const val E1 = 0.6931546200032968
    private const val E0 = 0.9999998957631361
}

/**
 * Peak per control block (32 samples) into a one-pole attack/release follower, a = exp(−32/(fs·τ)).
 */
class EnvFollower(private val block: Int = 32) {
    private var a = 0.0
    private var r = 0.0
    var value = 0.0
        private set

    fun configure(fs: Int, attackS: Double, releaseS: Double) {
        a = StrictMath.exp(-block / (fs * attackS))
        r = StrictMath.exp(-block / (fs * releaseS))
    }

    /** Feeds one block peak; returns the new envelope. */
    fun feed(peak: Double): Double {
        value = if (peak > value) a * value + (1 - a) * peak else r * value + (1 - r) * peak
        value = Denormal.flush(value)
        return value
    }

    fun reset() {
        value = 0.0
    }
}
