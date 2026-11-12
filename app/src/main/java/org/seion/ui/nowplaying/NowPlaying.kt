package org.seion.ui.nowplaying

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.seion.R
import org.seion.art.ArtUris
import org.seion.art.CoverFramePolicy
import org.seion.art.FrameChoice
import org.seion.playback.PlayerUiState
import org.seion.settings.BackgroundStyle
import org.seion.settings.RepeatSetting
import org.seion.settings.SeionSettings
import org.seion.ui.Format
import org.seion.ui.MenuItem
import org.seion.ui.NOW_PLAYING_MENU
import org.seion.ui.ambient.Ambient
import org.seion.ui.ambient.AmbientRepository
import org.seion.ui.ambient.CoverGeometry
import org.seion.ui.ambient.CoverLook
import org.seion.ui.ambient.CoverLookPolicy
import org.seion.ui.ambient.FeatheredCover
import org.seion.ui.ambient.NowPlayingBackground
import org.seion.ui.components.IconBtn
import org.seion.ui.components.LocalContainer
import org.seion.ui.components.LocalReduceMotion
import org.seion.ui.components.MenuAction
import org.seion.ui.components.NoCoverTile
import org.seion.ui.components.NowPlayingArtistLine
import org.seion.ui.components.OverflowMenu
import org.seion.ui.components.ScrollingText
import org.seion.ui.theme.LocalAccent
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionMotion
import org.seion.ui.theme.SeionType

/** Now playing and the mini-player keep a fixed, device-like layout: text scaling is capped at 1.3 here. */
@Composable
fun CappedFontScale(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale.coerceAtMost(1.3f)), content = content)
}

/**
 * Bounds timing for the mini-player <-> now-playing shared elements: growing = 450 ms emphasized decelerate,
 * shrinking = 350 ms emphasized accelerate.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val NowPlayingBounds =
    BoundsTransform { initial, target ->
        if (target.height >= initial.height) {
            tween(SeionMotion.EXPAND_MS, easing = SeionMotion.EmphasizedDecelerate)
        } else {
            tween(SeionMotion.COLLAPSE_MS, easing = SeionMotion.EmphasizedAccelerate)
        }
    }

class NowPlayingActions(
    val togglePlay: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val seekTo: (Long) -> Unit,
    val toggleShuffle: () -> Unit,
    val cycleRepeat: () -> Unit,
    /** Back and the drag: collapse onto whatever is under now playing. */
    val collapse: () -> Unit,
    /** The library button: the last library screen (Settings and the queue are popped), then collapse. */
    val library: () -> Unit,
    val openQueue: () -> Unit,
    val openSettings: () -> Unit,
    val openSound: () -> Unit,
    /** ⋮ → Add to playlist: the current song into a playlist (the picker opens over Now playing). */
    val addToPlaylist: () -> Unit = {}
)

/**
 * Now playing: action row, cover (63.8 % of the width),
 * title (one line, ellipsized, never clipped), artist, album, times + index, seek bar, transport.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.NowPlayingScreen(
    state: PlayerUiState,
    position: StateFlow<Long>,
    ambient: Ambient?,
    settings: SeionSettings,
    visibility: AnimatedVisibilityScope,
    actions: NowPlayingActions
) {
    val density = LocalDensity.current
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val reduceMotion = LocalReduceMotion.current
    // A drag down moves the whole screen with the finger; let go past 21 % (or flung) it collapses from there, else it
    // springs back. Upwards nothing moves. The collapse transition itself only starts on release.
    val sheet = remember { SheetDragState() }
    val swipe = remember { CoverSwipeState() }
    val sheetEnd = remember { TouchEnd() }
    var heightPx by remember { mutableFloatStateOf(0f) }
    val shown = visibility.transition.targetState == EnterExitState.Visible
    // Fully open (not opening or closing): the long lines roll only then, so they all start together.
    val settled = shown && visibility.transition.currentState == EnterExitState.Visible
    // Re-opened while it was still collapsing (the same content comes back): back to its resting place.
    LaunchedEffect(shown) { if (shown) sheet.settle(reduceMotion) }
    // The sleep timer lives in the service; the indicator and the ⋮ entry show its time left.
    val playerConnection = LocalContainer.current.player
    val sleepTimer by playerConnection.sleepTimer.collectAsStateWithLifecycle()
    val sleepLeft = rememberSleepRemaining(sleepTimer)
    var sleepDialog by rememberSaveable { mutableStateOf(false) }
    if (sleepDialog) {
        SleepTimerDialog(
            sleepTimer,
            sleepLeft,
            onSet = playerConnection::setSleepTimer,
            onExtend = { playerConnection.extendSleepTimer() },
            onDismiss = { sleepDialog = false }
        )
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height.toFloat() }
            .pointerInput(Unit) { detectTapGestures { } }
            .watchTouchEnd(sheetEnd)
            .draggable(
                sheet.draggable,
                Orientation.Vertical,
                enabled = shown,
                onDragStopped = { v ->
                    // A drag the system cancelled springs back: only a real release collapses.
                    if (!sheetEnd.cancelled && sheetCollapses(sheet.offset, v, heightPx)) actions.collapse() else sheet.settle(reduceMotion)
                }
            ).testTag("now_playing")
    ) {
        val width = maxWidth
        val height = maxHeight
        // Clipped: the oversized far layer must not stay behind above the screen's top edge while it moves down.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationY = sheet.offset
                clip = sheet.offset > 0f
            }
        ) {
            // Container transform: this opaque backdrop grows from the mini-player's bar (shared bounds).
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
                    .background(Color.Black)
            )
            // Geometry: cover 248 dp at (56, 90) on a 360 x 640 dp reference layout; the text
            // block keeps a fixed rhythm (title baseline 380.5, artist 416.6, album 446.8, seek 486.5, transport
            // 545.8). Extra height on taller screens goes to the weighted gaps.
            val coverTop = statusTop + 66.dp
            // The layout assumes the 48 dp 3-button bar (nav centre 609.5 dp): reserve at least that much at the
            // bottom so a thin gesture bar doesn't push the seek bar and transport down into the gaps.
            val bottomReserve = maxOf(navBottom, 58.dp)
            val available = height - statusTop - bottomReserve
            val coverSize = min(width * (248f / 360f), available * 0.45f)
            // The optional spectrum band: the cover shrinks inside its slot, the text never moves.
            val band = rememberSpectrumBand(LocalContainer.current.dsp.spectrum, settings.spectrum, state.isPlaying, reduceMotion)
            val layout = spectrumLayout(band.progress, coverSize.value, coverTop.value, width.value)
            val shownCover = layout.cover.dp
            val geometry = CoverGeometry((width - shownCover) / 2, coverTop, shownCover)

            NowPlayingBackground(
                ambient = ambient,
                style = settings.backgroundStyle,
                geometry = geometry,
                drifting = state.isPlaying && settings.ambientDrift && !reduceMotion,
                // With the band drawing, the drift steps on the band's frames (no frames of its own).
                driftFrames = if (band.running) band::drawsAt else null,
                statusBarHeight = statusTop,
                coverShift = { a -> swipe.offsetOfAmbient(a.key) }
            )

            CappedFontScale {
                Column(Modifier.fillMaxSize().padding(top = statusTop, bottom = bottomReserve)) {
                    // Action row: shelf (back to the library) and the overflow menu, on the right-hand keyline.
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth().height(48.dp).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (sleepTimer.active) {
                            SleepTimerIndicator(sleepTimer, sleepLeft, onClick = { sleepDialog = true }, Modifier.padding(start = 8.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        IconBtn(
                            R.drawable.ic_shelf,
                            stringResource(R.string.library),
                            "np_library",
                            iconSize = 23.dp,
                            onClick = actions.library
                        )
                        OverflowMenu(
                            NOW_PLAYING_MENU.map { item ->
                                when (item) {
                                    MenuItem.ADD_TO_PLAYLIST -> MenuAction(
                                        stringResource(R.string.add_to_playlist),
                                        "np_menu_add_to_playlist",
                                        actions.addToPlaylist
                                    )
                                    MenuItem.QUEUE -> MenuAction(stringResource(R.string.play_queue), "np_menu_queue", actions.openQueue)
                                    MenuItem.SLEEP_TIMER -> MenuAction(sleepTimerMenuLabel(sleepTimer, sleepLeft), "np_menu_sleep") {
                                        sleepDialog = true
                                    }
                                    MenuItem.SETTINGS -> MenuAction(
                                        stringResource(R.string.settings),
                                        "np_menu_settings",
                                        actions.openSettings
                                    )
                                    MenuItem.SOUND -> MenuAction(stringResource(R.string.sound), "np_menu_sound", actions.openSound)
                                    MenuItem.RESCAN -> error("not in now playing's menu")
                                }
                            },
                            tag = "np_more"
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Box(Modifier.fillMaxWidth().height(coverSize)) {
                        CoverPager(state, ambient, settings, coverSize, shownCover, visibility, swipe, actions)
                        if (band.progress > 0f) {
                            SpectrumBars(
                                band,
                                Modifier
                                    .offset(x = layout.bandLeft.dp, y = (layout.bandTop - layout.coverTop).dp)
                                    .width(layout.bandWidth.dp)
                                    .height(layout.bandHeight.dp)
                                    .testTag("np_spectrum")
                            )
                        }
                    }
                    // 338 -> title line box 354 (cap top 362.2; the box is 1.4 em so descenders always fit).
                    Spacer(Modifier.height(16.dp))
                    Spacer(Modifier.weight(0.15f))
                    val meta = state.current?.mediaMetadata
                    // Title, artist and album: one line each; a line that doesn't fit rolls once (ScrollingText.kt), all
                    // three starting together once the screen has finished opening.
                    ScrollingText(
                        meta?.title?.toString().orEmpty(),
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .sharedBounds(
                                rememberSharedContentState("np-title"),
                                visibility,
                                boundsTransform = NowPlayingBounds,
                                // Each text keeps its own size and they cross-fade (scaling 15 sp text up looked bloated).
                                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
                            ),
                        style = SeionType.NpTitle,
                        textAlign = TextAlign.Center,
                        active = settled,
                        restartKey = state.current?.mediaId,
                        textModifier = Modifier.testTag("np_title")
                    )
                    Spacer(Modifier.height(8.6.dp))
                    // Every artist ("A, B, C"); a tap lists them all (ArtistLine.kt).
                    NowPlayingArtistLine(
                        meta?.artist?.toString(),
                        SeionType.NpSecondary,
                        "np_artist",
                        Modifier.padding(horizontal = 20.dp),
                        active = settled,
                        restartKey = state.current?.mediaId
                    )
                    Spacer(Modifier.height(6.5.dp))
                    ScrollingText(
                        meta?.albumTitle?.toString().orEmpty(),
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        style = SeionType.NpSecondary,
                        textAlign = TextAlign.Center,
                        active = settled,
                        restartKey = state.current?.mediaId,
                        textModifier = Modifier.testTag("np_album")
                    )
                    Spacer(Modifier.height(4.dp))
                    Spacer(Modifier.weight(0.15f))
                    TimeSeekRow(state, position, actions.seekTo)
                    Spacer(Modifier.height(10.8.dp))
                    Spacer(Modifier.weight(0.3f))
                    Transport(state, actions)
                    Spacer(Modifier.weight(0.4f))
                }
            }
        }
    }
}

/**
 * One cover on screen: the track, its art key, the decoded bitmap (null = no art: placeholder) and its own look
 * (dimming, feather), computed from that bitmap before the frame is published.
 */
private data class CoverFrame(
    val mediaId: String?,
    val refKey: String?,
    val bitmap: androidx.compose.ui.graphics.ImageBitmap?,
    val look: CoverLook?
)

/** The cover: slides horizontally on next/previous; a horizontal swipe skips. Shared with the mini-player. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.CoverPager(
    state: PlayerUiState,
    ambient: Ambient?,
    settings: SeionSettings,
    slotSize: Dp,
    coverSize: Dp,
    visibility: AnimatedVisibilityScope,
    swipe: CoverSwipeState,
    actions: NowPlayingActions
) {
    val density = LocalDensity.current
    val coverEnd = remember { TouchEnd() }
    val coverPx = with(density) { coverSize.toPx() }
    val flingPx = with(density) { 600.dp.toPx() } // 0.6 dp/ms
    // The cover shown is a frame (track + decoded bitmap or the no-cover tile). A new track's frame replaces the old one
    // once its local art is decoded (cover -> cover slide), but the old cover never stays longer than the 150 ms
    // grace: then the tile shows, and art that arrives later (online) cross-fades in place.
    val container = LocalContainer.current
    val reduceMotion = LocalReduceMotion.current
    val currentId = state.current?.mediaId
    val currentRef = ArtUris.fromUri(state.current?.mediaMetadata?.artworkUri)
    // The whole ref: an identified track keeps its key but gets the names its cover is looked up with.
    val artState by remember(currentRef, currentId) {
        container.art.state(currentRef, AmbientRepository.COVER_SIZE)
    }.collectAsStateWithLifecycle()
    val changedAt = remember(currentId) { SystemClock.uptimeMillis() }
    var frame by remember { mutableStateOf<CoverFrame?>(null) }
    LaunchedEffect(currentId, artState) {
        while (true) {
            val elapsed = SystemClock.uptimeMillis() - changedAt
            when (val choice = CoverFramePolicy.next(frame?.mediaId, currentId, artState, elapsed)) {
                FrameChoice.KeepPrevious -> {
                    if (frame?.mediaId == currentId) return@LaunchedEffect
                    delay(CoverFramePolicy.GRACE_MS - elapsed)
                }
                is FrameChoice.Show -> {
                    val raw = choice.bitmap
                    val look = raw?.let { withContext(Dispatchers.Default) { AmbientRepository.coverLook(it) } }
                    swipe.arrived(currentId, currentRef?.cacheKey)
                    frame = CoverFrame(currentId, currentRef?.cacheKey, raw?.asImageBitmap(), look)
                    return@LaunchedEffect
                }
            }
        }
    }
    // Direction of the slide: forward when the play-order position grew (computed once per frame change).
    val last = remember { intArrayOf(state.orderPosition) }
    val forward =
        remember(frame?.mediaId) {
            val f = state.orderPosition >= last[0] || last[0] < 0
            last[0] = state.orderPosition
            f
        }
    // The slot keeps its layout height; the cover draws at its (possibly band-shrunk) size, anchored top-centre.
    Box(
        Modifier
            .fillMaxWidth()
            .height(slotSize),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedContent(
            targetState = frame,
            contentKey = { it?.mediaId },
            transitionSpec = {
                val dir = if (forward) 1 else -1
                val d = SeionMotion.COVER_MS
                val fade = tween<Float>(SeionMotion.COVER_FADE_MS, easing = SeionMotion.CoverFadeEasing)
                (
                    slideInHorizontally(tween(d, easing = SeionMotion.CoverSlideEasing)) { it / 4 * dir } +
                        fadeIn(fade)
                    )
                    .togetherWith(
                        slideOutHorizontally(tween(d, easing = SeionMotion.CoverSlideEasing)) { -it / 4 * dir } +
                            fadeOut(fade)
                    )
                    // AnimatedContent clips to its box by default, which cuts the offset cover at the edge (it then
                    // reads as a wipe, not a slide). The covers slide over the ambient instead.
                    .using(SizeTransform(clip = false))
            },
            label = "cover",
            modifier =
            Modifier
                .size(coverSize)
                .sharedElement(rememberSharedContentState("np-cover"), visibility, boundsTransform = NowPlayingBounds)
                .watchTouchEnd(coverEnd)
                .draggable(
                    swipe.draggable,
                    Orientation.Horizontal,
                    onDragStarted = { swipe.begin(frame?.mediaId, frame?.refKey) },
                    onDragStopped = { velocity ->
                        // A drag the system cancelled never skips.
                        val skip = if (coverEnd.cancelled) CoverSkip.NONE else coverSkip(swipe.dragged, velocity, coverPx, flingPx)
                        when (skip) {
                            CoverSkip.NEXT -> actions.next()
                            CoverSkip.PREVIOUS -> actions.previous()
                            CoverSkip.NONE -> Unit
                        }
                        // The dragged cover springs back (as it slides out, when the track changed); the next cover is
                        // never offset, so it always lands centred.
                        swipe.settle(reduceMotion)
                    }
                ).testTag("np_cover")
        ) { f ->
            if (f == null) return@AnimatedContent
            // The drag offset is this cover's own (the draggable's hit area stays put in the slot).
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val dx = swipe.offsetOf(f.mediaId)
                        translationX = dx
                        alpha = swipeAlpha(dx, coverPx)
                    }
            ) {
                // Same track, new art (the tile, then the online cover): a 300 ms cross-fade in place, no slide.
                Crossfade(
                    targetState = f,
                    animationSpec = if (reduceMotion) snap() else tween(SeionMotion.ART_FADE_MS, easing = SeionMotion.Standard),
                    label = "art"
                ) { shown ->
                    val bmp = shown.bitmap
                    if (bmp != null) {
                        val look =
                            CoverLookPolicy.lookFor(
                                shown.refKey,
                                shown.look,
                                ambient?.key,
                                ambient?.params,
                                settings.backgroundStyle == BackgroundStyle.AMBIENT
                            )
                        FeatheredCover(
                            bmp,
                            brightness = look.brightness,
                            feather = look.feather,
                            featherWidth = look.featherWidth,
                            modifier = Modifier.fillMaxSize().testTag("np_cover_frame")
                        )
                    } else {
                        // No art: the no-cover tile, square and unfeathered.
                        NoCoverTile(Modifier.fillMaxSize().testTag("np_cover_frame"), 0.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeSeekRow(state: PlayerUiState, positionFlow: StateFlow<Long>, onSeek: (Long) -> Unit) {
    val position by positionFlow.collectAsStateLifecycle()
    var scrub by remember { mutableStateOf<Long?>(null) }
    val shown = scrub ?: position
    val duration = state.durationMs
    // The total shown is the library's duration (the lists show the same number); elapsed is clamped to it.
    val shownTotal = state.current?.mediaMetadata?.durationMs?.takeIf { it > 0 } ?: duration
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                Format.elapsed(shown, shownTotal),
                style = SeionType.NpTime,
                modifier = Modifier.weight(1f).testTag("np_elapsed"),
                maxLines = 1
            )
            val pos = state.orderPosition
            Text(
                if (pos >= 0) "${pos + 1}/${state.queue.size}" else "",
                style = SeionType.NpTime.copy(color = Color(0xFFA4A4A4)),
                modifier = Modifier.testTag("np_index"),
                maxLines = 1
            )
            Text(
                Format.duration(shownTotal),
                style = SeionType.NpTime,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).testTag("np_total"),
                maxLines = 1
            )
        }
        SeekBar(shown, duration, onScrub = { scrub = it }, onSeek = onSeek)
    }
}

@Composable
private fun <T> StateFlow<T>.collectAsStateLifecycle() = collectAsStateWithLifecycle()

/** How the seek bar's thumb moves to a new position (unit-tested in SteppedMotionTest). */
internal object SeekBarMotion {
    /** Moves smaller than this never glide. */
    const val SPRING_MIN_PX = 4f

    /** A forward move up to this long is a playback tick ([org.seion.playback.PositionTicks], ≤ 1 s, a little late). */
    const val TICK_MAX_MS = org.seion.playback.PositionTicks.MAX_STEP_MS + 100

    /**
     * Whether the thumb glides from [fromMs] to [toMs] (a tap, a seek, a skip) rather than snapping (a playback tick, on
     * any song length: on a 20 s song a tick is 8 px). Not before the bar is laid out or the length is known.
     */
    fun springs(fromMs: Long, toMs: Long, durationMs: Long, widthPx: Float): Boolean {
        if (durationMs <= 0 || widthPx <= 0f) return false
        val px = abs(toMs - fromMs).toFloat() / durationMs * widthPx
        if (px < SPRING_MIN_PX) return false
        return toMs < fromMs || toMs - fromMs > TICK_MAX_MS
    }
}

/**
 * 2 dp track (played white, rest #5A5A5A) with a 15.6 dp white thumb that follows a jump on a spring; playback ticks
 * (about a pixel each, [org.seion.playback.PositionTicks]) snap, so a tick costs one frame, not a spring's worth.
 */
@Composable
fun SeekBar(positionMs: Long, durationMs: Long, onScrub: (Long?) -> Unit, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var pressed by remember { mutableStateOf(false) }
    var widthPx by remember { mutableStateOf(1f) }
    val thumbScale by animateFloatAsState(
        if (pressed || drag != null) 1.4f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "thumb"
    )
    val target = drag ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    // The bar draws the target itself, except while a glide runs: a tick is then one frame (a state write from an effect
    // after the tick would cost a second one). A glide still running is re-aimed at each new position, keeping its
    // velocity (one collector, never restarted by a tick).
    val thumb = remember { Animatable(target) }
    val drawn = remember { floatArrayOf(target) }
    val dragging = drag != null
    val position by rememberUpdatedState(positionMs)
    val duration by rememberUpdatedState(durationMs)
    LaunchedEffect(dragging) {
        if (dragging) return@LaunchedEffect
        var last = position
        snapshotFlow { position }.collect { p ->
            val d = duration
            if (thumb.isRunning || SeekBarMotion.springs(last, p, d, widthPx)) {
                if (!thumb.isRunning) thumb.snapTo(drawn[0])
                val to = if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f
                launch { thumb.animateTo(to, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) }
            }
            last = p
        }
    }
    val desc = stringResource(R.string.seek)
    val elapsedText = Format.duration(positionMs)
    fun at(x: Float) = (x / widthPx).coerceIn(0f, 1f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics {
                contentDescription = desc
                stateDescription = elapsedText
                progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
                setProgress { v ->
                    onSeek((v * durationMs).toLong())
                    true
                }
            }.pointerInput(durationMs) {
                widthPx = size.width.toFloat()
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { o -> if (durationMs > 0) onSeek((at(o.x) * durationMs).toLong()) }
                )
            }.pointerInput(durationMs) {
                widthPx = size.width.toFloat()
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        drag = at(o.x)
                        onScrub((at(o.x) * durationMs).toLong())
                    },
                    onDragEnd = {
                        drag?.let { if (durationMs > 0) onSeek((it * durationMs).toLong()) }
                        drag = null
                        onScrub(null)
                    },
                    onDragCancel = {
                        drag = null
                        onScrub(null)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        drag = at(change.position.x)
                        onScrub(((drag ?: 0f) * durationMs).toLong())
                    }
                )
            }.testTag("np_seek")
    ) {
        val y = size.height / 2
        val stroke = 2.dp.toPx()
        val f = if (thumb.isRunning && !dragging) thumb.value else target
        drawn[0] = f
        val x = f * size.width
        drawLine(SeionColors.SeekTrack, Offset(0f, y), Offset(size.width, y), stroke)
        drawLine(Color.White, Offset(0f, y), Offset(x, y), stroke)
        drawCircle(
            Color.White,
            radius = 7.8.dp.toPx() * thumbScale,
            center = Offset(x.coerceIn(7.8.dp.toPx(), size.width - 7.8.dp.toPx()), y)
        )
    }
}

/** Shuffle · previous · play/pause · next · repeat, centred at 10.2 / 28.2 / 50 / 71.9 / 89.9 % of the width. */
@Composable
private fun Transport(state: PlayerUiState, actions: NowPlayingActions) {
    val accent = LocalAccent.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(72.dp)) {
        val w = maxWidth

        // requiredSize: at a large font scale the column can squeeze this row; the round buttons keep their shape (a
        // button taller than the row is centred on it by the layout itself).
        fun Modifier.at(fraction: Float, size: Dp) =
            this.offset(x = w * fraction - size / 2, y = ((maxHeight - size) / 2).coerceAtLeast(0.dp)).requiredSize(size)

        ToggleGlyph(
            icon = R.drawable.ic_shuffle,
            on = state.shuffle,
            accent = accent,
            description = stringResource(R.string.shuffle),
            stateText = stringResource(if (state.shuffle) R.string.toggle_on else R.string.toggle_off),
            tag = "np_shuffle",
            modifier = Modifier.at(0.102f, 48.dp),
            onClick = actions.toggleShuffle
        )
        RingButton(R.drawable.ic_prev, stringResource(R.string.previous), "np_prev", Modifier.at(0.282f, 52.8.dp), 26.dp, actions.previous)
        PlayPauseButton(state.isPlaying, Modifier.at(0.5f, 68.4.dp), actions.togglePlay)
        RingButton(R.drawable.ic_next, stringResource(R.string.next), "np_next", Modifier.at(0.719f, 52.8.dp), 26.dp, actions.next)
        ToggleGlyph(
            icon = if (state.repeat == RepeatSetting.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
            on = state.repeat != RepeatSetting.OFF,
            accent = accent,
            description = stringResource(R.string.repeat),
            stateText =
            stringResource(
                when (state.repeat) {
                    RepeatSetting.OFF -> R.string.repeat_off
                    RepeatSetting.ALL -> R.string.repeat_all
                    RepeatSetting.ONE -> R.string.repeat_one
                }
            ),
            tag = "np_repeat",
            modifier = Modifier.at(0.899f, 48.dp),
            onClick = actions.cycleRepeat
        )
    }
}

/** Shuffle / repeat: grey when off; accent colour plus a dot underneath when on. */
@Composable
private fun ToggleGlyph(
    @DrawableRes icon: Int,
    on: Boolean,
    accent: Color,
    description: String,
    stateText: String,
    tag: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                stateDescription = stateText
            }.testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(icon),
            null,
            tint = if (on) accent else SeionColors.Inactive,
            modifier = Modifier.size(width = 22.dp, height = 16.dp)
        )
        if (on) Box(Modifier.offset(y = 15.dp).size(4.dp).clip(CircleShape).background(accent).testTag("${tag}_on"))
    }
}

@Composable
private fun RingButton(@DrawableRes icon: Int, description: String, tag: String, modifier: Modifier, glyphWidth: Dp, onClick: () -> Unit) {
    Box(
        modifier
            .clip(CircleShape)
            .border(1.75.dp, Color.White, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.width(glyphWidth).height(glyphWidth * 0.486f))
    }
}

/** Play/pause: the glyph morphs (animated vector path). */
@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
fun PlayPauseButton(playing: Boolean, modifier: Modifier, onClick: () -> Unit, small: Boolean = false, tag: String = "np_play") {
    val desc = stringResource(if (playing) R.string.pause else R.string.play)
    Box(
        modifier
            .clip(CircleShape)
            .border(if (small) 1.5.dp else 1.75.dp, Color.White, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = desc }
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        // One path morphs between the triangle and the two bars (AnimatedImageVector, 200 ms).
        val avd = AnimatedImageVector.animatedVectorResource(R.drawable.avd_play_pause)
        val scale = if (small) 0.55f else 1f
        Icon(
            rememberAnimatedVectorPainter(avd, atEnd = playing),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.padding(start = if (playing) 0.dp else 2.5.dp * scale).size(22.dp * scale, 25.dp * scale)
        )
    }
}

/**
 * The cover's opacity while it is dragged [dx] px sideways: a light fade (to 85 % at most) as feedback. A deeper fade
 * left the slot nearly empty when a committed swipe cross-faded to the next cover.
 */
internal fun swipeAlpha(dx: Float, coverPx: Float): Float = 1f - 0.15f * (abs(dx) / coverPx).coerceIn(0f, 1f)

/** The swipe distance that skips a track on the mini-player. */
internal fun isSkip(dx: Float, threshold: Float) = abs(dx) > threshold
