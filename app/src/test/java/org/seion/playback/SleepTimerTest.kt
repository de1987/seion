package org.seion.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sleep timer's rules on a fake clock. */
class SleepTimerTest {
    private var now = 1_000_000L
    private val timer = SleepTimer { now }

    @Test fun `a countdown pauses at its deadline after a fade over the last 10 s`() {
        timer.start(30)
        assertTrue(timer.active)
        assertEquals(30 * 60_000L, timer.remainingMs())
        assertEquals(1f, timer.volume(), 0f)
        // Nothing to do until the fade starts.
        assertEquals(30 * 60_000L - SleepTimer.FADE_MS, timer.nextCheckMs())
        now += 30 * 60_000L - SleepTimer.FADE_MS
        assertEquals(1f, timer.volume(), 0f)
        now += 5_000
        assertEquals(0.25f, timer.volume(), 1e-6f) // half way: (1/2)²
        assertEquals(SleepTimer.FADE_TICK_MS, timer.nextCheckMs())
        assertFalse(timer.expired())
        now += 5_000
        assertTrue(timer.expired())
        assertEquals(0f, timer.volume(), 0f)
        assertEquals(0L, timer.remainingMs())
    }

    @Test fun `the fade only ever falls`() {
        timer.start(1)
        var last = 1f
        while (!timer.expired()) {
            val v = timer.volume()
            assertTrue(v <= last)
            last = v
            now += 50
        }
    }

    @Test fun `extend adds from the deadline, or from now when it is due, and lifts the fade`() {
        timer.start(15)
        now += 15 * 60_000L - 2_000 // 2 s left, fading
        assertTrue(timer.volume() < 0.1f)
        assertTrue(timer.extend(SleepTimer.EXTEND_MINUTES))
        assertEquals(10 * 60_000L + 2_000, timer.remainingMs())
        assertEquals(1f, timer.volume(), 0f)
        now += 20 * 60_000L // overdue (the service was busy): +10 counts from now
        assertTrue(timer.extend(10))
        assertEquals(10 * 60_000L, timer.remainingMs())
        // Capped at 12 h from now.
        assertTrue(timer.extend(SleepTimer.MAX_MINUTES))
        assertEquals(SleepTimer.MAX_MINUTES * 60_000L, timer.remainingMs())
    }

    @Test fun `end of song has no countdown and no fade, and extend refuses it`() {
        timer.startEndOfItem()
        assertTrue(timer.active)
        assertTrue(timer.endOfItem)
        assertEquals(-1L, timer.remainingMs())
        assertEquals(1f, timer.volume(), 0f)
        assertFalse(timer.expired())
        assertFalse(timer.extend(10))
        // A countdown replaces it, and the other way round.
        timer.start(45)
        assertFalse(timer.endOfItem)
        timer.startEndOfItem()
        assertEquals(SleepTimer.NONE, timer.deadline)
    }

    @Test fun `cancel turns everything off`() {
        timer.start(60)
        timer.cancel()
        assertFalse(timer.active)
        assertEquals(-1L, timer.remainingMs())
        assertEquals(-1L, timer.nextCheckMs())
        assertEquals(1f, timer.volume(), 0f)
        assertFalse(timer.extend(10))
    }

    @Test fun `command values are checked`() {
        for (ok in listOf(
            SleepTimer.OFF,
            SleepTimer.END_OF_SONG,
            1,
            15,
            90,
            SleepTimer.MAX_MINUTES
        )) {
            assertTrue("$ok", SleepTimer.validSet(ok))
        }
        for (bad in listOf(-2, SleepTimer.MAX_MINUTES + 1, Int.MIN_VALUE)) assertFalse("$bad", SleepTimer.validSet(bad))
        assertEquals(listOf(15, 30, 45, 60, 90), SleepTimer.PRESETS)
    }

    @Test fun `minutes left round up`() {
        assertEquals(30, SleepTimer.minutesLeft(30 * 60_000L))
        assertEquals(1, SleepTimer.minutesLeft(1))
        assertEquals(1, SleepTimer.minutesLeft(60_000))
        assertEquals(2, SleepTimer.minutesLeft(60_001))
        assertEquals(0, SleepTimer.minutesLeft(0))
    }
}
