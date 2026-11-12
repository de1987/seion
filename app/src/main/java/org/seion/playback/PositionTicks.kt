package org.seion.playback

/**
 * When the UI's position updates while playing, kept sparse to save battery: the elapsed time changes
 * on whole seconds, and the progress bars move about a pixel per tick. Ticks sit on the position's grid of [stepMs], a
 * step that divides a second, so every whole second is a tick and the time never lags.
 */
object PositionTicks {
    const val MIN_STEP_MS = 250L
    const val MAX_STEP_MS = 1_000L

    /** A tick waits this much past the boundary, so the position read then is already in the new second. */
    const val LATE_MS = 10L

    /**
     * When the last tick was published (elapsedRealtime ms; 0: none yet). The UI's stepped motion (level bars, drift)
     * steps on the same grid, so a tick and a step share one frame instead of costing two.
     */
    @Volatile var lastTickAtMs: Long = 0L

    /**
     * One pixel of a bar [widthPx] wide over a track of [durationMs], clamped to 250 ms..1 s and rounded down to 250, 500
     * or 1000 ms (unknown length or width: 1 s, the time alone).
     */
    fun stepMs(durationMs: Long, widthPx: Int): Long {
        if (durationMs <= 0 || widthPx <= 0) return MAX_STEP_MS
        val perPixel = (durationMs / widthPx).coerceIn(MIN_STEP_MS, MAX_STEP_MS)
        return when {
            perPixel >= MAX_STEP_MS -> MAX_STEP_MS
            perPixel >= 500L -> 500L
            else -> MIN_STEP_MS
        }
    }

    /** The wait from [positionMs] to just past the next multiple of [stepMs]. */
    fun delayMs(positionMs: Long, stepMs: Long): Long = stepMs - Math.floorMod(positionMs.coerceAtLeast(0), stepMs) + LATE_MS
}
