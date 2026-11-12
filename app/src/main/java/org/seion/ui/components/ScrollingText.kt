package org.seion.ui.components

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Scrolling lines: a line that doesn't fit makes one pass: it waits, rolls left until its end shows, holds,
 * cross-fades back to the start and stays there.
 * No loop (Compose's basicMarquee loops and wraps around). With reduced motion it ends in "…" instead. Its edges
 * ([ScrollEdges]): on Now playing a clear 13 % margin on each side, with a short fade inside the margin only on a side
 * where text is hidden; in the mini-player the text starts at the column start (where a line that fits starts), a short
 * fade appears on the left while text is hidden there, and the right keeps its 13 % fade gutter.
 */

/**
 * The timeline of one pass of an overflowing line. Pure, so it is unit-tested (`ScrollPassTest`). Times are
 * milliseconds from the start of the pass; [offsetAt] is how far the text has moved left, in px.
 *
 * Delay ([startDelayMs], at the start) → scroll ([distance] at [speed] px/s, with a [rampMs] soft start and stop) →
 * hold ([holdMs], at the end) → fade out ([fadeOutMs]) → jump to the start → fade in ([fadeInMs]) → rest at the start.
 * A [distance] ≤ 0 (the text fits) never moves: [Phase.Static].
 */
class ScrollPass(
    val distance: Float,
    val speed: Float,
    val startDelayMs: Long = START_DELAY_MS,
    val holdMs: Long = HOLD_MS,
    val fadeOutMs: Long = FADE_OUT_MS,
    val fadeInMs: Long = FADE_IN_MS,
    val rampMs: Long = RAMP_MS
) {
    enum class Phase { Static, Delay, Scroll, Hold, FadeOut, FadeIn, Rest }

    /** Whether the line moves at all. */
    val moves: Boolean = distance > 0f && speed > 0f

    // A trapezoidal speed profile: accelerate over the ramp, cruise at [speed], brake over the ramp. A distance too
    // short to reach full speed peaks lower (a triangle), so the text never overshoots the end.
    private val accel = if (rampMs > 0) speed / (rampMs / 1000f) else Float.POSITIVE_INFINITY
    private val peak = if (!moves) {
        0f
    } else if (rampMs > 0) {
        min(speed, sqrt(distance * accel))
    } else {
        speed
    }
    private val rampS = if (!moves || rampMs <= 0) 0f else peak / accel
    private val scrollS = if (!moves) 0f else distance / peak + rampS

    /** The scroll's duration (the delay, hold and fades come on top). */
    val scrollMs: Float = scrollS * 1000f
    private val scrollEnd = startDelayMs + scrollMs
    private val holdEnd = scrollEnd + holdMs
    private val fadeOutEnd = holdEnd + fadeOutMs

    /** The whole pass; after it the line rests at the start. 0 when it doesn't move. */
    val totalMs: Long = if (!moves) 0L else ceil(fadeOutEnd + fadeInMs).toLong()

    fun phaseAt(t: Long): Phase = when {
        !moves -> Phase.Static
        t < startDelayMs -> Phase.Delay
        t < scrollEnd -> Phase.Scroll
        t < holdEnd -> Phase.Hold
        t < fadeOutEnd -> Phase.FadeOut
        t < totalMs -> Phase.FadeIn
        else -> Phase.Rest
    }

    fun offsetAt(t: Long): Float {
        if (!moves || t <= startDelayMs || t >= fadeOutEnd) return 0f
        if (t >= scrollEnd) return distance
        val s = (t - startDelayMs) / 1000f
        return when {
            s < rampS -> 0.5f * accel * s * s
            s <= scrollS - rampS -> 0.5f * accel * rampS * rampS + peak * (s - rampS)
            else -> (distance - 0.5f * accel * (scrollS - s) * (scrollS - s)).coerceAtMost(distance)
        }.coerceIn(0f, distance)
    }

    fun alphaAt(t: Long): Float = when {
        !moves || t < holdEnd || t >= totalMs -> 1f
        t < fadeOutEnd -> 1f - (t - holdEnd) / fadeOutMs.toFloat()
        else -> (t - fadeOutEnd) / fadeInMs.toFloat()
    }.coerceIn(0f, 1f)

    companion object {
        /** Each margin's (Now playing) or the right fade gutter's (mini-player) share of the line width. */
        const val GUTTER = 0.13f

        /** The short fade on a side where text is hidden, as a share of the line width. */
        const val EDGE_FADE = 0.06f

        /** Room left between a glyph's outline and a margin (px), the same on both sides, for anti-aliasing. */
        const val INK_PAD_PX = 1f

        const val START_DELAY_MS = 1500L
        const val HOLD_MS = 2500L
        const val FADE_OUT_MS = 150L
        const val FADE_IN_MS = 200L
        const val RAMP_MS = 300L

        /** Cruising speed in dp per second. */
        const val SPEED_DP_PER_S = 34f

        fun gutter(lineWidth: Float): Float = lineWidth * GUTTER

        fun edgeFade(lineWidth: Float): Float = lineWidth * EDGE_FADE

        /**
         * How strong a side's short fade is (0..1) with [hidden] px of text hidden past that side: it grows over the
         * first [fadeWidth] px, so it appears as the text starts to move and is gone when nothing is hidden there.
         */
        fun fadeStrength(hidden: Float, fadeWidth: Float): Float = if (fadeWidth <= 0f) 0f else (hidden / fadeWidth).coerceIn(0f, 1f)
    }
}

/** How an overflowing line meets its edges (see the file comment). */
enum class ScrollEdges {
    /** Now playing: a clear 13 % margin on each side; the ink rests and holds just inside the margins' inner edges. */
    Margins,

    /** The mini-player: rests at the start, like a line that fits; holds at the inner edge of the right fade gutter. */
    StartAligned
}

/**
 * Where an overflowing line's text sits at rest ([startX], px from the line's left edge to the text's origin) and how
 * far it rolls ([distance], px). Pure, so it is unit-tested (`ScrollPassTest`).
 */
class ScrollGeometry(val startX: Float, val distance: Float) {
    companion object {
        /**
         * Now playing: the first glyph's ink ([inkLeft], px from the text's origin) rests at the inner edge of the left
         * margin and the last glyph's ink ([inkRight]) holds at the inner edge of the right one, so side bearings and
         * letter spacing don't make the two gaps differ.
         */
        fun margins(inkLeft: Float, inkRight: Float, lineWidth: Float): ScrollGeometry {
            val g = ScrollPass.gutter(lineWidth)
            return ScrollGeometry(g - inkLeft, (inkRight - inkLeft) - (lineWidth - 2 * g))
        }

        /** The mini-player: the text starts at the line's start and its end ([textWidth]) holds at the right gutter. */
        fun startAligned(textWidth: Float, lineWidth: Float): ScrollGeometry =
            ScrollGeometry(0f, textWidth - (lineWidth - ScrollPass.gutter(lineWidth)))
    }
}

/**
 * The horizontal ink extent of [text] drawn in [style] (px from its origin: left, right), from the glyph outlines, so
 * side bearings and letter spacing are counted. Falls back to the advance box (0, [advance]) when the typeface or the
 * size can't be resolved.
 */
internal fun textInk(text: String, style: TextStyle, density: Density, resolver: FontFamily.Resolver, advance: Float): Pair<Float, Float> {
    val fallback = 0f to advance
    if (!style.fontSize.isSp) return fallback
    val typeface =
        resolver.resolve(
            style.fontFamily,
            style.fontWeight ?: FontWeight.Normal,
            style.fontStyle ?: FontStyle.Normal,
            style.fontSynthesis ?: FontSynthesis.All
        ).value as? Typeface ?: return fallback
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = with(density) { style.fontSize.toPx() }
            val spacing = style.letterSpacing
            letterSpacing = when {
                spacing.isEm -> spacing.value
                spacing.isSp -> spacing.value / style.fontSize.value
                else -> 0f
            }
        }
    val path = Path()
    paint.getTextPath(text, 0, text.length, 0f, 0f, path)
    val bounds = RectF()
    path.computeBounds(bounds, true)
    return if (bounds.isEmpty) fallback else bounds.left to bounds.right
}

/**
 * One line of [text] that rolls once when it doesn't fit ([ScrollPass]), its edges as [edges] says, then rests at the
 * start. Text that fits is a plain line ([textAlign] inside the space the line is given), and with reduced motion an
 * overflowing line ends in "…". The pass restarts when the text, the line's width or [restartKey] (e.g. the track, so
 * an album line that stays the same rolls again with the others) changes, and when the line becomes [active] again
 * (e.g. Now playing has finished opening); it runs only while [active].
 *
 * [modifier] is the line's box (size, padding, shared bounds, clicks); [textModifier] goes on the text itself (its
 * test tag and semantics: the semantics always carry the whole text). The scroll offset and the fade are read only in
 * layer blocks, so a pass never recomposes, and nothing runs after it.
 */
@Composable
fun ScrollingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Start,
    active: Boolean = true,
    restartKey: Any? = null,
    textModifier: Modifier = Modifier,
    edges: ScrollEdges = ScrollEdges.Margins
) {
    if (LocalReduceMotion.current) {
        Text(
            text,
            modifier.then(textModifier),
            color = color,
            style = style,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        return
    }
    val align = if (textAlign == TextAlign.Center) Alignment.TopCenter else Alignment.TopStart
    BoxWithConstraints(modifier, contentAlignment = align) {
        val measurer = rememberTextMeasurer()
        val textWidth = remember(text, style, measurer) { measurer.measure(text, style, softWrap = false, maxLines = 1).size.width }
        val lineWidth = constraints.maxWidth
        if (lineWidth == Constraints.Infinity || textWidth <= lineWidth) {
            Text(text, textModifier, color = color, style = style, textAlign = textAlign, maxLines = 1, softWrap = false)
        } else {
            val density = LocalDensity.current
            val resolver = LocalFontFamilyResolver.current
            val speed = with(density) { ScrollPass.SPEED_DP_PER_S.dp.toPx() }
            val line = lineWidth.toFloat()
            val geometry =
                remember(text, style, line, edges, density, resolver, textWidth) {
                    when (edges) {
                        ScrollEdges.Margins -> {
                            val (inkLeft, inkRight) = textInk(text, style, density, resolver, textWidth.toFloat())
                            // Grown by a pixel on each side: glyphs are rasterised at whole or fractional pixel
                            // positions, so a glyph's anti-aliased edge can reach a pixel past its outline, and a margin
                            // right at the outline shaved it (seen on the last "e" of a line on the emulator).
                            ScrollGeometry.margins(inkLeft - ScrollPass.INK_PAD_PX, inkRight + ScrollPass.INK_PAD_PX, line)
                        }
                        ScrollEdges.StartAligned -> ScrollGeometry.startAligned(textWidth.toFloat(), line)
                    }
                }
            val pass = remember(geometry, speed) { ScrollPass(geometry.distance, speed) }
            // A fresh clock per pass: the first frame of a new text already sits at the start.
            val clock = remember(pass, text, active, restartKey) { mutableLongStateOf(0L) }
            LaunchedEffect(clock) {
                if (!active) return@LaunchedEffect
                val start = withFrameNanos { it }
                while (clock.longValue < pass.totalMs) clock.longValue = (withFrameNanos { it } - start) / 1_000_000L
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        // Offscreen so the gutters' mask fades the text only, not what is behind it.
                        compositingStrategy = CompositingStrategy.Offscreen
                        clip = true
                        alpha = pass.alphaAt(clock.longValue)
                    }.drawWithCache {
                        // The mask is drawn with DstOut (black erases the text, transparent keeps it), so each side's
                        // short fade is one cached gradient whose strength is its draw alpha: nothing is allocated per
                        // frame, and the clock is read only here (a redraw, never a recomposition). Each fade's rect
                        // runs on to the line's edge (the gradient stays black past its end): a fade rect and a margin
                        // rect meeting at a fractional x would each cover that pixel column only partly and leave a
                        // sliver of the hidden glyph there.
                        val w = size.width
                        val h = size.height
                        val g = ScrollPass.gutter(w)
                        val f = ScrollPass.edgeFade(w)
                        val margins = edges == ScrollEdges.Margins
                        // Where the text area starts: inside the left margin, or at the line's start.
                        val inner = if (margins) g else 0f
                        val leftFade = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = inner, endX = inner + f)
                        val rightFade = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = w - g - f, endX = w - g)
                        val rightGutter = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = w - g, endX = w)
                        onDrawWithContent {
                            drawContent()
                            val offset = pass.offsetAt(clock.longValue)
                            val left = ScrollPass.fadeStrength(offset, f)
                            if (left > 0f) drawRect(leftFade, Offset.Zero, Size(inner + f, h), alpha = left, blendMode = BlendMode.DstOut)
                            if (margins) {
                                val right = ScrollPass.fadeStrength(pass.distance - offset, f)
                                if (right > 0f) {
                                    drawRect(rightFade, Offset(w - g - f, 0f), Size(g + f, h), alpha = right, blendMode = BlendMode.DstOut)
                                }
                                drawRect(Color.Black, Offset.Zero, Size(g, h), blendMode = BlendMode.DstOut)
                                drawRect(Color.Black, Offset(w - g, 0f), Size(g, h), blendMode = BlendMode.DstOut)
                            } else {
                                drawRect(rightGutter, Offset(w - g, 0f), Size(g, h), blendMode = BlendMode.DstOut)
                            }
                        }
                    }
            ) {
                Text(
                    text,
                    Modifier
                        .layout { measurable, c ->
                            val p = measurable.measure(c.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                            layout(c.maxWidth, p.height) {
                                p.placeWithLayer(0, 0) { translationX = geometry.startX - pass.offsetAt(clock.longValue) }
                            }
                        }.then(textModifier),
                    color = color,
                    style = style,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
