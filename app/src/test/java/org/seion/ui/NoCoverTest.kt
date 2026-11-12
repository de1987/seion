package org.seion.ui

import android.app.Application
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.R
import org.seion.ui.ambient.AmbientContrast
import org.seion.ui.ambient.AmbientRepository
import org.seion.ui.ambient.ClassicGradient
import org.seion.ui.ambient.Rgb
import org.seion.ui.ambient.TextSampler
import org.seion.ui.components.NoCoverArt
import org.seion.ui.components.NoCoverColors
import org.seion.ui.components.NoCoverTile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoCoverTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun glyphPathParses() {
        val d = ContextCompat.getDrawable(context, R.drawable.ic_no_cover_glyph)
        assertNotNull(d)
        val dp24 = (24 * context.resources.displayMetrics.density).toInt()
        assertEquals(dp24, d!!.intrinsicWidth)
        assertEquals(dp24, d.intrinsicHeight)
    }

    @Test fun coloursAreTheLauncherIcons() {
        // The launcher disc's gradient stops (now the mark's) and its ink tile (also the splash icon background).
        assertEquals(0xFFFFC453.toInt(), NoCoverColors.Amber.toArgb())
        assertEquals(0xFFFF7F5C.toInt(), NoCoverColors.Coral.toArgb())
        assertEquals(context.getColor(R.color.seion_icon_tile), NoCoverColors.Tile.toArgb())
        // Both stops stay far above 4.5:1 on the tile (7.21:1 and 11.33:1).
        assertTrue(contrast(NoCoverColors.Coral.toArgb(), NoCoverColors.Tile.toArgb()) >= 7.0)
        assertTrue(contrast(NoCoverColors.Amber.toArgb(), NoCoverColors.Tile.toArgb()) >= 11.0)
    }

    @Test fun paletteFollowsTheCoverRulesForTheGradientMidpoint() {
        val amber = Rgb.of(NoCoverColors.Amber.toArgb())
        val coral = Rgb.of(NoCoverColors.Coral.toArgb())
        assertClose(amber.mix(coral, 0.5), NoCoverColors.Mid.toArgb())
        val mid = Rgb.of(NoCoverColors.Mid.toArgb())
        assertColour(AmbientRepository.accentFrom(mid).toArgb(), NoCoverColors.Accent.toArgb())
        assertClose(ClassicGradient.baseColour(mid), NoCoverColors.ClassicBase.toArgb())
    }

    @Test fun bitmapIsTheInkTileWithTheGradientMark() {
        val a = NoCoverArt.bitmap(context, 512)
        assertSame(a, NoCoverArt.bitmap(context, 512))
        val w = a.width
        val px = IntArray(w * a.height).also { a.getPixels(it, 0, w, 0, 0, w, a.height) }
        assertTileWithGradientMark(px, w, a.height)
    }

    @Test fun composeTileIsTheInkTileWithTheGradientMark() {
        compose.setContent { NoCoverTile(160.dp) }
        compose.waitForIdle()
        val img = compose.onRoot().captureToImage()
        val px = IntArray(img.width * img.height).also { img.readPixels(it) }
        assertTileWithGradientMark(px, img.width, img.height)
    }

    /**
     * The tile is the launcher ink edge to edge (no border); the mark is drawn in the amber-to-coral gradient, never
     * tinted: its top-left pixels are close to amber, its bottom-right pixels close to coral, and every solid mark pixel
     * keeps at least 4.5:1 against the ink.
     */
    private fun assertTileWithGradientMark(px: IntArray, w: Int, h: Int) {
        val tile = NoCoverColors.Tile.toArgb()
        for (i in listOf(0, w - 1, (h - 1) * w, px.size - 1, w * (h / 2) + 1)) assertColour(tile, px[i])
        // Mark pixels are far from the ink; solid ones (all four neighbours in the mark too) exclude anti-aliased edges.
        val inMark = BooleanArray(px.size) { lum(px[it]) > 0.3 }
        val markCount = inMark.count { it }
        assertTrue("mark pixels $markCount", markCount > px.size / 50)
        px.indices.filter { inMark[it] }.forEach { assertTrue("mark pixel contrast", contrast(px[it], tile) >= 4.5) }
        val mark = px.indices.filter { i ->
            val x = i % w
            val y = i / w
            x in 1 until w - 1 && y in 1 until h - 1 && inMark[i] && inMark[i - 1] && inMark[i + 1] && inMark[i - w] && inMark[i + w]
        }
        val first = mark.minBy { it % w + it / w }
        val last = mark.maxBy { it % w + it / w }
        assertColour(NoCoverColors.Amber.toArgb(), px[first], tolerance = 24)
        assertColour(NoCoverColors.Coral.toArgb(), px[last], tolerance = 24)
        // Warm, not a flat tint: the green channel falls from the amber end to the coral end.
        assertTrue(Color.green(px[first]) - Color.green(px[last]) > 30)
    }

    @Test fun noCoverAmbientKeepsTheSecondaryTextAt4_5To1() {
        val repo = AmbientRepository(org.seion.art.ArtRepository(resolveLocal = { null }))
        val a = repo.buildNoCover() ?: error("no ambient")
        val far = a.far.asAndroidBitmap()
        val near = a.near.asAndroidBitmap()
        val farPx = IntArray(far.width * far.height).also { far.getPixels(it, 0, far.width, 0, 0, far.width, far.height) }
        val nearPx = IntArray(near.width * near.height).also { near.getPixels(it, 0, near.width, 0, 0, near.width, near.height) }
        val samples =
            TextSampler.samples(
                farPx,
                far.width,
                far.height,
                nearPx,
                near.width,
                near.height,
                a.params.saturation.toDouble(),
                a.params.coverBrightness.toDouble()
            )
        val grey = AmbientContrast.greyContrast(samples, a.params.farBrightness.toDouble(), a.params.scrim.toDouble())
        println("no-cover ambient: grey contrast $grey, title ${a.params.titleContrast}, scrim ${a.params.scrim}")
        assertTrue("grey $grey", grey >= AmbientContrast.TARGET)
        assertTrue("title ${a.params.titleContrast}", a.params.titleContrast >= AmbientContrast.TARGET)
    }

    private fun assertClose(expected: Rgb, actual: Int) {
        val e = Color.rgb(expected.r.roundToInt(), expected.g.roundToInt(), expected.b.roundToInt())
        assertColour(e, actual)
    }

    private fun assertColour(expected: Int, actual: Int, tolerance: Int = 2) {
        val ok =
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                abs(Color.blue(expected) - Color.blue(actual)) <= tolerance
        assertTrue("expected #%06X, got #%06X".format(expected and 0xFFFFFF, actual and 0xFFFFFF), ok)
    }

    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun lum(c: Int): Double {
        fun ch(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }
}
