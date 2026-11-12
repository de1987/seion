package org.seion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.ui.nowplaying.spectrumBarLayout
import org.seion.ui.nowplaying.spectrumLayout

/** The spectrum band on the 360 × 640 reference: the cover shrinks 248 -> 200, the band takes (20, 296, 320 × 44). */
class SpectrumLayoutTest {
    private val eps = 1e-3f

    @Test fun `closed and open`() {
        val closed = spectrumLayout(0f)
        assertEquals(248f, closed.cover, eps)
        assertEquals(0f, closed.bandHeight, eps)
        val open = spectrumLayout(1f)
        assertEquals(200f, open.cover, eps)
        assertEquals(20f, open.bandLeft, eps)
        assertEquals(296f, open.bandTop, eps)
        assertEquals(320f, open.bandWidth, eps)
        assertEquals(44f, open.bandHeight, eps)
        // The cover keeps its top (90) and centre.
        assertEquals(90f, open.coverTop, eps)
        assertEquals(80f, open.coverLeft, eps)
        assertEquals(56f, closed.coverLeft, eps)
    }

    @Test fun `the title box never moves`() {
        for (i in 0..10) assertEquals(354f, spectrumLayout(i / 10f).titleTop, eps)
    }

    @Test fun `14 bars with the spec's bar and gap, centred in the band`() {
        val g = spectrumBarLayout(320f)
        assertEquals(14, g.count)
        assertEquals(320f / 24, g.pitch, eps)
        assertEquals(9.33f, g.bar, 0.01f)
        assertEquals(4f, g.pitch - g.bar, 0.01f)
        // The old outer five bars on each side are gone: 5 pitches + the 2 dp half-gap of margin left and right.
        assertEquals(5 * 320f / 24 + 2f, g.left, 0.01f)
        assertEquals(g.left, g.right(320f), eps)
    }

    @Test fun `the group stays centred in pixels on both test screens`() {
        // A 320 dp band at 2.0 px/dp; a 1080 px wide phone: (1080 / 2.625 − 40) dp at 2.625 px/dp.
        for (px in listOf(640f, (1080f / 2.625f - 40f) * 2.625f)) {
            val g = spectrumBarLayout(px)
            assertEquals(14, g.count)
            assertEquals("margins at $px px", g.left, g.right(px), 1f)
            assertTrue(g.left > 0f && g.x(g.count - 1) + g.bar < px)
            assertEquals("gap at $px px", px * 4f / 320f, g.pitch - g.bar, 0.5f)
        }
    }
}
