package org.seion.ui

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.ui.motion.SpectrumPacing
import org.seion.ui.motion.SteppedMotion

/** To save battery, the drift, the level bars and the spectrum draw at a few steps per second, not every vsync. */
class SteppedMotionTest {
    @Test fun `steps land on the grid through the last position tick`() {
        assertEquals(100L, SteppedMotion.stepDelay(nowMs = 5_000, anchorMs = 5_000, stepMs = 100))
        assertEquals(1L, SteppedMotion.stepDelay(nowMs = 5_099, anchorMs = 5_000, stepMs = 100))
        assertEquals(95L, SteppedMotion.stepDelay(nowMs = 5_030, anchorMs = 4_000, stepMs = 125)) // 1030 = 8 × 125 + 30
        assertEquals(100L, SteppedMotion.stepDelay(nowMs = 4_900, anchorMs = 5_000, stepMs = 125)) // an anchor ahead
        assertEquals(40L, SteppedMotion.stepDelay(nowMs = 60, anchorMs = 0, stepMs = 100)) // no tick yet
    }

    @Test fun `the step rates stay in the approved ranges and share frames with the position ticks`() {
        assertTrue(1000.0 / SteppedMotion.DRIFT_STEP_MS in 8.0..15.0)
        assertTrue(1000.0 / SteppedMotion.LEVEL_STEP_MS in 8.0..10.0)
        // The bars and the drift share every tick (250 ms, 500 ms, 1 s): a short song's 250 ms ticks too.
        assertEquals(0L, org.seion.playback.PositionTicks.MIN_STEP_MS % SteppedMotion.LEVEL_STEP_MS)
        assertEquals(0L, org.seion.playback.PositionTicks.MIN_STEP_MS % SteppedMotion.DRIFT_STEP_MS)
    }

    @Test fun `the drift goes 0 to 1 over 38 s and back, like the old reversed loop`() {
        val half = SteppedMotion.DRIFT_HALF_PERIOD_MS
        assertEquals(38_000L, half)
        assertEquals(0f, SteppedMotion.driftPhase(0), 0f)
        assertEquals(0.5f, SteppedMotion.driftPhase(half / 2), 1e-6f)
        assertEquals(1f, SteppedMotion.driftPhase(half), 1e-6f)
        assertEquals(0.5f, SteppedMotion.driftPhase(half + half / 2), 1e-6f)
        assertEquals(0f, SteppedMotion.driftPhase(2 * half), 1e-6f)
        assertEquals(SteppedMotion.driftPhase(1234), SteppedMotion.driftPhase(2 * half + 1234), 1e-6f)
    }

    @Test fun `the drift is continuous, one step moves it by a tiny amount and never jumps`() {
        val step = SteppedMotion.DRIFT_STEP_MS
        var t = 0L
        var maxDelta = 0f
        while (t < 4 * SteppedMotion.DRIFT_HALF_PERIOD_MS) {
            maxDelta = maxOf(maxDelta, abs(SteppedMotion.driftPhase(t + step) - SteppedMotion.driftPhase(t)))
            t += step
        }
        assertTrue("largest step $maxDelta", maxDelta <= step.toFloat() / SteppedMotion.DRIFT_HALF_PERIOD_MS + 1e-6f)
    }

    @Test fun `the level bars keep their range and their three periods`() {
        for (bar in 0..2) {
            val half = SteppedMotion.levelHalfPeriodMs(bar)
            assertEquals(500L + 130L * bar, half)
            assertEquals(0.25f, SteppedMotion.levelHeight(0, bar), 1e-6f)
            assertEquals(1f, SteppedMotion.levelHeight(half, bar), 1e-6f)
            assertEquals(0.25f, SteppedMotion.levelHeight(2 * half, bar), 1e-6f)
            for (t in 0L..5_000L step 37) {
                val h = SteppedMotion.levelHeight(t, bar)
                assertTrue("bar $bar at $t: $h", h in 0.25f..1f)
            }
        }
        // Different periods: the bars don't move together.
        assertFalse(SteppedMotion.levelHeight(700, 0) == SteppedMotion.levelHeight(700, 1))
    }

    @Test fun `the spectrum draws 30 times a second at 60, 90 and 120 Hz`() {
        for (hz in listOf(60, 90, 120)) {
            val frameNs = 1_000_000_000L / hz
            var last = 0L
            var drawn = 0
            for (i in 1..hz * 10) {
                val now = 5_000_000_000L + i * frameNs
                if (SpectrumPacing.due(now, last)) {
                    drawn++
                    last = now
                }
            }
            assertEquals("$hz Hz", 300.0, drawn.toDouble(), 3.0)
        }
    }

    @Test fun `the spectrum's first frame draws at once and a late frame is never skipped`() {
        assertTrue(SpectrumPacing.due(1_000_000_000L, 0L))
        val last = 2_000_000_000L
        assertFalse(SpectrumPacing.due(last + 16_700_000L, last))
        assertTrue(SpectrumPacing.due(last + 31_000_000L, last)) // a jittered second frame at 60 Hz
        assertTrue(SpectrumPacing.due(last + 50_000_000L, last))
    }
}

class SeekBarMotionTest {
    private fun springs(fromMs: Long, toMs: Long, durationMs: Long, widthPx: Float = 640f) =
        org.seion.ui.nowplaying.SeekBarMotion.springs(fromMs, toMs, durationMs, widthPx)

    @Test fun `playback ticks snap, jumps glide`() {
        // A 250 ms tick on a 3-minute song, a 1 s tick on a 10-minute one: snap (one frame per tick).
        assertFalse(springs(54_000, 54_250, 180_000))
        assertFalse(springs(300_000, 301_000, 600_000))
        // A tap halfway along, a skip back to the start: the spring.
        assertTrue(springs(18_000, 90_000, 180_000))
        assertTrue(springs(144_000, 0, 180_000))
    }

    @Test fun `on a short song the ticks still snap`() {
        // A 20 s song: a 250 ms tick is 8 px of 640; an 8 s song: 20 px.
        assertFalse(springs(8_000, 8_250, 20_000))
        assertFalse(springs(3_000, 3_250, 8_000))
        assertFalse(springs(3_000, 4_010, 8_000)) // a 1 s tick, a little late
        // A tap a fifth of the way along springs whatever the length; so does a small tap backwards.
        assertTrue(springs(8_000, 12_000, 20_000))
        assertTrue(springs(8_000, 7_000, 20_000))
    }

    @Test fun `moves under a few pixels never glide`() {
        val px = org.seion.ui.nowplaying.SeekBarMotion.SPRING_MIN_PX
        assertFalse(springs(100_000, 100_000 - ((px - 1) / 640f * 600_000).toLong(), 600_000)) // a tiny step back
        assertTrue(springs(100_000, 100_000 - (3 * px / 640f * 600_000).toLong(), 600_000))
        assertFalse(springs(0, 90_000, 180_000, widthPx = 0f)) // not laid out yet
        assertFalse(springs(0, 90_000, 0)) // unknown length
    }
}
