package org.seion.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.ui.ambient.AmbientContrast
import org.seion.ui.ambient.AmbientRepository
import org.seion.ui.ambient.TextSampler

/**
 * Contrast property: for 200 random covers (seeded) the real renderer builds the layers, and every sample of what is drawn
 * behind the secondary text, with the solved scrim and far dimming, reaches 4.5:1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmbientRenderedContrastTest {
    private fun randomCover(rnd: Random): Bitmap {
        val n = 96
        val blocks = 2 + rnd.nextInt(7)
        val lift = rnd.nextInt(200) // bias towards light covers, the hard case
        val colours = IntArray(blocks * blocks) {
            val r = lift + rnd.nextInt(256 - lift)
            val g = lift + rnd.nextInt(256 - lift)
            val b = lift + rnd.nextInt(256 - lift)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val px = IntArray(n * n) { i -> colours[(i / n * blocks / n) * blocks + (i % n * blocks / n)] }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    @Test fun `every sample behind the secondary text reaches 4_5 to 1 on 200 random covers`() {
        val rnd = Random(20261108)
        val repo = AmbientRepository(org.seion.art.ArtRepository(resolveLocal = { null }))
        var worst = Double.MAX_VALUE
        repeat(200) { i ->
            val a = repo.build("c$i", randomCover(rnd)) ?: error("cover $i did not build")
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
            worst = minOf(worst, grey)
            assertTrue("cover $i grey $grey", grey >= AmbientContrast.TARGET)
        }
        println("Contrast property: worst grey contrast over 200 covers = $worst")
    }
}
