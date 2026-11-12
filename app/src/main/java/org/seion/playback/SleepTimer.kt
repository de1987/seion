package org.seion.playback

/**
 * The sleep timer's rules (pure; a fake clock in the tests). It lives in the playback service, so it keeps counting with
 * Seion's UI gone, and it pauses whatever is playing, whoever started it (the app, the panel, a car, a headset).
 *
 * Two kinds: a countdown to [deadline] (on the `elapsedRealtime` clock, which the UI shares to show the time left), or
 * "end of the current song" ([endOfItem]: the player's pause at the end of the item). A countdown fades the volume out
 * over its last [FADE_MS] (quadratic in amplitude, so the fall sounds even), then the service pauses and restores the
 * volume. After the pause the usual paused grace applies ("Closing for real").
 */
class SleepTimer(private val clock: () -> Long) {
    /** When the countdown pauses (elapsedRealtime ms), or [NONE]. */
    var deadline: Long = NONE
        private set

    /** Pause at the end of the current song. */
    var endOfItem: Boolean = false
        private set

    val active: Boolean get() = deadline != NONE || endOfItem

    fun start(minutes: Int) {
        require(minutes in 1..MAX_MINUTES) { "minutes out of range" }
        deadline = clock() + minutes * MINUTE_MS
        endOfItem = false
    }

    fun startEndOfItem() {
        deadline = NONE
        endOfItem = true
    }

    /** Adds [minutes] to a running countdown (from now if it is already due); false when there is none. */
    fun extend(minutes: Int): Boolean {
        if (deadline == NONE || minutes !in 1..MAX_MINUTES) return false
        val now = clock()
        deadline = (maxOf(deadline, now) + minutes * MINUTE_MS).coerceAtMost(now + MAX_MINUTES * MINUTE_MS)
        return true
    }

    fun cancel() {
        deadline = NONE
        endOfItem = false
    }

    /** Time left on the countdown (ms, ≥ 0), or −1 when there is none. */
    fun remainingMs(): Long = if (deadline == NONE) -1 else maxOf(0L, deadline - clock())

    fun expired(): Boolean = deadline != NONE && clock() >= deadline

    /** The player volume now: 1 until the last [FADE_MS] of a countdown, then (left / fade)² down to 0. */
    fun volume(): Float {
        val left = remainingMs()
        if (left < 0 || left >= FADE_MS) return 1f
        val r = left.toFloat() / FADE_MS
        return r * r
    }

    /** How long the service may wait before it looks again: until the fade starts, then every [FADE_TICK_MS]. */
    fun nextCheckMs(): Long {
        val left = remainingMs()
        if (left < 0) return -1
        return if (left > FADE_MS) left - FADE_MS else minOf(FADE_TICK_MS, left).coerceAtLeast(1)
    }

    companion object {
        const val NONE = -1L
        const val MINUTE_MS = 60_000L
        const val FADE_MS = 10_000L
        const val FADE_TICK_MS = 100L
        const val MAX_MINUTES = 12 * 60

        /** The dialog's choices (minutes), then "End of current song". */
        val PRESETS = listOf(15, 30, 45, 60, 90)

        /** "+10 min" on a running countdown. */
        const val EXTEND_MINUTES = 10

        /** [SeionCommands.KEY_MINUTES] of [SeionCommands.SET_SLEEP_TIMER]: 0 turns the timer off, −1 = end of song. */
        const val OFF = 0
        const val END_OF_SONG = -1

        /** Whether a SET_SLEEP_TIMER argument is valid (a minute count, [OFF] or [END_OF_SONG]). */
        fun validSet(minutes: Int): Boolean = minutes == OFF || minutes == END_OF_SONG || minutes in 1..MAX_MINUTES

        /**
         * The minutes the UI shows for [remainingMs]: rounded up, so "1 min" stays until the timer pauses and a fresh
         * 30-minute timer reads 30.
         */
        fun minutesLeft(remainingMs: Long): Int = ((remainingMs + MINUTE_MS - 1) / MINUTE_MS).toInt().coerceAtLeast(0)
    }
}
