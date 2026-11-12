package org.seion.ui.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.seion.ui.ScrollPos
import org.seion.ui.SeionViewModel

/** One stacked screen's scroll position, kept by the view model under the entry's id (saved state and disk). */
class EntryScroll(private val id: Long, private val vm: SeionViewModel) {
    fun saved(): ScrollPos? = vm.scrollOf(id)

    fun update(pos: ScrollPos) = vm.setScroll(id, pos)
}

/** Provided per back-stack entry by the browse host; null outside it (previews, tests of a single screen). */
val LocalEntryScroll = compositionLocalOf<EntryScroll?> { null }

/** How long a restored list waits for its rows (Room, the folder listing) before it gives the saved row up. */
private const val RESTORE_WAIT_MS = 5_000L

/** The list state of the screen's main list: it comes back at the row it was on (Back, a re-creation, a cold start). */
@Composable
fun rememberEntryListState(): LazyListState {
    val memory = LocalEntryScroll.current
    val start = remember(memory) { memory?.saved() }
    val state = remember(memory) { LazyListState(start?.index ?: 0, start?.offset ?: 0) }
    KeepScroll(
        memory,
        start,
        count = { state.layoutInfo.totalItemsCount },
        pos = { ScrollPos(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) },
        scrolling = { state.isScrollInProgress },
        canScrollForward = { state.canScrollForward },
        scrollTo = { i, o -> state.scrollToItem(i, o) }
    )
    return state
}

/** The scroll position this entry's list had when the screen was composed (null: none saved, or outside the host). */
@Composable
fun rememberEntrySavedScroll(): ScrollPos? {
    val memory = LocalEntryScroll.current
    return remember(memory) { memory?.saved() }
}

/** [rememberEntryListState] for a grid. */
@Composable
fun rememberEntryGridState(): LazyGridState {
    val memory = LocalEntryScroll.current
    val start = remember(memory) { memory?.saved() }
    val state = remember(memory) { LazyGridState(start?.index ?: 0, start?.offset ?: 0) }
    KeepScroll(
        memory,
        start,
        count = { state.layoutInfo.totalItemsCount },
        pos = { ScrollPos(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) },
        scrolling = { state.isScrollInProgress },
        canScrollForward = { state.canScrollForward },
        scrollTo = { i, o -> state.scrollToItem(i, o) }
    )
    return state
}

/**
 * Puts a restored list back on its saved row, then reports every move to [memory]. The rows usually arrive after the
 * first frame, and a lazy list that first measures fewer rows than its saved index forgets that index: so this waits
 * until the saved row exists and scrolls there, unless the user scrolled first or the list is shorter now.
 */
@Composable
private fun KeepScroll(
    memory: EntryScroll?,
    start: ScrollPos?,
    count: () -> Int,
    pos: () -> ScrollPos,
    scrolling: () -> Boolean,
    canScrollForward: () -> Boolean,
    scrollTo: suspend (Int, Int) -> Unit
) {
    if (memory == null) return
    LaunchedEffect(memory) {
        if (start != null && !start.atTop) {
            val reached = withTimeoutOrNull(RESTORE_WAIT_MS) { snapshotFlow { count() > start.index || scrolling() }.first { it } }
            if (reached != null && !scrolling() && pos() != start) scrollTo(start.index, start.offset)
            // A list left at its end (Settings, Sound) is clamped short of the saved row while its bottom padding (the
            // mini-player's) isn't back yet: once it can scroll further, it goes the rest of the way, unless the user
            // scrolled first.
            if (reached != null && !scrolling() && pos() != start) {
                withTimeoutOrNull(RESTORE_WAIT_MS) { snapshotFlow { scrolling() || canScrollForward() }.first { it } }
                if (!scrolling() && canScrollForward() && pos() != start) scrollTo(start.index, start.offset)
            }
        }
        snapshotFlow(pos).collect(memory::update)
    }
}
