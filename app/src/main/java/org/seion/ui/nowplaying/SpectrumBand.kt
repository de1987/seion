package org.seion.ui.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import org.seion.playback.viz.SpectrumModel
import org.seion.playback.viz.SpectrumTap
import org.seion.ui.motion.SpectrumPacing
import org.seion.ui.theme.SeionMotion

/**
 * The band's geometry on the 360 dp reference layout, in dp: the cover shrinks 248 -> 200 keeping its top
 * and centre, a 44 dp band spans the text column (x 20–340) from y 296 to 340, and the title box (354) never moves.
 * [slot] is the cover's layout size (248 on the reference), [slotTop] its top, [width] the screen width; the cover and
 * the band scale with the slot, the text column keeps its 20 dp margins.
 */
data class SpectrumLayout(
    val cover: Float,
    val coverLeft: Float,
    val coverTop: Float,
    val bandLeft: Float,
    val bandTop: Float,
    val bandWidth: Float,
    val bandHeight: Float,
    val titleTop: Float
)

fun spectrumLayout(progress: Float, slot: Float = 248f, slotTop: Float = 90f, width: Float = 360f): SpectrumLayout {
    val p = progress.coerceIn(0f, 1f)
    val k = slot / 248f
    val cover = (248f - 48f * p) * k
    return SpectrumLayout(
        cover = cover,
        coverLeft = (width - cover) / 2,
        coverTop = slotTop,
        bandLeft = 20f,
        bandTop = slotTop + (250f - 44f * p) * k,
        bandWidth = width - 40f,
        bandHeight = 44f * p * k,
        titleTop = slotTop + slot + 16f
    )
}

/** The band's state: whether it is open, its animated progress and the bars' fade, the model it draws. */
class SpectrumBandState {
    /** Fell quiet after a pause (or never played): closed until playback starts again. */
    internal var quiet by mutableStateOf(true)

    /** The open/close progress (0..1) and the bars' fade, as animated states. */
    internal var progressState: State<Float> = mutableStateOf(0f)
    internal var alphaState: State<Float> = mutableStateOf(0f)
    val progress: Float get() = progressState.value
    var model: SpectrumModel? = null
        internal set

    /** Bumped once per analysed display frame: the bars' Canvas reads it to redraw. */
    internal var frame by mutableIntStateOf(0)

    /** The analyser loop runs (it draws 30 frames a second); [drawsAt] tells other motion which frames those are. */
    var running by mutableStateOf(false)
        internal set

    /** The display frame time of the last analysed frame (0: none yet). */
    internal var lastFrameNs = 0L

    /** Whether the display frame at [frameNs] is one the band analyses and draws (before or after the band's turn). */
    fun drawsAt(frameNs: Long): Boolean = frameNs == lastFrameNs || SpectrumPacing.due(frameNs, lastFrameNs)
}

/**
 * Drives the band: the tap copies audio only while the setting is on, now playing is composed,
 * the activity is started (screen on), playback runs or the bars are still falling, and motion is allowed. Opens when
 * playing (450 ms emphasized decelerate), closes after the pause fall-out or when switched off (350 ms emphasized
 * accelerate); the bars fade in over the last 40 % of the open and out over the first 40 % of the close.
 */
@Composable
fun rememberSpectrumBand(tap: SpectrumTap, on: Boolean, playing: Boolean, reduceMotion: Boolean): SpectrumBandState {
    val state = remember { SpectrumBandState() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val active = on && !reduceMotion && visible
    LaunchedEffect(active, playing) {
        if (!active) {
            state.quiet = true
        } else if (playing) {
            state.quiet = false
        }
    }
    val open = active && (playing || !state.quiet)
    state.progressState =
        animateFloatAsState(
            if (open) 1f else 0f,
            if (open) {
                tween(SeionMotion.EXPAND_MS, easing = SeionMotion.EmphasizedDecelerate)
            } else {
                tween(SeionMotion.COLLAPSE_MS, easing = SeionMotion.EmphasizedAccelerate)
            },
            label = "spectrumBand"
        )
    state.alphaState =
        animateFloatAsState(
            if (open) 1f else 0f,
            if (open) {
                tween(SeionMotion.EXPAND_MS * 4 / 10, delayMillis = SeionMotion.EXPAND_MS * 6 / 10)
            } else {
                tween(SeionMotion.COLLAPSE_MS * 4 / 10)
            },
            label = "spectrumBars"
        )
    val tapOn = active && (playing || open)
    DisposableEffect(tap, tapOn) {
        tap.enabled = tapOn
        onDispose { tap.enabled = false }
    }
    LaunchedEffect(tapOn, playing) {
        state.running = tapOn
        if (!tapOn) {
            state.model?.reset()
            return@LaunchedEffect
        }
        var buf = FloatArray(0)
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            // 30 fps on any display, to save battery: the frames in between neither analyse nor draw. dt keeps the bars'
            // physics in real time.
            if (!SpectrumPacing.due(now, last)) continue
            val dt = if (last == 0L) 1f / 30 else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            state.lastFrameNs = now
            val fs = tap.sampleRate
            if (fs <= 0) continue
            val model = state.model?.takeIf { it.sampleRate == fs } ?: SpectrumModel(fs).also { state.model = it }
            if (buf.size != model.fftSize) buf = FloatArray(model.fftSize)
            val frame = tap.audibleNow(System.nanoTime(), playing)?.let(tap::frameAt)
            if (playing && frame != null && tap.read(frame, model.fftSize, buf)) model.analyze(buf) else model.silence()
            // The bars grow in with their fade, not while the cover is still shrinking.
            if (state.alphaState.value <= 0f) model.holdReveal()
            model.advance(dt)
            state.frame++
            if (!playing && model.fellQuiet) state.quiet = true
        }
    }
    return state
}

/**
 * Where the bars sit in a band [width] wide (any unit): the layout's pitch and bar (13.33 and 9.33 of the 320 dp band,
 * a 4 dp gap, scaling with the band), [count] bars as one group centred in the band, so the 14 bars keep the width
 * the 24 had and leave equal margins left and right. Bar b spans [x] (b) to [x] (b) + [bar].
 */
data class SpectrumBarLayout(val count: Int, val pitch: Float, val bar: Float, val left: Float) {
    fun x(b: Int) = left + b * pitch

    /** The free space right of the last bar (equals [left] when the group is centred). */
    fun right(width: Float) = width - (x(count - 1) + bar)
}

/** The bar pitch as a fraction of the band: 13.33 dp of 320 (a 24-bar grid). */
private const val BAR_PITCH = 1f / 24

fun spectrumBarLayout(width: Float, count: Int = SpectrumModel.BANDS): SpectrumBarLayout {
    val pitch = width * BAR_PITCH
    val bar = pitch * (9.33f / 13.33f)
    return SpectrumBarLayout(count, pitch, bar, (width - (count - 1) * pitch - bar) / 2)
}

/**
 * [SpectrumModel.BANDS] white bars ([spectrumBarLayout], 92 %) with 1.5 dp caps (85 %), filling [Modifier]'s box from
 * the bottom.
 */
@Composable
fun SpectrumBars(state: SpectrumBandState, modifier: Modifier) {
    Canvas(modifier) {
        state.frame // redraw per analysed frame
        val model = state.model ?: return@Canvas
        val alpha = state.alphaState.value
        if (alpha <= 0f || size.height <= 0f) return@Canvas
        val geo = spectrumBarLayout(size.width)
        val bar = geo.bar
        val cap = 1.5f * density
        val bars = Color.White.copy(alpha = 0.92f * alpha)
        val caps = Color.White.copy(alpha = 0.85f * alpha)
        for (b in 0 until geo.count) {
            val x = geo.x(b)
            val h = model.shown[b] * size.height
            if (h > 0.5f) drawRect(bars, Offset(x, size.height - h), Size(bar, h))
            val capY = size.height - model.shownCaps[b] * size.height - cap
            if (model.shownCaps[b] > 0.005f) drawRect(caps, Offset(x, capY.coerceAtLeast(0f)), Size(bar, cap))
        }
    }
}
