package org.seion.ui.ambient

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.seion.settings.BackgroundStyle
import org.seion.ui.motion.SteppedMotion
import org.seion.ui.motion.rememberSteppedClock
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionMotion

/** Where the now-playing cover sits (the near layer and the cover are drawn around it). */
data class CoverGeometry(val left: Dp, val top: Dp, val size: Dp)

/** The current track's ambient (null without a track): Settings' background-style previews draw from it. */
val LocalAmbient = androidx.compose.runtime.compositionLocalOf<Ambient?> { null }

/** Saturation × brightness colour matrix for the far layer / dimming for the cover and its halo. */
fun ambientFilter(saturation: Float, brightness: Float): ColorFilter {
    val m = ColorMatrix().apply { setToSaturation(saturation) }
    val v = m.values
    for (row in 0..2) for (col in 0..3) v[row * 5 + col] *= brightness
    return ColorFilter.colorMatrix(ColorMatrix(v))
}

fun dimFilter(brightness: Float): ColorFilter? = if (brightness >= 0.999f) null else ambientFilter(1f, brightness)

/**
 * The full-screen now-playing background. Seion Infinite: far layer (drifting slowly while playing), near
 * edge-clamp layer around the cover, scrim + vignette + status band. Classic: one vertical gradient. Track changes
 * cross-fade over [SeionMotion.AMBIENT_MS]. Onyx: the app's own background colour (the Settings screens'), nothing
 * from the cover, no motion.
 */
@Composable
fun NowPlayingBackground(
    ambient: Ambient?,
    style: BackgroundStyle,
    geometry: CoverGeometry,
    drifting: Boolean,
    statusBarHeight: Dp,
    /** Display frames another animation draws anyway (the spectrum band): the drift then steps only on those. */
    driftFrames: ((Long) -> Boolean)? = null,
    /** How far (px) the cover of [Ambient] is dragged sideways: its near layer follows it (read in the draw phase). */
    coverShift: (Ambient) -> Float = { 0f }
) {
    if (style == BackgroundStyle.ONYX) {
        Box(Modifier.fillMaxSize().background(SeionColors.Page))
        return
    }
    // One clock for every far layer (an outgoing and an incoming track drift together through the cross-fade). It only
    // runs while drifting and visible, and stops where it is: a pause freezes the layer in place, Play goes on from there.
    val drift = rememberSteppedClock(drifting && style == BackgroundStyle.AMBIENT, SteppedMotion.DRIFT_STEP_MS, driftFrames)
    Crossfade(targetState = ambient, animationSpec = tween(SeionMotion.AMBIENT_MS, easing = SeionMotion.Standard), label = "ambient") { a ->
        Box(Modifier.fillMaxSize()) {
            if (a == null) return@Box
            if (style == BackgroundStyle.CLASSIC) {
                ClassicLayer(a)
            } else {
                FarLayerView(a) { SteppedMotion.driftPhase(drift.longValue) }
                // The halo around the cover moves with a dragged cover, so the cover never leaves a hole in it.
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = coverShift(a) }) { NearLayerView(a, geometry) }
            }
        }
    }
    if (style == BackgroundStyle.AMBIENT) Scrim(ambient?.params ?: AmbientParams.DEFAULT, statusBarHeight)
}

@Composable
private fun FarLayerView(a: Ambient, phase: () -> Float) {
    val filter = remember(a) { ambientFilter(a.params.saturation, a.params.farBrightness) }
    // 38 s loop of scale/rotate/translate, drawn in 8 steps a second (every vsync would spend battery on 0.07 px of
    // motion per frame); frozen when paused, disabled, or with reduced motion. Read in
    // the draw phase: a step re-draws the layer, nothing recomposes.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            a.far,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = filter,
            modifier =
            Modifier
                .requiredSize(maxWidth * FarLayer.OVERSIZE_X, maxHeight * FarLayer.OVERSIZE_Y)
                .graphicsLayer {
                    val p = phase()
                    scaleX = 1f + 0.08f * p
                    scaleY = 1f + 0.08f * p
                    rotationZ = -3f + 6f * p
                    translationY = (p - 0.5f) * 24.dp.toPx()
                }
        )
    }
}

@Composable
private fun NearLayerView(a: Ambient, g: CoverGeometry) {
    val filter = remember(a) { dimFilter(a.params.coverBrightness) }
    Image(
        a.near,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        colorFilter = filter,
        modifier = Modifier.nearLayerBounds(g)
    )
}

/**
 * Places the near layer at exactly the cover ± [NearLayer.MARGIN] × its size, so the blurred cover at its centre sits
 * right behind the cover and only the edge-clamped halo shows around it (what [TextSampler] samples). The layer is
 * wider than the screen: `Modifier.size` clamped it to the parent's width, which squeezed it and drew a blurred copy
 * of the cover to the left of the real one, and `requiredSize` would centre it instead.
 */
internal fun Modifier.nearLayerBounds(g: CoverGeometry): Modifier = layout { measurable, constraints ->
    val m = NearLayer.MARGIN
    val side = (g.size * (1 + 2 * m)).roundToPx()
    val placeable = measurable.measure(Constraints.fixed(side, side))
    val w = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val h = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
    layout(w, h) { placeable.place((g.left - g.size * m).roundToPx(), (g.top - g.size * m).roundToPx()) }
}

@Composable
private fun ClassicLayer(a: Ambient) {
    val base = a.classicBase
    Canvas(Modifier.fillMaxSize()) {
        val stops =
            ClassicGradient.STOPS
                .map { (pos, f) -> pos to Color(base.red * f.toFloat(), base.green * f.toFloat(), base.blue * f.toFloat()) }
                .toTypedArray()
        drawRect(Brush.verticalGradient(*stops))
    }
}

/** Vertical scrim profile (clear to ~47 %, then 0.40 / 0.52 / 0.66 / 0.78 × scrim), vignette and status band. */
@Composable
private fun Scrim(p: AmbientParams, statusBarHeight: Dp) {
    // Scrim strength and status band animate with the 700 ms cross-fade, so a bright-to-dark change never snaps.
    val scrim by animateFloatAsState(p.scrim, tween(SeionMotion.AMBIENT_MS, easing = SeionMotion.Standard), label = "scrim")
    val status by animateFloatAsState(p.statusScrim, tween(SeionMotion.AMBIENT_MS, easing = SeionMotion.Standard), label = "status")
    Canvas(Modifier.fillMaxSize()) {
        val s = scrim
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.47f to Color.Transparent,
                0.5625f to Color.Black.copy(alpha = 0.40f * s),
                0.734f to Color.Black.copy(alpha = 0.52f * s),
                0.89f to Color.Black.copy(alpha = 0.66f * s),
                1f to Color.Black.copy(alpha = 0.78f * s)
            )
        )
        drawRect(
            Brush.radialGradient(
                0.55f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.35f),
                center = Offset(size.width / 2, size.height * 0.42f),
                radius = size.maxDimension * 0.75f
            )
        )
        val band = statusBarHeight.toPx() + 30.dp.toPx()
        drawRect(
            Brush.verticalGradient(0f to Color.Black.copy(alpha = status), 1f to Color.Transparent, endY = band),
            size = size.copy(height = band)
        )
    }
}

/** The cover with feathered edges (9 % alpha ramp per side, intersected), dimmed with its halo for bright art. */
@Composable
fun FeatheredCover(bitmap: ImageBitmap, brightness: Float, feather: Boolean, modifier: Modifier = Modifier, featherWidth: Float = 0.09f) {
    val filter = remember(brightness) { dimFilter(brightness) }
    Image(
        bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        colorFilter = filter,
        modifier =
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (feather) {
                    val ramp = listOf(
                        0f to Color.Transparent,
                        featherWidth to Color.Black,
                        (1f - featherWidth) to Color.Black,
                        1f to Color.Transparent
                    )
                    drawRect(Brush.horizontalGradient(*ramp.toTypedArray()), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    drawRect(Brush.verticalGradient(*ramp.toTypedArray()), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                }
            }
    )
}

/** Browse screens: a faint palette tint at the top and the cover's blurred glow behind the mini-player. */
@Composable
fun BrowseBackdrop(ambient: Ambient?) {
    Crossfade(
        targetState = ambient,
        animationSpec = tween(SeionMotion.AMBIENT_MS, easing = SeionMotion.Standard),
        label = "backdrop"
    ) { a ->
        if (a == null) return@Crossfade
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    // A faint palette tint at the top only; lists stay on near-black.
                    Brush.verticalGradient(0f to a.tint.copy(alpha = 0.2f), 1f to Color.Transparent, endY = 200.dp.toPx())
                )
            }
        }
    }
}
