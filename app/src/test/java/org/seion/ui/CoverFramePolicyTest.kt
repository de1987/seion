package org.seion.ui

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.seion.art.ArtOrigin
import org.seion.art.ArtState
import org.seion.art.CoverFramePolicy
import org.seion.art.FrameChoice

/** The previous track's cover never stays longer than the 150 ms grace. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CoverFramePolicyTest {
    private val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    @Test fun `found within the grace shows the art`() {
        assertEquals(FrameChoice.Show(bmp), CoverFramePolicy.next("a", "b", ArtState.Found(bmp, ArtOrigin.EMBEDDED), 80))
    }

    @Test fun `loading past the grace shows the tile`() {
        assertEquals(FrameChoice.Show(null), CoverFramePolicy.next("a", "b", ArtState.Loading, 150))
    }

    @Test fun `missing shows the tile at once`() {
        assertEquals(FrameChoice.Show(null), CoverFramePolicy.next("a", "b", ArtState.Missing(true), 0))
        assertEquals(FrameChoice.Show(null), CoverFramePolicy.next("a", "b", ArtState.Missing(false), 0))
    }

    @Test fun `the previous track's frame is never kept past the grace`() {
        for (t in listOf(150L, 151L, 1_000L, 15_000L)) {
            assertEquals(FrameChoice.Show(null), CoverFramePolicy.next("a", "b", ArtState.Loading, t))
        }
        assertEquals(FrameChoice.KeepPrevious, CoverFramePolicy.next("a", "b", ArtState.Loading, 149))
        // The same track reloading (settings changed) keeps its own cover until the new state arrives.
        assertEquals(FrameChoice.KeepPrevious, CoverFramePolicy.next("a", "a", ArtState.Loading, 5_000))
    }
}
