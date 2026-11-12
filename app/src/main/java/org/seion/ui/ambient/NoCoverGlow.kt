package org.seion.ui.ambient

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.Random
import kotlin.math.roundToInt
import org.seion.ui.components.NoCoverColors

/**
 * Seion Infinite for a track without a cover: mostly black, with a soft warm light at the top of the screen.
 * The field is the no-cover tile's ink ([NoCoverColors.Tile], so the tile on Now playing sits in it without a seam);
 * the light is the midpoint of the mark's amber-to-coral gradient, laid over it with a top-anchored elliptical falloff
 * that peaks at [PEAK] and is gone before the middle of the screen. Nothing is hard-coded but the shape: the colours
 * are read from [NoCoverColors] at render time, so the glow follows the palette.
 *
 * Rendered once per process into bitmaps (never per frame), dithered so the gradient does not band.
 */
object NoCoverGlow {
    /** The light's opacity over the ink at its brightest (top centre). */
    const val PEAK = 0.38

    /** The ellipse's centre, a little above the top edge, and its radii (fractions of the area's width and height). */
    private const val CENTRE_Y = -0.05
    private const val RADIUS_X = 0.9
    private const val RADIUS_Y = 0.5

    /** The light's colour: the gradient's midpoint, as the palette's other derived colours use. */
    fun colour(amber: Color = NoCoverColors.Amber, coral: Color = NoCoverColors.Coral): Rgb =
        Rgb.of(amber.toArgb()).mix(Rgb.of(coral.toArgb()), 0.5)

    /**
     * The light's opacity at ([xf], [yf]), fractions of the area it covers (0 = left/top): [PEAK] × (1 − d²)², with d
     * the elliptical distance from the centre. Smooth at both ends (no ring, no hard edge) and zero from d = 1 on.
     */
    fun alpha(xf: Double, yf: Double): Double {
        val dx = (xf - 0.5) / RADIUS_X
        val dy = (yf - CENTRE_Y) / RADIUS_Y
        val d2 = dx * dx + dy * dy
        if (d2 >= 1.0) return 0.0
        val f = 1.0 - d2
        return PEAK * f * f
    }

    /**
     * The far layer: [FarLayer.OVERSIZE_X] × [FarLayer.OVERSIZE_Y] the screen, centred, so the light is placed in
     * screen fractions (it continues above the top edge, so the drift never uncovers a dark rim). [scale] × the far
     * layer's usual size: enough pixels that the upscale stays smooth.
     */
    fun far(
        scale: Int = FAR_SCALE,
        tile: Color = NoCoverColors.Tile,
        amber: Color = NoCoverColors.Amber,
        coral: Color = NoCoverColors.Coral
    ): Bitmap {
        val w = FarLayer.WIDTH_PX * scale
        val h = FarLayer.HEIGHT_PX * scale
        return render(w, h, tile, colour(amber, coral)) { x, y ->
            val xf = 0.5 + ((x + 0.5) / w - 0.5) * FarLayer.OVERSIZE_X
            val yf = 0.5 + ((y + 0.5) / h - 0.5) * FarLayer.OVERSIZE_Y
            alpha(xf, yf)
        }
    }

    /** The mini-player's backdrop: the same rule over the bar (its top edge glows, its lower part is ink). */
    fun mini(
        width: Int = MINI_W,
        height: Int = MINI_H,
        tile: Color = NoCoverColors.Tile,
        amber: Color = NoCoverColors.Amber,
        coral: Color = NoCoverColors.Coral
    ): Bitmap = render(width, height, tile, colour(amber, coral)) { x, y -> alpha((x + 0.5) / width, (y + 0.5) / height) }

    private const val FAR_SCALE = 4

    /** 360 × 64 dp at a quarter of xhdpi: the bar is drawn cropped to fill, so only the aspect matters. */
    private const val MINI_W = 180
    private const val MINI_H = 32

    private fun render(w: Int, h: Int, tile: Color, light: Rgb, alphaAt: (Int, Int) -> Double): Bitmap {
        val base = Rgb.of(tile.toArgb())
        // Triangular dither of ±1 level (seeded: the bitmap is the same every time), so 8-bit steps never band.
        val rnd = Random(DITHER_SEED)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = base.mix(light, alphaAt(x, y))
                fun ch(v: Double) = (v + rnd.nextDouble() - rnd.nextDouble()).roundToInt().coerceIn(0, 255)
                px[y * w + x] = (0xFF shl 24) or (ch(c.r) shl 16) or (ch(c.g) shl 8) or ch(c.b)
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private const val DITHER_SEED = 20261109L
}
