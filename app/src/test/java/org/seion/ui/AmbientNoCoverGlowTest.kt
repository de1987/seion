package org.seion.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.ui.ambient.Ambient
import org.seion.ui.ambient.AmbientRepository
import org.seion.ui.ambient.FarLayer
import org.seion.ui.ambient.NoCoverGlow
import org.seion.ui.ambient.Rgb
import org.seion.ui.ambient.Wcag
import org.seion.ui.components.NoCoverColors

/**
 * Seion Infinite without a cover is mostly black, with a soft warm light at the top of the screen, never a
 * bright orange wash. Measured on what the far layer draws on screen (its colour matrix applied).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmbientNoCoverGlowTest {
    private fun noCover(): Ambient = runBlocking {
        AmbientRepository(org.seion.art.ArtRepository(resolveLocal = { null })).noCover()
    } ?: error("no ambient")

    /** The far layer's colour at a point of the screen (fractions), as drawn with the ambient's matrix. */
    private fun drawn(a: Ambient, xf: Double, yf: Double): Rgb {
        val far = a.far.asAndroidBitmap()
        val u = 0.5 + (xf - 0.5) / FarLayer.OVERSIZE_X
        val v = 0.5 + (yf - 0.5) / FarLayer.OVERSIZE_Y
        val px = far.getPixel((u * far.width).toInt().coerceIn(0, far.width - 1), (v * far.height).toInt().coerceIn(0, far.height - 1))
        return Rgb.of(px).saturate(a.params.saturation.toDouble()).scale(a.params.farBrightness.toDouble()).clamp()
    }

    private fun grid(from: Double, to: Double) = (0..10).map { from + (to - from) * it / 10 }

    @Test fun lowerHalfIsNearBlack() {
        val a = noCover()
        for (y in grid(0.5, 1.0)) {
            for (x in grid(0.0, 1.0)) {
                val l = Wcag.luminance(drawn(a, x, y))
                assertTrue("luminance $l at ($x, $y)", l < NEAR_BLACK)
            }
        }
    }

    @Test fun neverBright() {
        val a = noCover()
        for (y in grid(0.0, 1.0)) {
            for (x in grid(0.0, 1.0)) {
                val l = Wcag.luminance(drawn(a, x, y))
                assertTrue("luminance $l at ($x, $y)", l < MAX_LUMINANCE)
            }
        }
    }

    @Test fun topBandGlowsWarm() {
        val a = noCover()
        for (y in grid(0.02, 0.12)) {
            for (x in grid(0.35, 0.65)) {
                val c = drawn(a, x, y)
                assertTrue("luminance at ($x, $y)", Wcag.luminance(c) > GLOW_MIN)
                assertTrue("warm at ($x, $y): $c", c.r > c.g && c.g > c.b)
            }
        }
    }

    @Test fun glowIsTheNoCoverPalettesMidpointOverTheInk() {
        // The default palette: the light is amber-to-coral's midpoint (NoCoverColors.Mid), the field the tile's ink.
        assertGlowFollows(NoCoverGlow.far(scale = 1), NoCoverColors.Tile, NoCoverColors.Amber, NoCoverColors.Coral, far = true)
    }

    @Test fun glowFollowsAChangedPalette() {
        // Another palette (a cool blue pair on a different ink): the field and the light follow it, nothing is fixed.
        val tile = Color(0xFF101820)
        val amber = Color(0xFF53C4FF)
        val coral = Color(0xFF5C7FFF)
        assertGlowFollows(NoCoverGlow.far(scale = 1, tile = tile, amber = amber, coral = coral), tile, amber, coral, far = true)
        assertGlowFollows(NoCoverGlow.mini(tile = tile, amber = amber, coral = coral), tile, amber, coral, far = false)
    }

    @Test fun miniPlayerFollowsTheSameRule() {
        val a = noCover()
        val mini = a.mini.asAndroidBitmap()
        assertGlowFollows(mini, NoCoverColors.Tile, NoCoverColors.Amber, NoCoverColors.Coral, far = false)
        // Lower half of the bar: ink. Top edge: the warm light.
        for (y in mini.height / 2 until mini.height) {
            for (x in 0 until mini.width step 8) {
                assertTrue(Wcag.luminance(Rgb.of(mini.getPixel(x, y))) < NEAR_BLACK)
            }
        }
        val top = Rgb.of(mini.getPixel(mini.width / 2, 0))
        assertTrue("mini top $top", Wcag.luminance(top) > GLOW_MIN && top.r > top.g && top.g > top.b)
    }

    @Test fun coversKeepTheirFarLayerInTheMiniPlayer() {
        val repo = AmbientRepository(org.seion.art.ArtRepository(resolveLocal = { null }))
        val cover = Bitmap.createBitmap(IntArray(64 * 64) { 0xFF3366CC.toInt() }, 64, 64, Bitmap.Config.ARGB_8888)
        val a = repo.build("c", cover) ?: error("no ambient")
        assertSame(a.far, a.mini)
        assertTrue(a.near.width > 1)
    }

    @Test fun noCoverAmbientDrawsNoHaloAndNoExtraSaturation() {
        val a = noCover()
        val near = a.near.asAndroidBitmap()
        for (y in 0 until near.height) for (x in 0 until near.width) assertTrue(android.graphics.Color.alpha(near.getPixel(x, y)) == 0)
        assertTrue(a.params.saturation == 1f)
        assertTrue("far brightness ${a.params.farBrightness}", a.params.farBrightness == 1f)
    }

    /**
     * Every pixel of [bmp] is the [tile] plus some amount of the light, the midpoint of [amber] and [coral] (within the
     * ±1 dither); the light is clearly there at the top centre and absent at the bottom.
     */
    private fun assertGlowFollows(bmp: Bitmap, tile: Color, amber: Color, coral: Color, far: Boolean) {
        val base = Rgb.of(tile.toArgb())
        val light = Rgb.of(amber.toArgb()).mix(Rgb.of(coral.toArgb()), 0.5)
        val dir = Rgb(light.r - base.r, light.g - base.g, light.b - base.b)
        val norm = sqrt(dir.r * dir.r + dir.g * dir.g + dir.b * dir.b)
        var peak = 0.0
        for (y in 0 until bmp.height step 2) {
            for (x in 0 until bmp.width step 2) {
                val c = Rgb.of(bmp.getPixel(x, y))
                val d = Rgb(c.r - base.r, c.g - base.g, c.b - base.b)
                val t = (d.r * dir.r + d.g * dir.g + d.b * dir.b) / (norm * norm)
                peak = maxOf(peak, t)
                // The residual off the base→light line stays within the dither.
                val off = Rgb(d.r - dir.r * t, d.g - dir.g * t, d.b - dir.b * t)
                assertTrue("off the palette line at ($x, $y): $c", abs(off.r) < 2.5 && abs(off.g) < 2.5 && abs(off.b) < 2.5)
            }
        }
        assertTrue("peak $peak", abs(peak - NoCoverGlow.PEAK) < 0.03)
        // Screen bottom (the far layer's screen area ends at 82.7 % of its height): pure ink.
        val bottom = if (far) (bmp.height * (0.5 + 0.5 / FarLayer.OVERSIZE_Y)).toInt() - 1 else bmp.height - 1
        val c = Rgb.of(bmp.getPixel(bmp.width / 2, bottom))
        assertTrue("bottom $c", abs(c.r - base.r) <= 1.5 && abs(c.g - base.g) <= 1.5 && abs(c.b - base.b) <= 1.5)
    }

    private companion object {
        /** The ink tile #16171A is 0.008. */
        const val NEAR_BLACK = 0.012
        const val MAX_LUMINANCE = 0.12
        const val GLOW_MIN = 0.03
    }
}
