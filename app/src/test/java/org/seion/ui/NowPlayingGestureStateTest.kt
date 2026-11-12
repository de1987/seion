package org.seion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.ui.nowplaying.CoverSwipeState
import org.seion.ui.nowplaying.ambientIsFor
import org.seion.ui.nowplaying.sheetCollapses

/** The pure rules behind Now playing's gestures. */
class NowPlayingGestureStateTest {
    private fun dragged(id: String, art: String, dx: Float) = CoverSwipeState().apply {
        arrived(id, art)
        begin(id, art)
        draggable.dispatchRawDelta(dx)
    }

    @Test fun `the halo of a dragged cover stops following once the next track (same art) shows`() {
        val s = dragged("a", "album", -50f)
        assertEquals(-50f, s.offsetOfAmbient("album#1"), 0f)
        s.arrived("b", "album") // the next track of the same album: the same ambient, its cover centred
        assertEquals(0f, s.offsetOfAmbient("album#1"), 0f)
    }

    @Test fun `the halo of a dragged cover keeps following while another ambient takes over`() {
        val s = dragged("a", "art-a", -50f)
        s.arrived("b", "art-b") // the old ambient cross-fades out with its cover
        assertEquals(-50f, s.offsetOfAmbient("art-a#1"), 0f)
        assertEquals(0f, s.offsetOfAmbient("art-b#1"), 0f)
    }

    @Test fun `ambient keys match exactly, never by prefix`() {
        val s = dragged("a", "/music/x1", -50f)
        assertEquals(0f, s.offsetOfAmbient("/music/x12#1"), 0f)
        assertTrue(ambientIsFor("/music/x1#3", "/music/x1"))
        assertFalse(ambientIsFor("/music/x12#3", "/music/x1"))
    }

    @Test fun `a cover frozen by a new drag is centred again when it shows`() {
        val s = dragged("a", "art-a", -40f)
        s.arrived("b", "art-b")
        s.begin("b", "art-b") // a drag on the next cover freezes a where it was (it is fading out)
        assertEquals(-40f, s.offsetOf("a"), 0f)
        s.arrived("a", "art-a") // a becomes current again
        assertEquals(0f, s.offsetOf("a"), 0f)
        assertEquals(0f, s.offsetOfAmbient("art-a#1"), 0f)
    }

    @Test fun `no height known, no collapse`() {
        assertFalse(sheetCollapses(offset = 300f, velocity = 5000f, height = 0f))
        assertTrue(sheetCollapses(offset = 300f, velocity = 0f, height = 1000f))
    }
}
