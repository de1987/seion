package org.seion.playback

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.settings.RepeatSetting

class QueueLogicTest {
    @Test fun `shuffle keeps the current track first and is a permutation`() {
        repeat(50) { seed ->
            val order = QueueLogic.currentFirstOrder(10, current = 6, random = Random(seed))
            assertEquals(6, order[0])
            assertEquals((0 until 10).toList(), order.sorted())
        }
    }

    @Test fun `shuffle actually shuffles the rest`() {
        val orders = (0 until 20).map { QueueLogic.currentFirstOrder(8, 0, Random(it)).toList() }.toSet()
        assertNotEquals(1, orders.size)
    }

    @Test fun `shuffle with one track is that track`() {
        assertArrayEquals(intArrayOf(0), QueueLogic.currentFirstOrder(1, 0))
        assertArrayEquals(IntArray(0), QueueLogic.currentFirstOrder(0, 0))
        assertArrayEquals(intArrayOf(0), QueueLogic.currentFirstOrder(1, 5)) // out of range is clamped
    }

    @Test fun `turning shuffle off restores the original order`() {
        val shuffled = QueueLogic.currentFirstOrder(5, 2, Random(1))
        assertEquals(shuffled.toList(), QueueLogic.playOrder(5, true, shuffled))
        assertEquals(listOf(0, 1, 2, 3, 4), QueueLogic.playOrder(5, false, shuffled))
        // A stale order (queue changed size) falls back to the list order.
        assertEquals(listOf(0, 1, 2), QueueLogic.playOrder(3, true, shuffled))
    }

    @Test fun `move reorders play-order positions`() {
        assertEquals(listOf("b", "c", "a", "d"), QueueLogic.move(listOf("a", "b", "c", "d"), 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), QueueLogic.move(listOf("a", "b", "c", "d"), 3, 0))
        assertEquals(listOf("a", "b"), QueueLogic.move(listOf("a", "b"), 0, 5))
    }

    @Test fun `repeat off stops at the end`() {
        assertEquals(2, QueueLogic.next(1, 3, RepeatSetting.OFF, auto = true))
        assertNull(QueueLogic.next(2, 3, RepeatSetting.OFF, auto = true))
        assertNull(QueueLogic.previous(0, 3, RepeatSetting.OFF))
    }

    @Test fun `repeat all wraps at the end and the start`() {
        assertEquals(0, QueueLogic.next(2, 3, RepeatSetting.ALL, auto = true))
        assertEquals(2, QueueLogic.previous(0, 3, RepeatSetting.ALL))
    }

    @Test fun `repeat one replays on auto-advance, but Next moves on`() {
        assertEquals(1, QueueLogic.next(1, 3, RepeatSetting.ONE, auto = true))
        assertEquals(2, QueueLogic.next(1, 3, RepeatSetting.ONE, auto = false))
        assertEquals(0, QueueLogic.next(2, 3, RepeatSetting.ONE, auto = false))
    }

    @Test fun `repeat button cycles off, all, one`() {
        assertEquals(RepeatSetting.ALL, RepeatSetting.OFF.next())
        assertEquals(RepeatSetting.ONE, RepeatSetting.ALL.next())
        assertEquals(RepeatSetting.OFF, RepeatSetting.ONE.next())
        // Media3 values: OFF 0, ONE 1, ALL 2.
        assertEquals(RepeatSetting.ONE, RepeatSetting.fromMedia3(1))
        assertEquals(RepeatSetting.ALL, RepeatSetting.fromMedia3(2))
        assertEquals(RepeatSetting.OFF, RepeatSetting.fromMedia3(99))
    }

    @Test fun `errors skip forward but never loop through a queue of broken files`() {
        assertEquals(QueueLogic.ErrorAction.SKIP, QueueLogic.afterError(1, 3, hasNext = true))
        assertEquals(QueueLogic.ErrorAction.SKIP, QueueLogic.afterError(2, 3, hasNext = true))
        assertEquals(QueueLogic.ErrorAction.STOP, QueueLogic.afterError(3, 3, hasNext = true)) // repeat-all, all broken
        assertEquals(QueueLogic.ErrorAction.STOP, QueueLogic.afterError(1, 3, hasNext = false)) // last item
        assertEquals(QueueLogic.ErrorAction.STOP, QueueLogic.afterError(1, 1, hasNext = true))
    }
}

/** The decision after a player error is made from the failing item, not the current one. */
class QueueErrorStepTest {
    @Test fun `the last item failing while the one before it is current stops with stopped`() {
        // Gapless pre-roll: item 2 (the last) fails while item 1 is still current.
        val step = QueueLogic.afterError(
            failingIndex = 2,
            currentIndex = 1,
            nextAfterFailing = null,
            nextAfterCurrent = 2,
            consecutiveErrors = 1,
            queueSize = 3
        )
        assertEquals(QueueLogic.ErrorAction.STOP, step.action)
    }

    @Test fun `a middle item failing ahead of the current one skips past the failing item`() {
        val step = QueueLogic.afterError(
            failingIndex = 2,
            currentIndex = 1,
            nextAfterFailing = 3,
            nextAfterCurrent = 2,
            consecutiveErrors = 1,
            queueSize = 5
        )
        assertEquals(QueueLogic.ErrorStep(QueueLogic.ErrorAction.SKIP, 3), step)
    }

    @Test fun `the current item failing skips to the next item`() {
        assertEquals(
            QueueLogic.ErrorStep(QueueLogic.ErrorAction.SKIP, 2),
            QueueLogic.afterError(
                failingIndex = 1,
                currentIndex = 1,
                nextAfterFailing = 2,
                nextAfterCurrent = 2,
                consecutiveErrors = 1,
                queueSize = 3
            )
        )
        assertEquals(
            QueueLogic.ErrorAction.STOP,
            QueueLogic.afterError(
                failingIndex = 2,
                currentIndex = 2,
                nextAfterFailing = null,
                nextAfterCurrent = null,
                consecutiveErrors = 1,
                queueSize = 3
            ).action
        )
        // Unknown failing period: decide from the current item.
        assertEquals(
            QueueLogic.ErrorStep(QueueLogic.ErrorAction.SKIP, 2),
            QueueLogic.afterError(
                failingIndex = null,
                currentIndex = 1,
                nextAfterFailing = null,
                nextAfterCurrent = 2,
                consecutiveErrors = 1,
                queueSize = 3
            )
        )
        // Every item failed in a row (repeat-all): stop.
        assertEquals(
            QueueLogic.ErrorAction.STOP,
            QueueLogic.afterError(
                failingIndex = 0,
                currentIndex = 0,
                nextAfterFailing = 1,
                nextAfterCurrent = 1,
                consecutiveErrors = 3,
                queueSize = 3
            ).action
        )
    }

    @Test fun `queue sections are the played rows, now playing and up next`() {
        assertEquals(QueueLogic.Sections(0 until 2, 2, 3 until 6), QueueLogic.sections(6, 2))
        // The first track: nothing played yet.
        assertEquals(QueueLogic.Sections(IntRange.EMPTY, 0, 1 until 3), QueueLogic.sections(3, 0))
        // The last track: nothing up next.
        assertEquals(QueueLogic.Sections(0 until 2, 2, IntRange.EMPTY), QueueLogic.sections(3, 2))
        // No current track (e.g. still restoring): one plain list, no headers.
        assertEquals(QueueLogic.Sections(IntRange.EMPTY, null, 0 until 4), QueueLogic.sections(4, -1))
        assertEquals(QueueLogic.Sections(IntRange.EMPTY, null, IntRange.EMPTY), QueueLogic.sections(0, 0))
    }

    @Test fun `undo of a queue removal only while the queue is what the removal left`() {
        assertTrue(QueueLogic.canRestore(countNow = 3, countAfter = 3, index = 1))
        assertTrue(QueueLogic.canRestore(3, 3, 3)) // the last row comes back at the end
        // A new list was started, or a row was added or removed meanwhile.
        assertFalse(QueueLogic.canRestore(4, 3, 1))
        assertFalse(QueueLogic.canRestore(2, 3, 1))
        assertFalse(QueueLogic.canRestore(3, 3, 4))
        assertFalse(QueueLogic.canRestore(3, 3, -1))
    }

    @Test fun `a play order names every playlist index once`() {
        assertTrue(QueueLogic.isOrder(intArrayOf(2, 0, 1), 3))
        assertFalse(QueueLogic.isOrder(intArrayOf(2, 0, 1), 4)) // e.g. sent before the restored item was inserted
        assertFalse(QueueLogic.isOrder(intArrayOf(0, 0, 1), 3))
        assertFalse(QueueLogic.isOrder(intArrayOf(0, 1, 3), 3))
    }
}
