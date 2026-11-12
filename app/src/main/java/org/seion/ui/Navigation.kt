package org.seion.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.seion.playback.PlayerUiState

/** Browse destinations. Folder levels are separate entries, so drilling down/up animates like any other push. */
@Serializable
sealed interface Screen {
    @Serializable
    data object Home : Screen

    @Serializable
    data object Songs : Screen

    @Serializable
    data object Albums : Screen

    @Serializable
    data class Album(val key: String) : Screen

    @Serializable
    data object Artists : Screen

    @Serializable
    data class Artist(val name: String?) : Screen

    @Serializable
    data object Genres : Screen

    @Serializable
    data class Genre(val name: String?) : Screen

    @Serializable
    data object FolderRoots : Screen

    @Serializable
    data class Folder(val root: String, val path: String) : Screen

    @Serializable
    data object Recent : Screen

    /** The user's playlists. */
    @Serializable
    data object Playlists : Screen

    /** One playlist, by its id in `playlists.db`. */
    @Serializable
    data class Playlist(val id: Long) : Screen

    @Serializable
    data object Search : Screen

    @Serializable
    data object Queue : Screen

    @Serializable
    data object Settings : Screen

    @Serializable
    data object MusicFolders : Screen

    @Serializable
    data class FolderPicker(val path: String?) : Screen

    @Serializable
    data object Licenses : Screen

    /** CrystalX+, Seion RenovaPure and gapless playback, on a screen of their own. */
    @Serializable
    data object Sound : Screen

    /** Settings › Album art: the lookup order, folder images, online lookups, the cover cache. */
    @Serializable
    data object AlbumArtSettings : Screen
}

/** Screens that belong to the library (what the library button returns to). */
val Screen.isLibrary: Boolean get() = when (this) {
    Screen.Queue, Screen.Settings, Screen.MusicFolders, is Screen.FolderPicker, Screen.Licenses, Screen.Sound,
    Screen.AlbumArtSettings -> false
    else -> true
}

/** Settings and its sub-screens (the browse ⋮ menu hides its "Settings" entry there). */
val Screen.isSettings: Boolean get() = this == Screen.Settings ||
    this == Screen.MusicFolders ||
    this is Screen.FolderPicker ||
    this == Screen.Licenses ||
    this == Screen.AlbumArtSettings

/** The entries of the ⋮ menus. */
enum class MenuItem { ADD_TO_PLAYLIST, QUEUE, SLEEP_TIMER, SETTINGS, SOUND, RESCAN }

/**
 * The browse ⋮ menu on [screen]: "Sound" sits right after "Settings"; an entry that would open the screen you are on
 * (Settings and its sub-screens, or Sound) is hidden there.
 */
fun browseMenu(screen: Screen): List<MenuItem> = listOfNotNull(
    if (screen.isSettings) null else MenuItem.SETTINGS,
    if (screen == Screen.Sound) null else MenuItem.SOUND,
    MenuItem.RESCAN
)

/**
 * Now playing's ⋮ menu (it covers the browse stack, so nothing is hidden); "Add to playlist" first;
 * the sleep timer after the queue.
 */
val NOW_PLAYING_MENU = listOf(MenuItem.ADD_TO_PLAYLIST, MenuItem.QUEUE, MenuItem.SLEEP_TIMER, MenuItem.SETTINGS, MenuItem.SOUND)

@Serializable
data class NavEntry(val id: Long, val screen: Screen, val depth: Int)

/** Where a list was scrolled to: the first visible row and how far it is scrolled off the top (px). */
@Serializable
data class ScrollPos(val index: Int, val offset: Int) {
    val atTop: Boolean get() = index == 0 && offset == 0
}

/**
 * What survives the activity being recreated or the process being killed in the background: the browse stack (every
 * library screen and folder level), whether now playing was open and each stacked list's scroll position (by
 * [NavEntry.id]). Kept in the [SavedStateHandle] as JSON; [NavStore] keeps the same value on disk for a real close.
 */
@Serializable
data class SavedNav(val stack: List<NavEntry>, val nextId: Long, val nowPlaying: Boolean, val scroll: Map<Long, ScrollPos> = emptyMap()) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(nav: SavedNav): String = json.encodeToString(serializer(), nav)

        /** Null for a missing or unreadable value (e.g. saved by an older build): start from the library home. */
        fun decode(raw: String?): SavedNav? = raw?.let {
            runCatching { json.decodeFromString(serializer(), it) }.getOrNull()?.takeIf { nav -> nav.stack.isNotEmpty() }
        }
    }
}

/**
 * The browse back stack (now playing is an overlay above it, not an entry). Every change is written to [handle], so
 * leaving Seion and coming back after the system destroyed the activity or the process lands on the same screen.
 *
 * With a [store] (the app's), every change is also written to disk (debounced, off the main thread), and a cold start
 * whose [handle] is empty (Seion was swiped away, the device restarted, an update) restores from it: [ready] stays
 * false until that read and its checks against the library ([env], [NavRestore]) are done. The handle wins whenever it
 * has a value (the process was only killed in the background).
 */
class SeionViewModel(
    private val handle: SavedStateHandle = SavedStateHandle(),
    private val store: NavStore? = null,
    private val env: NavRestoreEnv? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val restored = SavedNav.decode(handle[KEY_NAV])
    private var nextId = restored?.nextId ?: 1L
    val stack = mutableStateListOf<NavEntry>().apply { addAll(restored?.stack ?: listOf(NavEntry(0, Screen.Home, 0))) }

    /** The last known scroll position of each stacked list, by [NavEntry.id] (the lists read it back when recreated). */
    private val scroll = HashMap<Long, ScrollPos>(restored?.scroll.orEmpty())

    /** False while a cold start is still reading and checking the saved screens (the UI shows the bare page). */
    var ready by mutableStateOf(restored != null || store == null || env == null)
        private set

    /** Bumped on every saved change: a late check of a restored entry never undoes what the user did since. */
    private var navVersion = 0

    /** Now playing is open (the overlay's target); the UI writes it on every expand and collapse. */
    var nowPlaying: Boolean = restored?.nowPlaying ?: false
        set(open) {
            if (field == open) return
            field = open
            save()
        }

    init {
        if (!ready) viewModelScope.launch { restoreFromDisk() }
    }

    private suspend fun restoreFromDisk() {
        val s = store ?: return
        val e = env ?: return
        val result =
            runCatching {
                withTimeoutOrNull(RESTORE_TIMEOUT_MS) {
                    withContext(io) { s.load()?.let { NavRestore.restore(it, e) } }
                }
            }.onFailure { Log.w(TAG, "restoring the saved screens failed", it) }.getOrNull()
        if (result != null) {
            apply(result.nav)
            Log.i(TAG, "restored ${result.nav.stack.size} screens (now playing ${result.nav.nowPlaying})")
        }
        ready = true
        // The index is the last scan's snapshot: the scan that runs at start may still remove a restored album, artist
        // or genre (its files deleted while Seion was closed). Every such screen is checked again once that scan is
        // done, like the ones kept unchecked while the index was empty, so a gone page falls back to its parent
        // instead of staying empty.
        val recheck = result?.let { it.provisional + NavRestore.indexEntries(it.nav) }.orEmpty()
        if (recheck.isNotEmpty()) checkLater(recheck, e)
    }

    private fun apply(nav: SavedNav) {
        stack.clear()
        stack.addAll(nav.stack)
        nextId = nav.nextId
        scroll.clear()
        scroll.putAll(nav.scroll)
        nowPlaying = nav.nowPlaying
        navVersion = 0
    }

    /**
     * Album, artist and genre screens restored at a cold start are checked again once the scan has finished (those
     * restored while the index was empty, being rebuilt, were not checked at all); an entry that is gone trims the stack
     * there, unless the user has moved on since.
     */
    private fun checkLater(ids: Set<Long>, e: NavRestoreEnv) {
        val version = navVersion
        viewModelScope.launch {
            withContext(io) { e.awaitScanDone() }
            if (navVersion != version) return@launch
            val nav = snapshot()
            val trimmed = runCatching { withContext(io) { NavRestore.recheck(nav, ids, e) } }.getOrNull() ?: return@launch
            if (navVersion != version) return@launch
            if (trimmed.stack != stack.toList()) {
                Log.i(TAG, "a restored screen is gone after the scan: ${stack.size} -> ${trimmed.stack.size} screens")
                stack.clear()
                stack.addAll(trimmed.stack)
                save()
            }
        }
    }

    private fun snapshot() = SavedNav(stack.toList(), nextId, nowPlaying, scroll.filterKeys { id -> stack.any { it.id == id } })

    private fun save() {
        navVersion++
        scroll.keys.retainAll(stack.map { it.id }.toSet())
        writeHandle()
        store?.save(snapshot())
    }

    private fun writeHandle() {
        handle[KEY_NAV] = SavedNav.encode(snapshot())
    }

    /** The scroll position [id]'s list last had (null: never scrolled, or not on the stack). */
    fun scrollOf(id: Long): ScrollPos? = scroll[id]

    /** A list moved: kept for its entry (in the saved state and on disk, debounced; the stack is untouched). */
    fun setScroll(id: Long, pos: ScrollPos) {
        if (scroll[id] == pos || stack.none { it.id == id }) return
        scroll[id] = pos
        if (store == null) return
        scrollJob?.cancel()
        scrollJob =
            viewModelScope.launch {
                delay(SCROLL_SAVE_MS)
                writeHandle()
                store.save(snapshot())
            }
    }

    private var scrollJob: Job? = null

    /** Seion leaves the screen: write the pending state now (a swipe from Recents may follow right away). */
    fun flush() {
        if (!ready) return
        scrollJob?.cancel()
        writeHandle()
        store?.let {
            it.save(snapshot())
            it.flush()
        }
    }

    /** Requests from outside the composition (the media panel's tap) to expand now playing. */
    val expandRequest = mutableStateOf(0)

    val top: NavEntry get() = stack.last()

    fun push(screen: Screen) {
        if (top.screen == screen) return
        stack.add(NavEntry(nextId++, screen, stack.size))
        save()
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        save()
        return true
    }

    /** Pops back to the deepest entry matching [predicate]; false if none is on the stack. */
    fun popTo(predicate: (Screen) -> Boolean): Boolean {
        val i = stack.indexOfLast { predicate(it.screen) }
        if (i < 0) return false
        while (stack.lastIndex > i) stack.removeAt(stack.lastIndex)
        save()
        return true
    }

    fun replaceTop(screen: Screen) {
        val depth = top.depth
        stack[stack.lastIndex] = NavEntry(nextId++, screen, depth)
        save()
    }

    fun home() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        save()
    }

    /** The library button: pop to the deepest library entry (Home is always one). */
    fun showLibrary() {
        popTo { it.isLibrary }
    }

    /** The last [expandRequest] now playing has answered; lives here so a re-created composition doesn't replay it. */
    private var handledExpand = 0

    fun requestExpand() {
        expandRequest.value++
    }

    /** True once per [requestExpand]: the caller opens now playing. */
    fun takeExpandRequest(): Boolean {
        if (expandRequest.value == handledExpand) return false
        handledExpand = expandRequest.value
        return true
    }

    private companion object {
        const val KEY_NAV = "seion.nav"
        const val TAG = "SeionNav"

        /** A cold start never waits longer than this for the saved screens (then it starts at the library home). */
        const val RESTORE_TIMEOUT_MS = 2_000L

        /** A scroll position is saved once the list has rested this long (a stack change saves at once). */
        const val SCROLL_SAVE_MS = 400L
    }
}

/** When now playing closes by itself. */
object NowPlayingRule {
    /**
     * Now playing collapses only when the queue is really gone: connected, the service has answered whether it restored
     * the saved queue ([PlayerUiState.settled]), and there is no current item. Coming back to Seion after the paused
     * service was released, the new session is empty for a moment until the queue is restored; that moment is not the
     * end of the queue and must not close now playing.
     */
    fun queueGone(state: PlayerUiState): Boolean = state.connected && state.settled && state.current == null

    /**
     * [PlayerUiState.settled] for one connection: the restore check answered with the service's item count, and the
     * controller already shows those items (the answer can arrive before the timeline update does).
     */
    fun settled(restoreAnswered: Boolean, serviceItems: Int, controllerItems: Int): Boolean =
        restoreAnswered && (serviceItems == 0 || controllerItems > 0)
}
