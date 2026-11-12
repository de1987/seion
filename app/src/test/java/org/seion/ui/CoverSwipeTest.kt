package org.seion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.ui.nowplaying.CoverSkip
import org.seion.ui.nowplaying.coverSkip
import org.seion.ui.nowplaying.swipeAlpha

/**
 * A committed swipe must not leave the cover slot nearly empty for ~100 ms: a dragged cover faded to 35 %, with
 * the 80 ms cross-fade to the next cover multiplying that, would. The drag feedback stays subtle, so the slot is never empty.
 */
class CoverSwipeTest {
    @Test fun `a dragged cover stays mostly opaque`() {
        val cover = 496f
        for (dx in listOf(-cover * 2, -cover, -0.3f * cover, 0f, 0.3f * cover, cover, cover * 2)) {
            assertTrue("alpha at $dx", swipeAlpha(dx, cover) >= 0.85f)
        }
        assertEquals(1f, swipeAlpha(0f, cover), 0f)
        assertTrue(swipeAlpha(0.5f * cover, cover) < 1f) // still some feedback
    }

    @Test fun `a release skips past 30 percent or on a fling, never against the fling`() {
        val cover = 496f
        val fling = 1200f
        assertEquals(CoverSkip.NEXT, coverSkip(-0.31f * cover, 0f, cover, fling))
        assertEquals(CoverSkip.PREVIOUS, coverSkip(0.31f * cover, 0f, cover, fling))
        assertEquals(CoverSkip.NONE, coverSkip(-0.29f * cover, 0f, cover, fling))
        assertEquals(CoverSkip.NEXT, coverSkip(-20f, -2000f, cover, fling)) // a short flick
        assertEquals(CoverSkip.PREVIOUS, coverSkip(0f, 2000f, cover, fling))
        // Dragged far left, then flung back right: the finger changed its mind.
        assertEquals(CoverSkip.NONE, coverSkip(-0.5f * cover, 2000f, cover, fling))
    }
}
