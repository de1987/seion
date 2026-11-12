package org.seion.ui

import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.seion.R
import org.seion.art.ArtState
import org.seion.art.ArtUris
import org.seion.art.CoverFramePolicy
import org.seion.playlists.toNewEntry
import org.seion.ui.ambient.Ambient
import org.seion.ui.ambient.AmbientRepository
import org.seion.ui.ambient.BrowseBackdrop
import org.seion.ui.ambient.LocalAmbient
import org.seion.ui.components.LocalContainer
import org.seion.ui.components.LocalCoveredByNowPlaying
import org.seion.ui.components.LocalReduceMotion
import org.seion.ui.library.AddRequest
import org.seion.ui.library.AddToPlaylistHost
import org.seion.ui.library.AlbumScreen
import org.seion.ui.library.AlbumsScreen
import org.seion.ui.library.ArtistScreen
import org.seion.ui.library.ArtistsScreen
import org.seion.ui.library.BrowseActions
import org.seion.ui.library.EntryScroll
import org.seion.ui.library.FolderRootsScreen
import org.seion.ui.library.FolderScreen
import org.seion.ui.library.GenreScreen
import org.seion.ui.library.GenresScreen
import org.seion.ui.library.LibraryHome
import org.seion.ui.library.LocalBottomInset
import org.seion.ui.library.LocalBrowse
import org.seion.ui.library.LocalEntryScroll
import org.seion.ui.library.LocalPlayerState
import org.seion.ui.library.LocalPlaylistPicker
import org.seion.ui.library.PlaylistPicker
import org.seion.ui.library.PlaylistScreen
import org.seion.ui.library.PlaylistsScreen
import org.seion.ui.library.QueueScreen
import org.seion.ui.library.RecentScreen
import org.seion.ui.library.SearchScreen
import org.seion.ui.library.SongsScreen
import org.seion.ui.nowplaying.MINI_PLAYER_HEIGHT
import org.seion.ui.nowplaying.MiniPlayerLayer
import org.seion.ui.nowplaying.NowPlayingActions
import org.seion.ui.nowplaying.NowPlayingScreen
import org.seion.ui.nowplaying.cancelSeek
import org.seion.ui.settings.AlbumArtSettingsScreen
import org.seion.ui.settings.FolderPickerScreen
import org.seion.ui.settings.LicensesScreen
import org.seion.ui.settings.MusicFoldersScreen
import org.seion.ui.settings.SettingsScreen
import org.seion.ui.settings.SoundScreen
import org.seion.ui.theme.LocalAccent
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionMotion

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SeionRoot(vm: SeionViewModel) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val res = LocalResources.current
    val reduce = LocalReduceMotion.current
    val state by c.player.state.collectAsStateWithLifecycle()
    val settings by c.settingsState.collectAsStateWithLifecycle()
    val gen by c.art.generationFlow.collectAsStateWithLifecycle()
    val ref = ArtUris.fromUri(state.current?.mediaMetadata?.artworkUri)
    val hasTrack = state.current != null
    // The ambient follows the same art state as the cover: the cover's ambient when found, the shared
    // no-cover ambient when there is no art, or while it is still loading after the 150 ms grace.
    val artState by remember(ref) { c.art.state(ref, AmbientRepository.COVER_SIZE) }.collectAsStateWithLifecycle()
    val ambient by produceState<Ambient?>(ref?.let { c.ambient.cached(it) }, ref?.cacheKey, artState, gen, hasTrack) {
        if (!hasTrack) {
            value = null
            return@produceState
        }
        when (val a = artState) {
            is ArtState.Found -> value = if (ref != null) c.ambient.forBitmap(ref, a.bitmap) else c.ambient.noCover()
            is ArtState.Missing -> value = c.ambient.noCover()
            ArtState.Loading -> {
                delay(CoverFramePolicy.GRACE_MS)
                value = c.ambient.noCover()
            }
        }
    }
    // Prefetch the next track's ambient so a skip never waits for the blur.
    LaunchedEffect(state.currentIndex, state.queue.size, gen) {
        val pos = state.orderPosition
        state.queue.getOrNull(pos + 1)?.let { e -> ArtUris.fromUri(e.item.mediaMetadata.artworkUri)?.let { c.ambient.load(it) } }
    }
    LaunchedEffect(Unit) {
        c.player.errors.collect { issue ->
            val msg =
                when {
                    issue.unsupportedFormat -> R.string.error_unsupported
                    issue.stopped -> R.string.error_stopped
                    else -> R.string.error_skipped
                }
            val text = res.getString(msg, issue.title)
            android.util.Log.i("SeionUi", "message: $text")
            Toast.makeText(context, text, if (issue.stopped) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        c.onlineNotices.collect { Toast.makeText(context, res.getString(R.string.online_notice), Toast.LENGTH_LONG).show() }
    }

    val hasCurrent = state.current != null
    val accent by animateColorAsState(
        ambient?.accent ?: SeionColors.Accent,
        tween(SeionMotion.AMBIENT_MS, easing = SeionMotion.Standard),
        label = "accent"
    )
    val scope = rememberCoroutineScope()
    // Now playing open/closed lives in the view model's saved state: it survives the activity or the process being
    // destroyed while Seion is in the background.
    val np = remember { SeekableTransitionState(vm.nowPlaying) }
    fun npTo(expanded: Boolean) {
        vm.nowPlaying = expanded
        scope.launch {
            when {
                reduce -> np.snapTo(expanded)
                // A scrub (predictive back, the mini-player's drag) let go: back the way it came.
                expanded == np.currentState && np.targetState != expanded -> np.cancelSeek(reduceMotion = false)
                else -> np.animateTo(expanded)
            }
        }
    }
    ExpandOnRequest(vm, hasCurrent) { npTo(true) }
    // Closes only when the queue is really gone, not while a reconnect is still restoring it (NowPlayingRule).
    val queueGone = NowPlayingRule.queueGone(state)
    LaunchedEffect(queueGone) { if (queueGone && (np.currentState || np.targetState)) npTo(false) }
    val expanded = np.targetState || np.currentState

    // A focused text field (Search) under Now playing kept its caret blinking, a full-window redraw twice a second even
    // while paused: starting a song or covering the stack drops the focus and the keyboard.
    // Back on Search, a tap on the field brings the keyboard again.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dropFocus = {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
    }
    val browse =
        remember(vm) {
            BrowseActions(
                vm = vm,
                play = { items, start, shuffle ->
                    dropFocus()
                    c.player.play(items, start, shuffle)
                    // Starting a track opens now playing.
                    vm.requestExpand()
                },
                rescan = { c.scans.scan(force = true) }
            )
        }
    val navBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val densityValue = LocalDensity.current.density

    val picker = remember { PlaylistPicker() }
    val coverPicker = remember { org.seion.ui.library.CoverPicker() }
    val detailsSheet = remember { org.seion.ui.library.DetailsSheet() }
    CompositionLocalProvider(
        org.seion.ui.library.LocalCoverPicker provides coverPicker,
        org.seion.ui.library.LocalDetailsSheet provides detailsSheet,
        LocalAccent provides accent,
        LocalAmbient provides ambient,
        LocalBrowse provides browse,
        LocalPlaylistPicker provides picker,
        LocalPlayerState provides state,
        LocalBottomInset provides if (hasCurrent) MINI_PLAYER_HEIGHT.dp + navBottom else 0.dp
    ) {
        SharedTransitionLayout(Modifier.fillMaxSize().background(SeionColors.Page)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
                BrowseBackdrop(ambient)
                // While now playing covers the screen, the browse stack underneath is hidden from accessibility
                // services (TalkBack must not reach controls behind the overlay).
                val covered = np.currentState && np.targetState
                LaunchedEffect(covered) { if (covered) dropFocus() }
                Box(Modifier.fillMaxSize().then(if (covered) Modifier.clearAndSetSemantics { } else Modifier)) {
                    CompositionLocalProvider(LocalCoveredByNowPlaying provides covered) {
                        BrowseHost(vm, enabled = !np.currentState && !np.targetState)
                    }
                }

                // Predictive back on now playing scrubs the collapse; commit collapses, cancel springs back. Enabled on
                // currentState only: seeking flips targetState to false mid-gesture, and disabling the handler then
                // cancelled the gesture, which snapped now playing back open.
                PredictiveBackHandler(enabled = np.currentState) { progress ->
                    try {
                        progress.collect { e -> np.seekTo(e.progress * 0.9f, targetState = false) }
                        npTo(false)
                    } catch (e: CancellationException) {
                        npTo(true)
                        throw e
                    }
                }

                if (hasCurrent || expanded) {
                    var dragFraction by remember { mutableFloatStateOf(0f) }
                    val npTransition = rememberTransition(np, label = "nowPlaying")
                    npTransition.AnimatedContent(
                        transitionSpec = { npTransform(targetState, reduce, densityValue) },
                        contentAlignment = Alignment.BottomCenter,
                        modifier = Modifier.fillMaxSize()
                    ) { isExpanded ->
                        if (isExpanded) {
                            NowPlayingScreen(
                                state = state,
                                position = c.player.position,
                                ambient = ambient,
                                settings = settings,
                                visibility = this,
                                actions =
                                NowPlayingActions(
                                    togglePlay = c.player::togglePlay,
                                    next = c.player::next,
                                    previous = c.player::previous,
                                    seekTo = c.player::seekTo,
                                    toggleShuffle = c.player::toggleShuffle,
                                    cycleRepeat = c.player::cycleRepeat,
                                    collapse = { npTo(false) },
                                    library = {
                                        vm.showLibrary()
                                        npTo(false)
                                    },
                                    openQueue = {
                                        vm.push(Screen.Queue)
                                        npTo(false)
                                    },
                                    openSettings = {
                                        vm.push(Screen.Settings)
                                        npTo(false)
                                    },
                                    openSound = {
                                        vm.push(Screen.Sound)
                                        npTo(false)
                                    },
                                    addToPlaylist = {
                                        state.current?.let { item ->
                                            val song = item.toNewEntry()
                                            picker.open(AddRequest(song.title) { listOf(song) })
                                        }
                                    }
                                )
                            )
                        } else {
                            MiniPlayerLayer(
                                state = state,
                                position = c.player.position,
                                ambient = ambient,
                                visibility = this,
                                onExpand = { npTo(true) },
                                onDragUp = { px ->
                                    dragFraction = (px / (heightPx * 0.6f)).coerceIn(0f, 1f)
                                    scope.launch { np.seekTo(dragFraction, targetState = true) }
                                },
                                onDragEnd = { v -> npTo(dragFraction > 0.25f || v > 1800f).also { dragFraction = 0f } },
                                onTogglePlay = c.player::togglePlay,
                                onNext = c.player::next,
                                onPrevious = c.player::previous
                            )
                        }
                    }
                }
                // The Add to playlist picker (a sheet window): from the library, the queue and Now playing alike.
                AddToPlaylistHost()
                // Details (touch and hold, album ⋮) and Change cover's photo picker.
                org.seion.ui.library.DetailsHost()
                org.seion.ui.library.CoverPickerHost()
            }
        }
    }
}

private fun npTransform(expanding: Boolean, reduce: Boolean, density: Float): ContentTransform {
    if (reduce) return fadeIn(snap()) togetherWith fadeOut(snap())
    val rise = (SeionMotion.EXPAND_RISE_DP * density).toInt()
    return if (expanding) {
        // 450 ms emphasized decelerate: content rises 48 dp and fades in over the first 40 %; the mini-player fades
        // out over the first 30 %; the cover and title fly as shared elements.
        val d = SeionMotion.EXPAND_MS
        (slideInVertically(tween(d, easing = SeionMotion.EmphasizedDecelerate)) { rise } + fadeIn(tween(d * 4 / 10)))
            .togetherWith(fadeOut(tween(d * 3 / 10)))
    } else {
        // 350 ms emphasized accelerate, the same path reversed.
        val d = SeionMotion.COLLAPSE_MS
        fadeIn(tween(d / 2, delayMillis = d / 2))
            .togetherWith(slideOutVertically(tween(d, easing = SeionMotion.EmphasizedAccelerate)) { rise } + fadeOut(tween(d)))
    }
}

/** The browse back stack: horizontal slide + fade (~300 ms, emphasized easing), predictive back scrubs the pop. */
@Composable
private fun BrowseHost(vm: SeionViewModel, enabled: Boolean) {
    val reduce = LocalReduceMotion.current
    val density = LocalDensity.current.density
    val top = vm.stack.last()
    val nav = remember { SeekableTransitionState(top) }
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(top) {
        if (nav.currentState != top || nav.targetState != top) {
            if (reduce) nav.snapTo(top) else nav.animateTo(top)
        }
    }
    // Free saved state of popped entries.
    val liveIds = vm.stack.map { it.id }.toSet()
    val known = remember { mutableSetOf<Long>() }
    LaunchedEffect(liveIds) {
        (known - liveIds).forEach(holder::removeState)
        known.clear()
        known.addAll(liveIds)
    }
    // Predictive back (Android 14+ sends progress): the screen scales toward 0.9 while the shared-axis pop is
    // scrubbed; release commits, cancel springs back. On 12/13 the handler fires once on release.
    var backProgress by remember { mutableFloatStateOf(0f) }
    var backFrom by remember { mutableStateOf<Long?>(null) }
    PredictiveBackHandler(enabled = enabled && vm.stack.size > 1) { progress ->
        val previous = vm.stack[vm.stack.size - 2]
        val current = vm.stack.last()
        backFrom = current.id
        try {
            progress.collect { e ->
                backProgress = e.progress
                nav.seekTo(e.progress, targetState = previous)
            }
            vm.pop()
        } catch (e: CancellationException) {
            scope.launch { nav.animateTo(current) }
            throw e
        } finally {
            backProgress = 0f
            backFrom = null
        }
    }
    val transition = rememberTransition(nav, label = "browse")
    transition.AnimatedContent(
        transitionSpec = {
            if (reduce) return@AnimatedContent fadeIn(snap()) togetherWith fadeOut(snap())
            // Shared axis X: 30 dp slide; the outgoing screen is gone by 105 ms, the incoming fades in from 105 ms.
            val forward = targetState.depth >= initialState.depth
            val d = SeionMotion.NAV_MS
            val out = SeionMotion.NAV_FADE_OUT_MS
            val shift = (SeionMotion.NAV_SLIDE_DP * density).toInt()
            val dir = if (forward) 1 else -1
            (
                slideInHorizontally(tween(d, easing = SeionMotion.EmphasizedDecelerate)) { dir * shift } +
                    fadeIn(tween(d - out, delayMillis = out))
                )
                .togetherWith(
                    slideOutHorizontally(tween(d, easing = SeionMotion.EmphasizedAccelerate)) { -dir * shift } + fadeOut(tween(out))
                )
        },
        contentKey = { it.id },
        modifier = Modifier.fillMaxSize()
    ) { entry ->
        holder.SaveableStateProvider(entry.id) {
            val scaled = entry.id == backFrom
            // Each entry's list keeps its scroll position in the view model (saved state and disk: NavStore).
            val scroll = remember(entry.id) { EntryScroll(entry.id, vm) }
            CompositionLocalProvider(LocalEntryScroll provides scroll) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        if (scaled) {
                            val sc = 1f - 0.1f * backProgress
                            scaleX = sc
                            scaleY = sc
                        }
                    }
                ) { ScreenContent(entry.screen) }
            }
        }
    }
}

@Composable
private fun ScreenContent(screen: Screen) {
    when (screen) {
        Screen.Home -> LibraryHome()
        Screen.Songs -> SongsScreen()
        Screen.Albums -> AlbumsScreen()
        is Screen.Album -> AlbumScreen(screen.key)
        Screen.Artists -> ArtistsScreen()
        is Screen.Artist -> ArtistScreen(screen.name)
        Screen.Genres -> GenresScreen()
        is Screen.Genre -> GenreScreen(screen.name)
        Screen.FolderRoots -> FolderRootsScreen()
        is Screen.Folder -> FolderScreen(screen.root, screen.path)
        Screen.Recent -> RecentScreen()
        Screen.Playlists -> PlaylistsScreen()
        is Screen.Playlist -> PlaylistScreen(screen.id)
        Screen.Search -> SearchScreen()
        Screen.Queue -> QueueScreen()
        Screen.Settings -> SettingsScreen()
        Screen.MusicFolders -> MusicFoldersScreen()
        Screen.AlbumArtSettings -> AlbumArtSettingsScreen()
        is Screen.FolderPicker -> FolderPickerScreen(screen.path)
        Screen.Licenses -> LicensesScreen()
        Screen.Sound -> SoundScreen()
    }
}

/**
 * Opens now playing once per [SeionViewModel.requestExpand] (the media panel's tap), as soon as there is a track. What
 * was handled is kept in the view model, so a re-created activity (font size, language) does not replay the last tap.
 */
@Composable
internal fun ExpandOnRequest(vm: SeionViewModel, hasCurrent: Boolean, expand: () -> Unit) {
    LaunchedEffect(vm.expandRequest.value, hasCurrent) {
        if (hasCurrent && vm.takeExpandRequest()) expand()
    }
}
