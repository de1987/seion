package org.seion.ui.motion

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import org.seion.playback.PositionTicks

/**
 * Slow decorative motion drawn a few times a second instead of every display frame, to save battery: the
 * ambient drift moves ~4 px/s, and the playing row's level bars are a hint, not a meter. The shapes come from the
 * running time, so the motion is continuous: a step only samples it.
 */
object SteppedMotion {
    /**
     * The drift's step: 8 fps, sharing every position tick ([PositionTicks]: 250, 500 or 1000 ms). 100 ms (10 fps)
     * missed the 250 ms ticks of songs under ~6 min: each tick then moved the grid and Now playing drew ~16 fps.
     */
    const val DRIFT_STEP_MS = 125L

    /** The level bars' step: 8 fps, sharing every position tick (250 ms, 500 ms, 1 s). */
    const val LEVEL_STEP_MS = 125L

    /** The drift goes 0 → 1 in 38 s and back in 38 s. */
    const val DRIFT_HALF_PERIOD_MS = 38_000L

    /** The wait from [nowMs] to the next step on the grid of [stepMs] through [anchorMs] (the last position tick). */
    fun stepDelay(nowMs: Long, anchorMs: Long, stepMs: Long): Long = stepMs - Math.floorMod(nowMs - anchorMs, stepMs)

    /** 0 → 1 → 0 over 2 × [halfMs], linear. */
    private fun triangle(elapsedMs: Long, halfMs: Long): Float {
        val t = Math.floorMod(elapsedMs, 2 * halfMs)
        return (if (t <= halfMs) t else 2 * halfMs - t).toFloat() / halfMs
    }

    /** The drift's phase after [elapsedMs] of drifting (linear, reversed every 38 s). */
    fun driftPhase(elapsedMs: Long): Float = triangle(elapsedMs, DRIFT_HALF_PERIOD_MS)

    fun levelHalfPeriodMs(bar: Int): Long = 500L + 130L * bar

    /** Bar [bar]'s height (0.25..1 of the box) after [elapsedMs] of playing: a back-and-forth tween, eased both ways. */
    fun levelHeight(elapsedMs: Long, bar: Int): Float =
        0.25f + 0.75f * FastOutSlowInEasing.transform(triangle(elapsedMs, levelHalfPeriodMs(bar)))
}

/** The spectrum band's frame cap: 30 analysed and drawn frames a second, whatever the display's refresh rate. */
object SpectrumPacing {
    private const val FRAME_NS = 1_000_000_000L / 30

    /** A display frame up to this much early still counts (vsync jitter), so 60 Hz draws every other frame. */
    private const val SLACK_NS = 4_000_000L

    /** Whether the display frame at [nowNs] analyses and draws, the last one having been at [lastNs] (0: none yet). */
    fun due(nowNs: Long, lastNs: Long): Boolean = lastNs == 0L || nowNs - lastNs >= FRAME_NS - SLACK_NS
}

/**
 * Milliseconds of running time, advanced in [stepMs] steps while [running] and the activity is started (screen on,
 * Seion visible): a `delay` loop, unlike `withFrameNanos`, would keep ticking in the background. Stopped, it keeps its
 * value, so the motion resumes where it was. Steps land on the position ticks' grid (a tick and a step make one frame).
 * With [sharedFrames] (display frames another animation draws anyway, e.g. the spectrum band's 30 a second), it steps
 * only on those frames instead, at least [stepMs] apart, so it adds no frame of its own. Read it in the draw phase:
 * each step redraws without recomposing.
 */
@Composable
fun rememberSteppedClock(running: Boolean, stepMs: Long, sharedFrames: ((Long) -> Boolean)? = null): LongState {
    val elapsed = remember { mutableLongStateOf(0L) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = running && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val currentShared by rememberUpdatedState(sharedFrames)
    LaunchedEffect(active, stepMs, sharedFrames != null) {
        if (!active) return@LaunchedEffect
        val origin = SystemClock.elapsedRealtime() - elapsed.longValue
        while (true) {
            val shared = currentShared
            if (shared != null) {
                val frameNs = withFrameNanos { it }
                val now = SystemClock.elapsedRealtime() - origin
                val since = now - elapsed.longValue
                // On a shared frame; or on any frame if the other animation stopped drawing (never frozen by it).
                if (since >= stepMs - SHARED_SLACK_MS && (shared(frameNs) || since >= 2 * stepMs)) elapsed.longValue = now
            } else {
                delay(SteppedMotion.stepDelay(SystemClock.elapsedRealtime(), PositionTicks.lastTickAtMs, stepMs))
                elapsed.longValue = SystemClock.elapsedRealtime() - origin
            }
        }
    }
    return elapsed
}

/** A shared frame up to this early still takes the step (the 30 fps band's frames are 33 ms apart, not 100 / 3). */
private const val SHARED_SLACK_MS = 10L
