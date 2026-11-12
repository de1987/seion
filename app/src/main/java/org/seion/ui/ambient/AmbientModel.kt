package org.seion.ui.ambient

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** An sRGB colour with 0..255 channels (doubles, so averages stay exact). */
data class Rgb(val r: Double, val g: Double, val b: Double) {
    fun scale(f: Double) = Rgb(r * f, g * f, b * f)

    fun mix(o: Rgb, t: Double) = Rgb(r * (1 - t) + o.r * t, g * (1 - t) + o.g * t, b * (1 - t) + o.b * t)

    fun clamp() = Rgb(r.coerceIn(0.0, 255.0), g.coerceIn(0.0, 255.0), b.coerceIn(0.0, 255.0))

    /** Android's `ColorMatrix.setToSaturation` (unclamped). */
    fun saturate(s: Double): Rgb {
        val ir = (1 - s) * 0.213
        val ig = (1 - s) * 0.715
        val ib = (1 - s) * 0.072
        return Rgb((ir + s) * r + ig * g + ib * b, ir * r + (ig + s) * g + ib * b, ir * r + ig * g + (ib + s) * b)
    }

    companion object {
        val WHITE = Rgb(255.0, 255.0, 255.0)
        val BLACK = Rgb(0.0, 0.0, 0.0)

        /** The secondary text grey (#AFAFAF) that must reach 4.5:1. */
        val GREY = Rgb(175.0, 175.0, 175.0)

        fun of(argb: Int) = Rgb(((argb shr 16) and 0xFF).toDouble(), ((argb shr 8) and 0xFF).toDouble(), (argb and 0xFF).toDouble())
    }
}

/** WCAG 2 relative luminance and contrast ratio. */
object Wcag {
    private fun channel(v: Double): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(c: Rgb): Double = 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b)

    fun contrast(a: Rgb, b: Rgb): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}

/** The numbers the contrast rule needs, measured once per cover on a small downscaled copy. */
data class ArtStats(
    /** Mean relative luminance of the whole cover, 0..1. */
    val meanLuminance: Double,
    /** Mean colour of the lower half (what ends up behind the text after blurring). */
    val lowerHalf: Rgb,
    val topRow: Rgb,
    val bottomRow: Rgb
) {
    companion object {
        fun of(pixels: IntArray, width: Int, height: Int): ArtStats {
            require(pixels.size >= width * height && width > 0 && height > 0)
            var lum = 0.0
            var lr = 0.0
            var lg = 0.0
            var lb = 0.0
            var lowN = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val c = Rgb.of(pixels[y * width + x])
                    lum += Wcag.luminance(c)
                    if (y >= height / 2) {
                        lr += c.r
                        lg += c.g
                        lb += c.b
                        lowN++
                    }
                }
            }
            fun row(y: Int): Rgb {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (x in 0 until width) {
                    val c = Rgb.of(pixels[y * width + x])
                    r += c.r
                    g += c.g
                    b += c.b
                }
                return Rgb(r / width, g / width, b / width)
            }
            return ArtStats(
                meanLuminance = lum / (width * height),
                lowerHalf = Rgb(lr / lowN, lg / lowN, lb / lowN),
                topRow = row(0),
                bottomRow = row(height - 1)
            )
        }
    }
}

/**
 * One measured colour behind the secondary text, split by layer so the scrim solve stays analytic:
 * [far] is the far layer after its saturation matrix at brightness 1 (unclamped), [near] the near layer's
 * premultiplied contribution (already dimmed with the cover), [nearAlpha] its coverage, [scrimProfile] the scrim's
 * alpha per unit strength at this row, and [fixedDim] what the vignette keeps. The scrim is composited above every
 * sampled layer.
 */
data class TextSample(
    val far: Rgb,
    val near: Rgb = Rgb.BLACK,
    val nearAlpha: Double = 0.0,
    val scrimProfile: Double,
    val fixedDim: Double = 1.0
) {
    fun drawn(farBrightness: Double, scrim: Double): Rgb {
        val f = far.scale(farBrightness).clamp().scale(1 - nearAlpha)
        val c = Rgb(f.r + near.r, f.g + near.g, f.b + near.b).clamp()
        return c.scale((1 - scrimProfile * scrim).coerceIn(0.0, 1.0) * fixedDim)
    }
}

/** The adaptive values the ambient layers are drawn with. */
data class AmbientParams(
    /** Far (blurred, spread) layer brightness. */
    val farBrightness: Float,
    val saturation: Float,
    /** Overall scrim strength; multiplies the vertical scrim profile. */
    val scrim: Float,
    /** Status-bar band opacity. */
    val statusScrim: Float,
    /** Cover + edge-clamp halo brightness (dimmed together for bright covers so the seam stays invisible). */
    val coverBrightness: Float,
    val titleContrast: Double,
    val greyContrast: Double,
    /** Width of the cover's feathered edge as a fraction of its size (per side). */
    val feather: Float = 0.09f
) {
    companion object {
        val DEFAULT = AmbientParams(0.7f, 1.5f, 0.8f, 0.34f, 1f, 15.0, 6.0)
    }
}

/**
 * The adaptive contrast rule, pure and unit-tested: the background adapts, never the text colour. It solves on
 * samples of what is really drawn behind the secondary text ([TextSampler]: far layer through its drift range, near
 * layer through its mask, under the scrim profile) instead of a model of it: a simple model that assumed a scrim
 * factor of 0.53 at the secondary rows, where the profile gives 0.46–0.49, let light covers read 4.0–4.4:1 on screen
 * while it said 4.5.
 */
object AmbientContrast {
    const val TARGET = 4.5

    /** The solve aims 0.1 above [TARGET] (rounding margin on screen). */
    const val SOLVE_TARGET = 4.6
    private const val SATURATION = 1.5

    /**
     * Bright art dims the cover and its halo together (floor 0.55, deep enough that pastel and
     * white covers don't read as a lighter box).
     */
    fun coverBrightness(meanLuminance: Double): Double = if (meanLuminance > 0.35) max(0.55, 1 - (meanLuminance - 0.35) * 1.0) else 1.0

    /** The feathered edge widens from 9 % up to 18 % on bright art. */
    fun featherWidth(meanLuminance: Double): Double {
        val t = ((meanLuminance - 0.45) / 0.35).coerceIn(0.0, 1.0)
        return 0.09 + 0.09 * t * t * (3 - 2 * t)
    }

    /** The look a cover frame carries from its own art: the same dimming and feather the ambient uses. */
    fun coverLook(meanLuminance: Double): CoverLook =
        CoverLook(coverBrightness(meanLuminance).toFloat(), feather = true, featherWidth = featherWidth(meanLuminance).toFloat())

    /** The worst contrast of the grey secondary text over [samples] drawn with these values. */
    fun greyContrast(samples: List<TextSample>, farBrightness: Double, scrim: Double): Double =
        samples.minOf { Wcag.contrast(Rgb.GREY, it.drawn(farBrightness, scrim)) }

    /**
     * Solves the scrim (then the far-layer dimming) on [samples] measured from what is drawn behind the secondary text
     * raise the scrim in 0.05 steps until the worst sample reaches [SOLVE_TARGET], then lower the far layer.
     * The far-dimming loop only rescales the far part of each sample. [s] still gives the cover dimming, the feather
     * and the status band. [saturation] is the far layer's colour matrix the samples were measured with; [farStart]
     * the far-layer brightness the solve starts from (the no-cover ambient starts at 1: its field is already dark).
     */
    fun compute(
        s: ArtStats,
        samples: List<TextSample>,
        saturation: Double = SATURATION,
        farStart: Double = (0.88 - 0.4 * s.meanLuminance).coerceIn(0.58, 0.88)
    ): AmbientParams {
        val l = s.meanLuminance
        var far = farStart
        val near = coverBrightness(l)
        val status = min(0.8, 0.24 + 0.7 * Wcag.luminance(s.topRow) * near + 0.15 * l)

        fun solve(farB: Double): Pair<Double, Double> {
            var scrim = 0.55
            var grey: Double
            while (true) {
                grey = greyContrast(samples, farB, scrim)
                if (grey >= SOLVE_TARGET || scrim >= 1.0 - 1e-9) break
                scrim = min(1.0, scrim + 0.05)
            }
            return scrim to grey
        }

        var (scrim, grey) = solve(far)
        while (grey < SOLVE_TARGET && far > 0.4 + 1e-9) {
            far = max(0.4, far - 0.03)
            val r = solve(far)
            scrim = r.first
            grey = r.second
        }
        val title = samples.minOf { Wcag.contrast(Rgb.WHITE, it.drawn(far, scrim)) }
        return AmbientParams(
            farBrightness = far.toFloat(),
            saturation = saturation.toFloat(),
            scrim = scrim.toFloat(),
            statusScrim = status.toFloat(),
            coverBrightness = near.toFloat(),
            feather = featherWidth(l).toFloat(),
            titleContrast = title,
            greyContrast = grey
        )
    }

    /** The rule for a cover whose layers were not sampled: its lower half as a uniform colour. */
    fun compute(s: ArtStats): AmbientParams = compute(s, uniformSamples(s.lowerHalf, coverBrightness(s.meanLuminance)))

    /** Samples of a uniform cover colour (tests, and the fallback when the layers could not be sampled). */
    fun uniformSamples(c: Rgb, coverBrightness: Double): List<TextSample> = TextSampler.SECONDARY_ROWS_DP.map { y ->
        val a = 0.25
        TextSample(
            far = c.saturate(SATURATION),
            near = c.scale(coverBrightness * a),
            nearAlpha = a,
            scrimProfile = TextSampler.scrimProfile(y)
        )
    }
}

/**
 * Where the secondary text sits on the 360 × 640 dp reference layout (cover 248 dp at (56, 90),
 * artist baseline 416.6, album 446.8) and how each layer maps there, so [AmbientContrast] solves on what is drawn.
 */
object TextSampler {
    private const val SCREEN_W = 360.0
    private const val SCREEN_H = 640.0
    private const val COVER_LEFT = 56.0
    private const val COVER_TOP = 90.0
    private const val COVER_SIZE = 248.0

    /** Three rows through each secondary line's glyph box (baseline − 11, − 4, + 3 dp). */
    val SECONDARY_ROWS_DP: List<Double> = listOf(416.6, 446.8).flatMap { b -> listOf(b - 11, b - 4, b + 3) }

    /** 16 points across the text width (20 dp padding each side). */
    val TEXT_X_DP: List<Double> = List(16) { 24.0 + it * (312.0 / 15) }

    /** The drift's range (phase 0..1: scale 1..1.08, rotation −3..3°, translation −12..12 dp); worst case over it. */
    val DRIFT_PHASES = listOf(0.0, 0.25, 0.5, 0.75, 1.0)

    private val SCRIM_STOPS = listOf(0.0 to 0.0, 0.47 to 0.0, 0.5625 to 0.40, 0.734 to 0.52, 0.89 to 0.66, 1.0 to 0.78)

    /** The scrim's alpha per unit strength at [yDp] (the vertical profile drawn by the scrim). */
    fun scrimProfile(yDp: Double): Double {
        val f = (yDp / SCREEN_H).coerceIn(0.0, 1.0)
        for (i in 1 until SCRIM_STOPS.size) {
            val (a, x) = SCRIM_STOPS[i - 1]
            val (b, y) = SCRIM_STOPS[i]
            if (f <= b) return x + (y - x) * (f - a) / (b - a)
        }
        return SCRIM_STOPS.last().second
    }

    /** What the fixed vignette (0 → 0.35 alpha from 55 % to 100 % of 0.75 × the screen height) keeps at a point. */
    fun vignetteKeep(xDp: Double, yDp: Double): Double {
        val d = kotlin.math.hypot(xDp - SCREEN_W / 2, yDp - SCREEN_H * 0.42) / (SCREEN_H * 0.75)
        val a = if (d <= 0.55) 0.0 else 0.35 * ((d - 0.55) / 0.45).coerceAtMost(1.0)
        return 1 - a
    }

    /**
     * Samples behind the secondary text from the far layer ([farPx], ARGB, [farW] × [farH], drawn over
     * [FarLayer.OVERSIZE_X] × [FarLayer.OVERSIZE_Y] the screen, centred, drifting) and the near layer ([nearPx],
     * ARGB with alpha, drawn over the cover ± [NearLayer.MARGIN] × its size, dimmed by [coverBrightness]).
     */
    fun samples(
        farPx: IntArray,
        farW: Int,
        farH: Int,
        nearPx: IntArray,
        nearW: Int,
        nearH: Int,
        saturation: Double,
        coverBrightness: Double
    ): List<TextSample> {
        val farWd = SCREEN_W * FarLayer.OVERSIZE_X
        val farHd = SCREEN_H * FarLayer.OVERSIZE_Y
        val m = NearLayer.MARGIN.toDouble()
        val nearLeft = COVER_LEFT - COVER_SIZE * m
        val nearTop = COVER_TOP - COVER_SIZE * m
        val nearSize = COVER_SIZE * (1 + 2 * m)
        val out = ArrayList<TextSample>(DRIFT_PHASES.size * SECONDARY_ROWS_DP.size * TEXT_X_DP.size)
        for (phase in DRIFT_PHASES) {
            val scale = 1 + 0.08 * phase
            val rot = Math.toRadians(-3 + 6 * phase)
            val ty = (phase - 0.5) * 24
            val cos = kotlin.math.cos(-rot)
            val sin = kotlin.math.sin(-rot)
            for (y in SECONDARY_ROWS_DP) {
                val profile = scrimProfile(y)
                for (x in TEXT_X_DP) {
                    // Inverse of the far layer's graphicsLayer (scale, rotate about its centre, then translate).
                    val dx = x - SCREEN_W / 2
                    val dy = y - SCREEN_H / 2 - ty
                    val qx = (dx * cos - dy * sin) / scale
                    val qy = (dx * sin + dy * cos) / scale
                    val far = bilinear(farPx, farW, farH, qx / farWd + 0.5, qy / farHd + 0.5)
                    val near = bilinear(nearPx, nearW, nearH, (x - nearLeft) / nearSize, (y - nearTop) / nearSize)
                    val alpha = near[3] / 255.0
                    out +=
                        TextSample(
                            far = Rgb(far[0], far[1], far[2]).saturate(saturation),
                            near = Rgb(near[0], near[1], near[2]).scale(coverBrightness * alpha),
                            nearAlpha = alpha,
                            scrimProfile = profile,
                            fixedDim = vignetteKeep(x, y)
                        )
                }
            }
        }
        return out
    }

    /** Bilinear RGBA at normalised (u, v), edge-clamped; channels 0..255 (alpha in [3]). */
    private fun bilinear(px: IntArray, w: Int, h: Int, u: Double, v: Double): DoubleArray {
        val fx = (u * w - 0.5).coerceIn(0.0, w - 1.0)
        val fy = (v * h - 0.5).coerceIn(0.0, h - 1.0)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0
        val ty = fy - y0
        val out = DoubleArray(4)
        for (c in 0 until 4) {
            val shift = if (c == 3) 24 else 16 - 8 * c
            fun ch(i: Int) = ((px[i] ushr shift) and 0xFF).toDouble()
            val top = ch(y0 * w + x0) * (1 - tx) + ch(y0 * w + x1) * tx
            val bottom = ch(y1 * w + x0) * (1 - tx) + ch(y1 * w + x1) * tx
            out[c] = top * (1 - ty) + bottom * ty
        }
        return out
    }
}

/**
 * "Classic" background: one vertical gradient from the cover's base colour to black, with a fixed
 * profile: base at the top, ×0.64 at 30 %, ×0.45 at 52 %, ×0.25 at 71 %, ×0.06 at 86 %, black from 93 %.
 */
object ClassicGradient {
    val STOPS: List<Pair<Float, Double>> =
        listOf(0f to 1.0, 0.30f to 0.64, 0.52f to 0.45, 0.71f to 0.25, 0.86f to 0.06, 0.93f to 0.0, 1f to 0.0)

    /** The base colour: the swatch's hue and saturation with lightness clamped to 0.22..0.38 (HSL). */
    fun baseColour(swatch: Rgb): Rgb {
        val (h, s, l) = toHsl(swatch)
        return fromHsl(h, s, l.coerceIn(0.22, 0.38))
    }

    fun toHsl(c: Rgb): Triple<Double, Double, Double> {
        val r = c.r / 255
        val g = c.g / 255
        val b = c.b / 255
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        if (mx == mn) return Triple(0.0, 0.0, l)
        val d = mx - mn
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        val h =
            when (mx) {
                r -> ((g - b) / d + (if (g < b) 6 else 0))
                g -> ((b - r) / d + 2)
                else -> ((r - g) / d + 4)
            } / 6
        return Triple(h, s, l)
    }

    fun fromHsl(h: Double, s: Double, l: Double): Rgb {
        if (s == 0.0) return Rgb(l * 255, l * 255, l * 255)
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Double): Double {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        return Rgb(hue(h + 1.0 / 3) * 255, hue(h) * 255, hue(h - 1.0 / 3) * 255)
    }
}

/** How one cover frame is drawn: its dimming and feathered-edge width. */
data class CoverLook(val brightness: Float, val feather: Boolean, val featherWidth: Float) {
    companion object {
        val PLAIN = CoverLook(1f, feather = false, featherWidth = 0.09f)
    }
}

/** Which look a cover frame is drawn with, pure so the swap rule is unit-tested. */
object CoverLookPolicy {
    /**
     * [frameRefKey] is the frame's art key, [ambientKey] / [ambientParams] the ambient currently shown (which may
     * already belong to the next track while this frame animates out).
     */
    fun lookFor(
        frameRefKey: String?,
        frameLook: CoverLook?,
        ambientKey: String?,
        ambientParams: AmbientParams?,
        ambientStyle: Boolean
    ): CoverLook {
        if (!ambientStyle) return CoverLook.PLAIN
        // The look captured with the frame (computed from its own bitmap before it was published) is never read
        // from another track's ambient: both the outgoing and the incoming cover are right from their first frame.
        if (frameLook != null) return frameLook
        val params = ambientParams?.takeIf { frameRefKey != null && ambientKey != null && ambientKey.startsWith(frameRefKey) }
        return CoverLook(
            brightness = if (ambientStyle) params?.coverBrightness ?: 1f else 1f,
            feather = ambientStyle,
            featherWidth = params?.feather ?: 0.09f
        )
    }
}
