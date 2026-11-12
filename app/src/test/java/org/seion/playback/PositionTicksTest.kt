package org.seion.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saving battery: the position ticks on whole seconds for the time, and as often as the bars move a pixel. */
class PositionTicksTest {
    @Test fun `the bar step follows one pixel of the track, between 250 ms and 1 s, on the second grid`() {
        assertEquals(500L, PositionTicks.stepMs(durationMs = 360_000, widthPx = 720)) // 500 ms per pixel
        assertEquals(250L, PositionTicks.stepMs(durationMs = 180_000, widthPx = 720)) // 250 ms per pixel
        assertEquals(250L, PositionTicks.stepMs(durationMs = 30_000, widthPx = 720)) // never faster than 250 ms
        assertEquals(1_000L, PositionTicks.stepMs(durationMs = 3_600_000, widthPx = 720)) // never slower than 1 s
        assertEquals(500L, PositionTicks.stepMs(durationMs = 600_000, widthPx = 720)) // 833 ms per pixel → 500
        assertEquals(1_000L, PositionTicks.stepMs(durationMs = 0, widthPx = 720)) // unknown length: the time only
        assertEquals(1_000L, PositionTicks.stepMs(durationMs = 200_000, widthPx = 0))
    }

    @Test fun `every step divides a second, so each whole second is a tick`() {
        for (d in listOf(10_000L, 90_000L, 200_000L, 400_000L, 1_000_000L, 10_000_000L)) {
            val step = PositionTicks.stepMs(d, 720)
            assertEquals("duration $d", 0L, 1_000L % step)
        }
    }

    @Test fun `the next tick comes just after the next boundary of the position, so the time never lags`() {
        val late = PositionTicks.LATE_MS
        assertTrue(late in 1L..30L)
        assertEquals(1_000L + late, PositionTicks.delayMs(positionMs = 12_000, stepMs = 1_000))
        assertEquals(1L + late, PositionTicks.delayMs(positionMs = 12_999, stepMs = 1_000))
        assertEquals(250L + late, PositionTicks.delayMs(positionMs = 12_250, stepMs = 500))
        assertEquals(100L + late, PositionTicks.delayMs(positionMs = 12_150, stepMs = 250))
        // A position a hair before the boundary (an early wake-up) is corrected right away, not a whole step later.
        assertTrue(PositionTicks.delayMs(positionMs = 12_997, stepMs = 1_000) < 50)
    }

    @Test fun `a tick landing after a boundary shows the new second`() {
        for (pos in listOf(0L, 400L, 999L, 61_234L)) {
            val step = 1_000L
            val next = pos + PositionTicks.delayMs(pos, step)
            assertEquals("from $pos", pos / 1_000 + 1, next / 1_000)
        }
    }
}
