package org.seion.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.art.ArtRef
import org.seion.ui.ambient.Ambient
import org.seion.ui.ambient.AmbientParams
import org.seion.ui.ambient.AmbientRepository
import org.seion.ui.components.NoCoverColors

/** Every track without art shares one no-cover ambient, rendered once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AmbientNoCoverTest {
    @Test fun sharedKey() = runTest {
        val tile = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val renders = mutableListOf<String>()
        val img = tile.asImageBitmap()
        val repo =
            AmbientRepository(
                loadArt = { _, _ -> null },
                generation = { 0 },
                renderer = { key, _ -> error("a track without art is never rendered as a cover ($key)") },
                noCoverBuilder = {
                    renders += AmbientRepository.NO_COVER_KEY
                    Ambient(
                        AmbientRepository.NO_COVER_KEY,
                        img,
                        img,
                        img,
                        AmbientParams.DEFAULT,
                        NoCoverColors.Tile,
                        NoCoverColors.Tile,
                        NoCoverColors.Tile
                    )
                }
            )
        val a = repo.load(ArtRef("/m/x/1.mp3", "/m/x", null, null))!!
        val b = repo.load(ArtRef("/m/y/2.mp3", "/m/y", "Artist", "Album"))!!
        assertSame(a, b)
        assertEquals(listOf(AmbientRepository.NO_COVER_KEY), renders)
        // The ink field with a soft top glow; an ambient cached under an older key is never reused.
        assertEquals("no-cover:v3", a.key)
        assertEquals(NoCoverColors.Accent, a.accent)
        assertEquals(NoCoverColors.ClassicBase, a.classicBase)
    }
}
