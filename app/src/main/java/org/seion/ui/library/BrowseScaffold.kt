package org.seion.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import org.seion.R
import org.seion.library.TrackEntity
import org.seion.playback.MediaItems
import org.seion.playback.PlayerUiState
import org.seion.ui.MenuItem
import org.seion.ui.Screen
import org.seion.ui.SeionViewModel
import org.seion.ui.browseMenu
import org.seion.ui.components.AppBar
import org.seion.ui.components.IconBtn
import org.seion.ui.components.LocalContainer
import org.seion.ui.components.MenuAction
import org.seion.ui.components.OverflowMenu

/** Navigation + playback entry points for browse screens. */
class BrowseActions(
    val vm: SeionViewModel,
    val play: (List<androidx.media3.common.MediaItem>, Int, Boolean) -> Unit,
    val rescan: () -> Unit
) {
    fun go(screen: Screen) = vm.push(screen)

    fun back() = vm.pop()

    fun playTracks(tracks: List<TrackEntity>, start: Int, shuffle: Boolean = false) =
        play(tracks.map(MediaItems::fromTrack), start, shuffle)
}

val LocalBrowse = compositionLocalOf<BrowseActions> { error("no browse actions") }

/** Space below browse content: the mini-player (when something is queued) plus the navigation bar. */
val LocalBottomInset = compositionLocalOf { 0.dp }

val LocalPlayerState = compositionLocalOf { PlayerUiState() }

@Composable
fun bottomPadding(extra: Dp = 8.dp): PaddingValues {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return PaddingValues(bottom = maxOf(LocalBottomInset.current, nav) + extra)
}

@Composable
fun minDurationMs(): Long {
    val s by LocalContainer.current.settingsState.collectAsStateWithLifecycle()
    return s.minDurationMs
}

@Composable
fun <T> rememberFlowState(key: Any?, initial: T, flow: () -> Flow<T>): State<T> =
    remember(key) { flow() }.collectAsStateWithLifecycle(initial)

/** App bar with the standard actions: search, play queue, and ⋮ (Settings, Sound, Rescan library). */
@Composable
fun BrowseScaffold(
    title: String,
    showBack: Boolean = true,
    showSearch: Boolean = true,
    /** The screen's own ⋮ entries (Add to playlist, playlist actions), above Settings, Sound and Rescan. */
    extraMenu: List<MenuAction> = emptyList(),
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val b = LocalBrowse.current
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        val back: () -> Unit = onBack ?: { b.back() }
        AppBar(title, if (showBack) back else null) {
            if (showSearch) {
                IconBtn(org.seion.R.drawable.ic_search, stringResource(R.string.search), "bar_search") {
                    b.go(Screen.Search)
                }
            }
            IconBtn(R.drawable.ic_queue, stringResource(R.string.play_queue), "bar_queue") { b.go(Screen.Queue) }
            // An entry that would open the screen you are on is hidden there (browseMenu).
            OverflowMenu(
                extraMenu + browseMenu(b.vm.top.screen).map { item ->
                    when (item) {
                        MenuItem.SETTINGS -> MenuAction(stringResource(R.string.settings), "menu_settings") { b.go(Screen.Settings) }
                        MenuItem.SOUND -> MenuAction(stringResource(R.string.sound), "menu_sound") { b.go(Screen.Sound) }
                        MenuItem.RESCAN -> MenuAction(stringResource(R.string.rescan_library), "menu_rescan") { b.rescan() }
                        MenuItem.QUEUE -> MenuAction(stringResource(R.string.play_queue), "menu_queue") { b.go(Screen.Queue) }
                        MenuItem.ADD_TO_PLAYLIST, MenuItem.SLEEP_TIMER -> error("not in the browse menu")
                    }
                }
            )
        }
        content()
    }
}
