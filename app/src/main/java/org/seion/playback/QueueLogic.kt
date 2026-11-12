package org.seion.playback

import kotlin.random.Random
import org.seion.settings.RepeatSetting

/**
 * Pure play-order rules (unit-tested). The playlist itself never changes order for shuffle: ExoPlayer keeps
 * the original list and traverses it through a ShuffleOrder, so turning shuffle off restores the original order.
 * Positions below are positions in the *play order*; values are playlist indices.
 */
object QueueLogic {
    /** Shuffle that keeps [current] playing: it comes first, every other index follows in random order. */
    fun currentFirstOrder(count: Int, current: Int, random: Random = Random.Default): IntArray {
        if (count <= 0) return IntArray(0)
        val cur = current.coerceIn(0, count - 1)
        val rest = (0 until count).filter { it != cur }.shuffled(random)
        return (listOf(cur) + rest).toIntArray()
    }

    /** The order the queue screen shows and playback follows. */
    fun playOrder(count: Int, shuffleEnabled: Boolean, shuffleOrder: IntArray?): List<Int> =
        if (shuffleEnabled && shuffleOrder != null && shuffleOrder.size == count) {
            shuffleOrder.toList()
        } else {
            (0 until count).toList()
        }

    /** Moves the entry at play-order position [from] to [to]. */
    fun <T> move(order: List<T>, from: Int, to: Int): List<T> {
        if (from !in order.indices || to !in order.indices || from == to) return order
        val m = order.toMutableList()
        val item = m.removeAt(from)
        m.add(to, item)
        return m
    }

    /**
     * The play-order position after [position] when a track ends ([auto]) or the user skips: repeat-one replays
     * on auto-advance only, repeat-all wraps at the end, off stops (null).
     */
    fun next(position: Int, size: Int, repeat: RepeatSetting, auto: Boolean): Int? {
        if (size <= 0) return null
        if (auto && repeat == RepeatSetting.ONE) return position
        return when {
            position + 1 < size -> position + 1
            repeat == RepeatSetting.OFF -> null
            else -> 0
        }
    }

    /** The play-order position before [position] (user skip back): wraps only with a repeat mode. */
    fun previous(position: Int, size: Int, repeat: RepeatSetting): Int? {
        if (size <= 0) return null
        return when {
            position > 0 -> position - 1
            repeat == RepeatSetting.OFF -> null
            else -> size - 1
        }
    }

    /**
     * The queue screen's sections for [size] rows in play order with the current track at play-order position
     * [current]: the rows already played in this pass (dimmed, no header), NOW PLAYING, and UP NEXT (what will play after
     * it in this order: the shuffle order while shuffled). Without a current track (-1) every row is "up next" with no
     * headers.
     */
    data class Sections(val played: IntRange, val current: Int?, val upNext: IntRange)

    fun sections(size: Int, current: Int): Sections = if (current !in 0 until size) {
        Sections(IntRange.EMPTY, null, 0 until size)
    } else {
        Sections(0 until current, current, current + 1 until size)
    }

    /**
     * Undo of a queue removal is only applied while the queue still has the [countAfter] items the removal left (the
     * service checks it when the Undo arrives, after the removal: both are session commands, run in order); anything
     * else (a new list was started, a row was added or removed meanwhile) drops it rather than inserting the song where
     * it never was. [index] must fit the queue as it is.
     */
    fun canRestore(countNow: Int, countAfter: Int, index: Int): Boolean = countNow == countAfter && index in 0..countNow

    /** [order] is a play order of [count] items: every playlist index exactly once. */
    fun isOrder(order: IntArray, count: Int): Boolean = order.size == count && order.sorted() == (0 until count).toList()

    /** Removing [removeIndex]: whether to move to the next item first (so playback continues). */
    fun skipBeforeRemoving(removeIndex: Int, currentIndex: Int, hasNext: Boolean): Boolean = removeIndex == currentIndex && hasNext

    /**
     * The item the UI shows as current right after a new list was started: the controller can briefly report the old
     * queue's item before the session applies the new list.
     */
    fun displayedCurrent(controllerId: String?, pendingId: String?, pendingAgeMs: Long): String? =
        if (pendingId != null && pendingAgeMs < PENDING_START_MS && controllerId != pendingId) pendingId else controllerId

    /** How long a just-started list's first item wins over what the controller reports. */
    const val PENDING_START_MS = 1_500L

    enum class ErrorAction { SKIP, STOP }

    /** What to do after a player error: skip to [target] (a playlist index), or stop. */
    data class ErrorStep(val action: ErrorAction, val target: Int?)

    /**
     * After a player error on [failingIndex] (the error's period; null when unknown) while [currentIndex] is current;
     * [nextAfterFailing] / [nextAfterCurrent] are the items that follow them in play order (null at the end without
     * repeat). The decision is made from the *failing* item: an error of the next item's pre-roll can
     * fire while the item before it is still current, and deciding from the current item would "skip" onto the failed
     * item and never announce the stop.
     */
    fun afterError(
        failingIndex: Int?,
        currentIndex: Int,
        nextAfterFailing: Int?,
        nextAfterCurrent: Int?,
        consecutiveErrors: Int,
        queueSize: Int
    ): ErrorStep {
        val next = if (failingIndex == null || failingIndex == currentIndex) nextAfterCurrent else nextAfterFailing
        val action = afterError(consecutiveErrors, queueSize, next != null)
        return ErrorStep(action, if (action == ErrorAction.SKIP) next else null)
    }

    /** After [consecutiveErrors] failures in a row: skip while a next item exists and not every item has failed. */
    fun afterError(consecutiveErrors: Int, queueSize: Int, hasNext: Boolean): ErrorAction =
        if (hasNext && consecutiveErrors < queueSize) ErrorAction.SKIP else ErrorAction.STOP
}
