package org.seion.ui.nowplaying

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import org.seion.R
import org.seion.art.ArtUris
import org.seion.playback.PlayerUiState
import org.seion.ui.ambient.Ambient
import org.seion.ui.components.FadingCover
import org.seion.ui.components.LocalReduceMotion
import org.seion.ui.components.MiniArtistLine
import org.seion.ui.components.ScrollEdges
import org.seion.ui.components.ScrollingText
import org.seion.ui.components.rememberCover
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionMotion

const val MINI_PLAYER_HEIGHT = 66

/**
 * The mini-player docked above the navigation bar on every browse screen: position line, cover (fading out to the
 * right), title, artist, ringed play/pause. Tap or drag up expands now playing; swiping the text skips.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.MiniPlayerLayer(
    state: PlayerUiState,
    position: StateFlow<Long>,
    ambient: Ambient?,
    visibility: AnimatedVisibilityScope,
    onExpand: () -> Unit,
    onDragUp: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val meta = state.current?.mediaMetadata
    val ref = ArtUris.fromUri(meta?.artworkUri)
    val bmp = if (ambient != null && ref != null && ambientIsFor(ambient.key, ref.cacheKey)) ambient.cover else rememberCover(ref, 720.dp)
    // Only a drag that really went up scrubs the expand: a drag down on the bar reports nothing (it would
    // otherwise scrub the transition to 0 and then animate it).
    var up by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }
    val drag =
        rememberDraggableState { d ->
            val next = (up - d).coerceAtLeast(0f)
            if (next != up) {
                up = next
                scrubbing = true
                onDragUp(next)
            }
        }
    val skipThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    val reduceMotion = LocalReduceMotion.current
    // Fully shown (not appearing or leaving): the long lines roll only then.
    val settled = visibility.transition.currentState == EnterExitState.Visible &&
        visibility.transition.targetState == EnterExitState.Visible
    val openDesc = stringResource(R.string.open_now_playing)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        CappedFontScale {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(MINI_PLAYER_HEIGHT.dp)
                        .draggable(drag, Orientation.Vertical, onDragStopped = { v ->
                            if (scrubbing) onDragEnd(-v)
                            scrubbing = false
                            up = 0f
                        })
                        .clickable(onClick = onExpand)
                        .semantics { contentDescription = openDesc }
                        .testTag("mini_player")
                ) {
                    // The container now playing grows from (shared bounds), under the bar's content.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .sharedBounds(
                                rememberSharedContentState("np-container"),
                                visibility,
                                boundsTransform = NowPlayingBounds,
                                // Drawn in place (under the ambient layers and controls), not in the transition overlay above
                                // them: the overlay hid everything but the cover and title until the transform ended.
                                renderInOverlayDuringTransition = false
                            )
                            .background(Color(0xFF0A0B0D))
                    ) {
                        // Opaque, like a backdrop blur: the cover's pre-blurred far layer under a dark veil
                        // (list rows scrolling behind never show through); without a cover, the ink with its top glow.
                        if (ambient != null) {
                            androidx.compose.foundation.Image(
                                ambient.mini,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                colorFilter = remember(ambient) { org.seion.ui.ambient.ambientFilter(1.4f, 0.8f) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Box(Modifier.fillMaxSize().background(Color(0x990A0B0D)))
                    }
                    MiniProgress(position, state.durationMs)
                    Row(Modifier.fillMaxSize().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        FadingCover(
                            bmp,
                            Modifier
                                .size(64.dp)
                                .sharedElement(rememberSharedContentState("np-cover"), visibility, boundsTransform = NowPlayingBounds)
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithContent {
                                    drawContent()
                                    drawRect(
                                        Brush.horizontalGradient(0.8f to Color.Black, 1f to Color.Transparent),
                                        blendMode = BlendMode.DstIn
                                    )
                                },
                            corner = 0.dp,
                            fadeMs = SeionMotion.ART_FADE_MS
                        )
                        Spacer(Modifier.width(10.dp))
                        // The text follows the finger and springs back; a new drag goes on from where the spring is,
                        // and a drag the system cancels skips nothing and leaves no offset behind.
                        val swipeText = remember { SpringBackDragState() }
                        val textEnd = remember { TouchEnd() }
                        var dragStart by remember { mutableFloatStateOf(0f) }
                        Column(
                            Modifier
                                .weight(1f)
                                .watchTouchEnd(textEnd)
                                .draggable(
                                    swipeText.draggable,
                                    Orientation.Horizontal,
                                    onDragStarted = { dragStart = swipeText.offset },
                                    onDragStopped = {
                                        val dx = swipeText.offset - dragStart
                                        if (!textEnd.cancelled && isSkip(dx, skipThreshold)) if (dx < 0) onNext() else onPrevious()
                                        swipeText.settle(reduceMotion)
                                    }
                                ).graphicsLayer { translationX = swipeText.offset * 0.3f }
                                .testTag("mini_text")
                        ) {
                            // Title and artist: a line that doesn't fit rolls once (ScrollingText.kt), starting where a line that fits does.
                            ScrollingText(
                                meta?.title?.toString().orEmpty(),
                                Modifier.sharedBounds(
                                    rememberSharedContentState("np-title"),
                                    visibility,
                                    boundsTransform = NowPlayingBounds,
                                    // Each text keeps its own size and they cross-fade (scaling 15 sp text up looked bloated).
                                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
                                ),
                                style = LocalTextStyle.current.merge(TextStyle(color = SeionColors.Ink, fontSize = 15.sp)),
                                active = settled,
                                restartKey = state.current?.mediaId,
                                textModifier = Modifier.testTag("mini_title"),
                                // Rests at the column start, lined up with the artist line whether either overflows.
                                edges = ScrollEdges.StartAligned
                            )
                            // Every artist ("A, B, C"); TalkBack reads them all (ArtistLine.kt).
                            MiniArtistLine(
                                meta?.artist?.toString(),
                                LocalTextStyle.current.merge(TextStyle(color = Color(0xFFB5B6BB), fontSize = 12.5.sp)),
                                "mini_artist",
                                Modifier.padding(top = 3.dp),
                                active = settled,
                                restartKey = state.current?.mediaId
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        PlayPauseButton(state.isPlaying, Modifier.size(40.dp), onTogglePlay, small = true, tag = "mini_play")
                        Spacer(Modifier.width(14.dp))
                    }
                }
                // The navigation bar area sits on the bottom scrim.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsBottomHeight(WindowInsets.navigationBars)
                        .background(Color(0xFF050507))
                )
            }
        }
    }
}

@Composable
private fun MiniProgress(position: StateFlow<Long>, durationMs: Long) {
    val pos by position.collectAsStateWithLifecycle()
    Canvas(Modifier.fillMaxWidth().height(2.dp).testTag("mini_progress")) {
        drawRect(Color(0x2EFFFFFF))
        val f = if (durationMs > 0) (pos.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        drawRect(Color.White, topLeft = Offset.Zero, size = size.copy(width = size.width * f))
    }
}
