package org.seion.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.settings.BackgroundStyle
import org.seion.ui.ambient.Ambient
import org.seion.ui.ambient.AmbientParams
import org.seion.ui.ambient.CoverGeometry
import org.seion.ui.ambient.NearLayer
import org.seion.ui.ambient.NowPlayingBackground

/**
 * Seion Infinite must not show a blurred copy of the cover beside the cover. The near layer (the blurred cover with
 * its edge-clamped halo, 2.5 × the cover's size) laid out with `Modifier.size` is clamped to the screen width:
 * squeezed horizontally, its blurred cover lands beside the real one. The blurred
 * cover inside the near layer must sit exactly behind the cover, and the halo (the colour expansion) must still
 * surround the cover on every side.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h914dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmbientNearLayerPlacementTest {
    @get:Rule val compose = createComposeRule()

    private val density = 2f // xhdpi

    /** A near layer whose cover area is magenta and whose halo is cyan, laid out like the renderer's. */
    private fun markedNear(): Bitmap {
        val size = NearLayer.SIZE_PX
        val inset = (NearLayer.MARGIN * NearLayer.COVER_PX).toInt()
        val px = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            val inCover = x in inset until inset + NearLayer.COVER_PX && y in inset until inset + NearLayer.COVER_PX
            if (inCover) MAGENTA else CYAN
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun ambient(): Ambient {
        val black = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
        return Ambient(
            key = "marked@1",
            cover = black.asImageBitmap(),
            far = black.asImageBitmap(),
            near = markedNear().asImageBitmap(),
            params = AmbientParams.DEFAULT, // cover brightness 1: the near layer is drawn undimmed
            classicBase = Color.Black,
            accent = Color.White,
            tint = Color.Black
        )
    }

    private class Shot(val px: IntArray, val w: Int, val h: Int) {
        operator fun get(x: Int, y: Int) = px[y * w + x]
    }

    private fun render(width: Dp, height: Dp, g: CoverGeometry): Shot {
        val a = ambient()
        compose.setContent {
            Box(Modifier.size(width, height)) {
                NowPlayingBackground(a, BackgroundStyle.AMBIENT, g, drifting = false, statusBarHeight = 24.dp)
            }
        }
        compose.waitForIdle()
        val img = compose.onRoot().captureToImage()
        val px = IntArray(img.width * img.height)
        img.readPixels(px)
        return Shot(px, img.width, img.height)
    }

    private fun isMagenta(c: Int) = r(c) > 120 && b(c) > 120 && g(c) < 60

    private fun isCyan(c: Int) = g(c) > 100 && b(c) > 100 && r(c) < 60

    private fun check(width: Dp, height: Dp, g: CoverGeometry) {
        val shot = render(width, height, g)
        val wPx = (width.value * density).toInt()
        val hPx = (height.value * density).toInt()
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (y in 0 until hPx) {
            for (x in 0 until wPx) {
                if (isMagenta(shot[x, y])) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        val cl = (g.left.value * density).toInt()
        val ct = (g.top.value * density).toInt()
        val cs = (g.size.value * density).toInt()
        val got = "blurred cover drawn at x $left..$right, y $top..$bottom; cover at x $cl..${cl + cs}, y $ct..${ct + cs}"
        assertTrue(got, left != Int.MAX_VALUE)
        for ((actual, expected) in listOf(left to cl, right to cl + cs - 1, top to ct, bottom to ct + cs - 1)) {
            assertTrue(got, abs(actual - expected) <= 3)
        }
        // The halo (the colour expansion) still reaches out on every side of the cover.
        val mid = ct + cs / 2
        val cx = cl + cs / 2
        val gap = (10 * density).toInt()
        val sides = mapOf(
            "left" to (cl - gap to mid),
            "right" to (cl + cs + gap to mid),
            "above" to (cx to ct - gap),
            "below" to (cx to ct + cs + gap)
        )
        for ((side, p) in sides) assertTrue("no halo $side of the cover ($got)", isCyan(shot[p.first, p.second]))
    }

    @Test fun `on the 360 x 640 dp reference the blurred cover sits exactly behind the cover`() {
        check(360.dp, 640.dp, CoverGeometry(56.dp, 90.dp, 248.dp))
    }

    @Test fun `on a tall 411 dp wide phone the blurred cover sits exactly behind the cover`() {
        // A 1080 × 2400 phone at 420 dpi: the full-size cover is 283 dp, centred.
        check(411.dp, 914.dp, CoverGeometry(64.dp, 90.dp, 283.dp))
    }

    @Test fun `with the spectrum band the shrunk cover keeps its blurred cover behind it`() {
        check(411.dp, 914.dp, CoverGeometry(91.dp, 90.dp, 229.dp))
    }

    private companion object {
        const val MAGENTA = 0xFFFF00FF.toInt()
        const val CYAN = 0xFF00FFFF.toInt()

        fun r(c: Int) = (c shr 16) and 0xFF

        fun g(c: Int) = (c shr 8) and 0xFF

        fun b(c: Int) = c and 0xFF
    }
}
