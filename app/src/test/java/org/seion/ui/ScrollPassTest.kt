package org.seion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.ui.components.ScrollGeometry
import org.seion.ui.components.ScrollPass
import org.seion.ui.components.ScrollPass.Phase

/**
 * One pass of a line that doesn't fit: wait at the start, roll left until the last
 * character reaches the inner edge of the right margin, hold, cross-fade back to the start and rest there. No loop.
 * Where the text sits (Now playing's equal margins, the mini-player's start-aligned rest) and the short fade on a
 * side where text is hidden.
 */
class ScrollPassTest {
    private val speed = 70f // px/s (35 dp/s at 2x)
    private val pass = ScrollPass(distance = 280f, speed = speed)
    private val delay = ScrollPass.START_DELAY_MS
    private val scrollEnd get() = delay + pass.scrollMs
    private val holdEnd get() = scrollEnd + ScrollPass.HOLD_MS
    private val fadeOutEnd get() = holdEnd + ScrollPass.FADE_OUT_MS

    @Test fun `a line that fits never moves`() {
        val fits = ScrollPass(distance = 0f, speed = speed)
        assertFalse(fits.moves)
        assertEquals(0L, fits.totalMs)
        for (t in listOf(0L, 1_000L, 5_000L, 60_000L)) {
            assertEquals(Phase.Static, fits.phaseAt(t))
            assertEquals(0f, fits.offsetAt(t))
            assertEquals(1f, fits.alphaAt(t))
        }
    }

    @Test fun `the pass waits at the start first`() {
        for (t in listOf(0L, 500L, delay - 1)) {
            assertEquals(Phase.Delay, pass.phaseAt(t))
            assertEquals(0f, pass.offsetAt(t))
            assertEquals(1f, pass.alphaAt(t))
        }
    }

    @Test fun `it scrolls forward at the cruising speed with soft ends`() {
        var last = 0f
        var t = delay
        while (t < scrollEnd) {
            assertEquals(Phase.Scroll, pass.phaseAt(t))
            val x = pass.offsetAt(t)
            assertTrue("offset went back at $t ms ($last -> $x)", x >= last)
            assertTrue(x <= pass.distance)
            assertEquals(1f, pass.alphaAt(t))
            last = x
            t += 10
        }
        // Mid-scroll it cruises at the given speed.
        val mid = (delay + pass.scrollMs / 2).toLong()
        assertEquals(speed, (pass.offsetAt(mid + 100) - pass.offsetAt(mid)) * 10f, 0.5f)
        // Soft start: the first 50 ms cover less than at full speed.
        assertTrue(pass.offsetAt(delay + 50) < speed * 0.05f)
        // The duration is the distance at the cruising speed plus one ramp.
        assertEquals((280f / speed) * 1000f + ScrollPass.RAMP_MS, pass.scrollMs, 1f)
    }

    @Test fun `it holds at the end, then fades out, jumps back and fades in`() {
        val holdMid = ((scrollEnd + holdEnd) / 2).toLong()
        assertEquals(Phase.Hold, pass.phaseAt(holdMid))
        assertEquals(pass.distance, pass.offsetAt(holdMid))
        assertEquals(1f, pass.alphaAt(holdMid))
        assertEquals(ScrollPass.HOLD_MS.toFloat(), holdEnd - scrollEnd, 0.01f)

        val out = (holdEnd + ScrollPass.FADE_OUT_MS / 2).toLong() + 1
        assertEquals(Phase.FadeOut, pass.phaseAt(out))
        assertEquals("still at the end while it fades out", pass.distance, pass.offsetAt(out))
        assertTrue(pass.alphaAt(out) in 0.3f..0.7f)

        val inMid = (fadeOutEnd + ScrollPass.FADE_IN_MS / 2).toLong() + 1
        assertEquals(Phase.FadeIn, pass.phaseAt(inMid))
        assertEquals("back at the start while it fades in", 0f, pass.offsetAt(inMid))
        assertTrue(pass.alphaAt(inMid) in 0.3f..0.7f)
        // The jump happens while it's invisible.
        assertEquals(0f, pass.alphaAt(fadeOutEnd.toLong() + 1), 0.02f)
    }

    @Test fun `then it rests at the start for good`() {
        assertEquals(ceilMs(fadeOutEnd + ScrollPass.FADE_IN_MS), pass.totalMs)
        for (t in listOf(pass.totalMs, pass.totalMs + 1, pass.totalMs + 60_000)) {
            assertEquals(Phase.Rest, pass.phaseAt(t))
            assertEquals(0f, pass.offsetAt(t))
            assertEquals(1f, pass.alphaAt(t))
        }
    }

    @Test fun `the total is delay, scroll, hold and the two fades`() {
        val expected = ScrollPass.START_DELAY_MS + pass.scrollMs + ScrollPass.HOLD_MS + ScrollPass.FADE_OUT_MS + ScrollPass.FADE_IN_MS
        assertEquals(expected, pass.totalMs.toFloat(), 1f)
    }

    // ---------------------------------------------------------------- where the text sits (ScrollGeometry)

    /** The ink's left edge at rest and its right edge at the end of the pass, in px from the line's left edge. */
    private fun inkAtRest(g: ScrollGeometry, inkLeft: Float) = g.startX + inkLeft

    private fun inkAtEnd(g: ScrollGeometry, inkRight: Float) = g.startX - g.distance + inkRight

    @Test fun `now playing's ink rests and holds at the inner edges of equal 13 percent margins`() {
        val line = 300f
        val margin = ScrollPass.gutter(line)
        assertEquals(39f, margin, 0.001f) // 13 %
        // A 500 px advance whose first glyph has a 3 px left side bearing and whose last has a 5 px right one (plus
        // letter spacing): the ink, not the advance box, meets the margins, so the two gaps are equal (both 0).
        val g = ScrollGeometry.margins(inkLeft = 3f, inkRight = 495f, lineWidth = line)
        assertEquals(margin, inkAtRest(g, 3f), 0.001f)
        assertEquals(line - margin, inkAtEnd(g, 495f), 0.001f)
        val restGap = inkAtRest(g, 3f) - margin
        val holdGap = (line - margin) - inkAtEnd(g, 495f)
        assertEquals(restGap, holdGap, 0.001f)
        val p = ScrollPass(g.distance, speed)
        assertEquals(g.distance, p.offsetAt((ScrollPass.START_DELAY_MS + p.scrollMs).toLong() + 1), 0f)
    }

    @Test fun `placing the advance box lets side bearings make the gaps differ`() {
        // The advance box at the gutters, with the same glyphs: 3 px on the left, 5 px on the right.
        val line = 300f
        val margin = ScrollPass.gutter(line)
        val oldRest = margin + 3f - margin
        val oldHold = (line - margin) - (margin - (500f - (line - 2 * margin)) + 495f)
        assertEquals(2f, oldHold - oldRest, 0.001f)
        // The ink rule removes the difference.
        val g = ScrollGeometry.margins(3f, 495f, line)
        assertEquals(0f, ((line - margin) - inkAtEnd(g, 495f)) - (inkAtRest(g, 3f) - margin), 0.001f)
    }

    @Test fun `a glyph that reaches left of its origin still rests at the margin`() {
        // E.g. an italic or a "j": the ink starts 2 px left of the origin.
        val g = ScrollGeometry.margins(inkLeft = -2f, inkRight = 410f, lineWidth = 300f)
        assertEquals(ScrollPass.gutter(300f), inkAtRest(g, -2f), 0.001f)
        assertEquals(300f - ScrollPass.gutter(300f), inkAtEnd(g, 410f), 0.001f)
    }

    @Test fun `a text only slightly too long still keeps the margins and moves a little`() {
        val g = ScrollGeometry.margins(inkLeft = 0f, inkRight = 301f, lineWidth = 300f)
        assertEquals(301f - 300f * (1 - 2 * ScrollPass.GUTTER), g.distance, 0.001f)
        val p = ScrollPass(g.distance, speed)
        assertTrue(p.moves)
        // Too short to reach full speed: it still ends exactly at the distance, never past it.
        var t = 0L
        while (t < p.totalMs) {
            assertTrue(p.offsetAt(t) <= g.distance + 0.0001f)
            t += 5
        }
        assertEquals(g.distance, p.offsetAt((ScrollPass.START_DELAY_MS + p.scrollMs).toLong() + 1), 0f)
    }

    @Test fun `the mini-player's line rests at the start and holds at the inner edge of the right gutter`() {
        val line = 300f
        val g = ScrollGeometry.startAligned(textWidth = 500f, lineWidth = line)
        // At rest the text starts where a line that fits starts: the column start, no left gutter.
        assertEquals(0f, g.startX, 0f)
        assertEquals(line - ScrollPass.gutter(line), g.startX - g.distance + 500f, 0.001f)
    }

    // ---------------------------------------------------------------- the short fade on a hidden side

    @Test fun `a side's fade grows with the text hidden past it`() {
        val f = ScrollPass.edgeFade(300f)
        assertEquals(18f, f, 0.001f) // 6 %
        assertEquals("nothing hidden: no fade", 0f, ScrollPass.fadeStrength(0f, f), 0f)
        assertEquals(0.5f, ScrollPass.fadeStrength(9f, f), 0.001f)
        assertEquals(1f, ScrollPass.fadeStrength(f, f), 0f)
        assertEquals(1f, ScrollPass.fadeStrength(200f, f), 0f)
        assertEquals(0f, ScrollPass.fadeStrength(-3f, f), 0f)
    }

    @Test fun `over a pass the left fade appears and the right one goes, and both are back at rest`() {
        val g = ScrollGeometry.margins(0f, 500f, 300f)
        val p = ScrollPass(g.distance, speed)
        val f = ScrollPass.edgeFade(300f)
        fun left(t: Long) = ScrollPass.fadeStrength(p.offsetAt(t), f)
        fun right(t: Long) = ScrollPass.fadeStrength(g.distance - p.offsetAt(t), f)
        // At rest: text hidden only on the right.
        assertEquals(0f, left(0), 0f)
        assertEquals(1f, right(0), 0f)
        // Holding at the end: hidden only on the left.
        val hold = (ScrollPass.START_DELAY_MS + p.scrollMs).toLong() + 100
        assertEquals(Phase.Hold, p.phaseAt(hold))
        assertEquals(1f, left(hold), 0f)
        assertEquals(0f, right(hold), 0f)
        // Mid-scroll: both sides hidden.
        val mid = (ScrollPass.START_DELAY_MS + p.scrollMs / 2).toLong()
        assertEquals(1f, left(mid), 0f)
        assertEquals(1f, right(mid), 0f)
        // After the pass: back to the rest.
        assertEquals(0f, left(p.totalMs), 0f)
        assertEquals(1f, right(p.totalMs), 0f)
    }

    private fun ceilMs(ms: Float) = kotlin.math.ceil(ms).toLong()
}
