package org.seion.ui.nowplaying

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.sign
import org.seion.ui.theme.SeionMotion

/*
 * Now playing's touch gestures. Every drag offset here is plain state written
 * synchronously by its DraggableState, and every settle runs under that state's own mutex: a new touch cancels a
 * settle, and a release always reads the final offset. Launching one coroutine per drag delta to move an Animatable
 * breaks when the last move and the lift arrive in the same input batch (a quick flick): the release reads a stale
 * offset and the queued moves then cancel the spring back, leaving the cover stuck off centre.
 */

/** The spring a released drag settles back with (no bounce: the cover and the screen never overshoot the centre). */
internal val settleSpring: AnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** What a released cover swipe does. */
enum class CoverSkip { NEXT, PREVIOUS, NONE }

/**
 * A cover dragged [dx] px (negative = left) and let go at [velocity] px/s: past 30 % of the cover or on a fling it
 * skips (left = next, right = previous); a fling against the drag (the finger changed its mind) skips nothing.
 */
fun coverSkip(dx: Float, velocity: Float, coverPx: Float, flingPx: Float): CoverSkip {
    val fling = abs(velocity) > flingPx
    val dir =
        when {
            fling && dx != 0f && sign(dx) != sign(velocity) -> 0f
            fling -> sign(velocity)
            abs(dx) > 0.3f * coverPx -> sign(dx)
            else -> 0f
        }
    return when {
        dir < 0f -> CoverSkip.NEXT
        dir > 0f -> CoverSkip.PREVIOUS
        else -> CoverSkip.NONE
    }
}

/**
 * The cover's horizontal drag. The offset belongs to the cover (track) it started on: only that cover, and its near
 * layer in Seion Infinite, are drawn offset, so the next cover always arrives centred even while the old one is
 * still springing back. A drag that starts on another cover freezes the previous one where it was (it is fading
 * out) instead of snapping it to the centre; a cover that shows again ([arrived]) is never offset by an old drag.
 */
@Stable
class CoverSwipeState {
    private var owner by mutableStateOf<String?>(null)
    private var ownerArt by mutableStateOf<String?>(null)
    private var offset by mutableFloatStateOf(0f)
    private var previous by mutableStateOf<String?>(null)
    private var previousArt by mutableStateOf<String?>(null)
    private var previousOffset by mutableFloatStateOf(0f)
    private var shown by mutableStateOf<String?>(null)
    private var shownArt by mutableStateOf<String?>(null)

    /** The drag offset (px) of the cover being dragged, or 0 when there is none. */
    val dragged: Float get() = offset

    val draggable = DraggableState { delta -> offset += delta }

    /** A drag starts on the cover of track [mediaId] whose art key is [artKey]. */
    fun begin(mediaId: String?, artKey: String?) {
        if (mediaId == owner) return
        previous = owner
        previousArt = ownerArt
        previousOffset = offset
        owner = mediaId
        ownerArt = artKey
        offset = 0f
    }

    /**
     * The cover of track [mediaId] (art key [artKey]) is now the one shown. A cover frozen by a later drag starts
     * again from the centre, never from its old offset.
     */
    fun arrived(mediaId: String?, artKey: String?) {
        shown = mediaId
        shownArt = artKey
        if (mediaId != null && mediaId == previous) {
            previous = null
            previousArt = null
            previousOffset = 0f
        }
    }

    /** How far the cover of track [mediaId] is drawn from the centre. */
    fun offsetOf(mediaId: String?): Float = when (mediaId) {
        null -> 0f
        owner -> offset
        previous -> previousOffset
        else -> 0f
    }

    /**
     * How far the near layer of the ambient keyed [ambientKey] follows its cover: while that cover shows, and while
     * its ambient cross-fades out under another one. Not once the shown cover has the same art (the same ambient
     * now belongs to a centred cover).
     */
    fun offsetOfAmbient(ambientKey: String): Float {
        val own = ownerArt
        val prev = previousArt
        return when {
            own != null && ambientIsFor(ambientKey, own) -> if (owner == shown || shownArt != own) offset else 0f
            prev != null && ambientIsFor(ambientKey, prev) -> if (shownArt != prev) previousOffset else 0f
            else -> 0f
        }
    }

    /** Springs the dragged cover back to the centre (snaps with reduced motion); a new touch cancels it. */
    suspend fun settle(reduceMotion: Boolean) {
        draggable.drag(MutatePriority.Default) {
            if (reduceMotion) {
                dragBy(-offset)
            } else {
                animate(offset, 0f, animationSpec = settleSpring) { v, _ -> dragBy(v - offset) }
            }
        }
    }
}

/** Whether the ambient keyed [key] (`cacheKey#generation`) was made for the art [cacheKey]: exactly, not a prefix. */
fun ambientIsFor(key: String, cacheKey: String): Boolean = key.startsWith("$cacheKey#")

/**
 * A drag that follows the finger and springs back to 0 when let go, never below [floor]. Now playing's vertical drag
 * uses it with floor 0 (the whole screen follows the finger down; upwards there is nothing to do, so an upward
 * swipe changes nothing), the mini-player's title for its sideways swipe.
 */
@Stable
open class SpringBackDragState(private val floor: Float = Float.NEGATIVE_INFINITY) {
    var offset by mutableFloatStateOf(0f)
        private set

    val draggable = DraggableState { delta -> offset = (offset + delta).coerceAtLeast(floor) }

    /** Springs back to 0 (snaps with reduced motion); a new touch cancels it and goes on from where it is. */
    suspend fun settle(reduceMotion: Boolean) {
        if (offset == 0f) return
        draggable.drag(MutatePriority.Default) {
            if (reduceMotion) {
                dragBy(-offset)
            } else {
                animate(offset, 0f, animationSpec = settleSpring) { v, _ -> dragBy(v - offset) }
            }
        }
    }
}

/** Now playing's vertical drag: the screen follows the finger down only. */
@Stable
class SheetDragState : SpringBackDragState(floor = 0f)

/**
 * Whether the last touch on a node ended because the system cancelled it (the app went to the background, the
 * notification shade was pulled) rather than being lifted. `draggable` reports both through `onDragStopped`, and a
 * cancelled drag must never collapse now playing or skip a track.
 */
@Stable
class TouchEnd {
    var cancelled = false
        internal set
}

/** Watches the touches on this node for [TouchEnd.cancelled]. */
fun Modifier.watchTouchEnd(end: TouchEnd): Modifier = this then TouchEndElement(end)

private data class TouchEndElement(val end: TouchEnd) : ModifierNodeElement<TouchEndNode>() {
    override fun create() = TouchEndNode(end)

    override fun update(node: TouchEndNode) {
        node.end = end
    }
}

private class TouchEndNode(var end: TouchEnd) :
    Modifier.Node(),
    PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Initial && pointerEvent.changes.any { it.changedToDownIgnoreConsumed() }) end.cancelled = false
    }

    override fun onCancelPointerInput() {
        end.cancelled = true
    }
}

/**
 * Released [offset] px down at [velocity] px/s on a screen [height] px tall: collapse past 21 % or on a fling down.
 * Before the height is known (0) nothing collapses.
 */
fun sheetCollapses(offset: Float, velocity: Float, height: Float): Boolean =
    height > 0f && offset > 0f && (offset > 0.21f * height || velocity > SHEET_FLING_PX_S)

/** The fling (px/s) that commits a drag between now playing and the mini-player, whatever the distance. */
const val SHEET_FLING_PX_S = 1800f

/**
 * Lets go of a scrubbed now playing transition (predictive back cancelled, a short drag on the mini-player) without
 * committing it: the scrub runs back to where it started, then the transition settles there. Calling
 * `animateTo(currentState)` would retarget a seeking transition: the shared cover goes on towards the other end,
 * then snaps back (and flashes the other end once more). Ending with `snapTo` instead drops the shared cover for
 * one frame; an `animateTo` from fraction 0, where everything already sits at home, ends it without a gap.
 */
suspend fun SeekableTransitionState<Boolean>.cancelSeek(reduceMotion: Boolean) {
    val home = currentState
    val away = targetState
    val from = fraction
    // Nothing to run back (a scrub that never left home): straight to the settle.
    if (!reduceMotion && away != home && from > 0f) {
        val ms = (CANCEL_MS * from).coerceAtLeast(CANCEL_MIN_MS)
        val start = withFrameNanos { it }
        while (true) {
            val t = ((withFrameNanos { it } - start) / 1_000_000f / ms).coerceIn(0f, 1f)
            seekTo(from * (1f - SeionMotion.Standard.transform(t)), targetState = away)
            if (t >= 1f) break
        }
    }
    // At fraction 0 everything already sits at home; the retargeting animateTo runs the transition's own duration
    // there (its spec is ignored when it retargets) without moving anything. currentState reads the other end
    // meanwhile, as during any expand or collapse.
    if (reduceMotion) snapTo(home) else animateTo(home)
}

/** A full scrub (fraction 1) runs back in this long; a partial one proportionally faster, but never in a blink. */
private const val CANCEL_MS = 350f
private const val CANCEL_MIN_MS = 200f
