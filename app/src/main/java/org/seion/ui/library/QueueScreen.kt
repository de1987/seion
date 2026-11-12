package org.seion.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.seion.R
import org.seion.art.ArtUris
import org.seion.playback.QueueEntry
import org.seion.playback.QueueLogic
import org.seion.playlists.toNewEntry
import org.seion.ui.Format
import org.seion.ui.components.Chip
import org.seion.ui.components.EmptyState
import org.seion.ui.components.ListHeader
import org.seion.ui.components.LocalContainer
import org.seion.ui.components.MenuAction
import org.seion.ui.components.SectionLabel
import org.seion.ui.components.TrackRow
import org.seion.ui.components.artistSubtitle

/**
 * Rows already played in this pass (above NOW PLAYING): dimmed to 60 % so their
 * subtitle keeps about 3:1 against the page.
 */
internal const val PLAYED_ALPHA = 0.6f

/**
 * The play queue in play order (the shuffle order while shuffled): the rows already played, NOW PLAYING and UP
 * NEXT; a tap jumps, touch and hold adds to a playlist. "Edit order" (the chip, or ⋮) switches to the playlist's edit
 * mode ([EditOrderList]): drag handles, ×, Undo, TalkBack's Move up / Move down. It opens at the current track, or at
 * the row it was on before a real close.
 */
@Composable
fun QueueScreen() {
    val c = LocalContainer.current
    val p = LocalPlayerState.current
    val res = LocalResources.current
    val picker = LocalPlaylistPicker.current
    val saved = rememberEntrySavedScroll()
    val listState = rememberEntryListState()
    var editing by rememberSaveable { mutableStateOf(false) }
    val undo = rememberUndoState()
    val queueLabel = stringResource(R.string.queue_label)
    val sections = QueueLogic.sections(p.queue.size, p.orderPosition)
    LaunchedEffect(p.queue.isEmpty()) { if (p.queue.isEmpty()) editing = false }
    BackHandler(enabled = editing) { editing = false }
    // Without a saved position (a real close restores it) the queue opens at NOW PLAYING once the current track is
    // known, unless it is one of the first two rows. Once per visit of the screen.
    var scrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sections.current != null) {
        val current = sections.current ?: return@LaunchedEffect
        if (scrolled) return@LaunchedEffect
        scrolled = true
        if ((saved == null || saved.atTop) && current > 1) listState.scrollToItem(queueItemOfNowPlaying(sections))
    }
    // Edit order starts at the row the list showed.
    val editState = remember(editing) { LazyListState(if (editing) editItemFor(listState.firstVisibleItemIndex, sections) else 0) }

    val queueMenu =
        if (p.queue.isEmpty()) {
            emptyList()
        } else {
            listOfNotNull(
                if (!editing &&
                    p.queue.size > 1
                ) {
                    MenuAction(stringResource(R.string.edit_order), "menu_edit_order") { editing = true }
                } else {
                    null
                },
                // "Add queue to playlist": its picker offers New playlist
                // too, so this is also how the queue is saved as a playlist.
                MenuAction(stringResource(R.string.add_queue_to_playlist), "menu_queue_to_playlist") {
                    val songs = p.queue.map { it.item.toNewEntry() }
                    picker.open(AddRequest(queueLabel) { songs })
                }
            )
        }
    BrowseScaffold(stringResource(R.string.play_queue), extraMenu = queueMenu, onBack = if (editing) ({ editing = false }) else null) {
        if (p.queue.isEmpty()) {
            EmptyState(org.seion.R.drawable.ic_queue, stringResource(R.string.queue_empty), null)
            return@BrowseScaffold
        }
        Box(Modifier.fillMaxSize()) {
            if (editing) {
                EditOrderList(
                    p.queue,
                    state = editState,
                    key = { it.item.mediaId + "#" + it.index },
                    tag = "queue",
                    removeDescription = stringResource(R.string.remove_from_queue),
                    onMove = { from, to, _ -> c.player.move(from, to) },
                    onRemove = { pos, e ->
                        val title = e.item.mediaMetadata.title?.toString().orEmpty()
                        c.player.removeAt(pos)?.let { r ->
                            undo.show(res.getString(R.string.removed_from_queue, title)) { c.player.undoRemove(r) }
                        }
                    },
                    onDone = { editing = false }
                ) { pos, e, leading, trailing ->
                    QueueRow(e, pos, p.currentIndex, p.isPlaying, editing = true, leading = leading, trailing = trailing, onClick = null)
                }
            } else {
                QueueList(
                    p.queue,
                    sections,
                    p.currentIndex,
                    p.isPlaying,
                    listState,
                    onEdit = { editing = true },
                    onLongClick = { e ->
                        val song = e.item.toNewEntry()
                        picker.open(AddRequest(song.title) { listOf(song) })
                    }
                ) { e -> c.player.playAt(e.index) }
            }
            UndoBar(undo, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** The list item of the NOW PLAYING header in [QueueList]: after the played rows. */
internal fun queueItemOfNowPlaying(s: QueueLogic.Sections): Int = s.played.count()

/**
 * The item of [EditOrderList] (one per row) that shows the same row as item [first] of [QueueList] (the played rows,
 * the NOW PLAYING header and row, the UP NEXT header, the rows after it).
 */
internal fun editItemFor(first: Int, s: QueueLogic.Sections): Int {
    if (first <= 0) return 0
    val current = s.current ?: return first
    val played = s.played.count()
    return when {
        first < played -> first
        first <= played + 1 -> current
        else -> first - 2
    }
}

/**
 * The queue outside Edit order: "N songs · M min" with the Edit order chip, the played rows (dimmed), NOW PLAYING and
 * UP NEXT ([QueueLogic.sections]).
 */
@Composable
internal fun QueueList(
    queue: List<QueueEntry>,
    sections: QueueLogic.Sections,
    currentIndex: Int,
    isPlaying: Boolean,
    state: LazyListState,
    onEdit: () -> Unit,
    onLongClick: (QueueEntry) -> Unit,
    onPlay: (QueueEntry) -> Unit
) {
    val total = queue.sumOf { it.item.mediaMetadata.durationMs ?: 0L }
    val (h, m) = Format.totalMinutes(total)
    val headerText =
        pluralStringResource(R.plurals.n_songs, queue.size, queue.size) + " · " +
            (if (h > 0) stringResource(R.string.hours_minutes, h, m) else stringResource(R.string.minutes, m))
    val addLabel = stringResource(R.string.add_to_playlist)
    // The header stays above the list, so Edit order is at hand wherever the list is scrolled.
    Column(Modifier.fillMaxSize()) {
        ListHeader(headerText) { if (queue.size > 1) Chip(stringResource(R.string.edit_order), "queue_edit", onClick = onEdit) }
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = bottomPadding()) {
            val played = sections.played.map { it to queue[it] }
            items(played, key = { (_, e) -> e.item.mediaId + "#" + e.index }) { (pos, e) ->
                QueueRow(
                    e,
                    pos,
                    currentIndex,
                    isPlaying,
                    modifier = Modifier.animateItem().alpha(PLAYED_ALPHA),
                    onLongClick = onLongClick,
                    addLabel = addLabel
                ) {
                    onPlay(e)
                }
            }
            sections.current?.let { pos ->
                item(key = "label_now") { SectionLabel(stringResource(R.string.queue_now_playing), Modifier.animateItem()) }
                val e = queue[pos]
                item(key = e.item.mediaId + "#" + e.index) {
                    QueueRow(
                        e,
                        pos,
                        currentIndex,
                        isPlaying,
                        modifier = Modifier.animateItem(),
                        onLongClick = onLongClick,
                        addLabel = addLabel
                    ) {
                        onPlay(e)
                    }
                }
            }
            if (!sections.upNext.isEmpty() && sections.current != null) {
                item(key = "label_next") { SectionLabel(stringResource(R.string.queue_up_next), Modifier.animateItem()) }
            }
            val next = sections.upNext.map { it to queue[it] }
            items(next, key = { (_, e) -> e.item.mediaId + "#" + e.index }) { (pos, e) ->
                QueueRow(
                    e,
                    pos,
                    currentIndex,
                    isPlaying,
                    modifier = Modifier.animateItem(),
                    onLongClick = onLongClick,
                    addLabel = addLabel
                ) {
                    onPlay(e)
                }
            }
        }
    }
}

/** One queue row: the library's [TrackRow] with its cover; in Edit order with the handle and × slots and no length. */
@Composable
private fun QueueRow(
    e: QueueEntry,
    pos: Int,
    currentIndex: Int,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: ((QueueEntry) -> Unit)? = null,
    addLabel: String? = null,
    onClick: (() -> Unit)?
) {
    val meta = e.item.mediaMetadata
    val sub = artistSubtitle(meta.artist?.toString(), meta.albumTitle?.toString())
    TrackRow(
        title = meta.title?.toString().orEmpty(),
        subtitle = sub.text,
        subtitleArtists = sub,
        durationMs = if (editing) 0 else meta.durationMs ?: 0,
        tag = "queue_$pos",
        modifier = modifier,
        artRef = ArtUris.fromUri(meta.artworkUri),
        isCurrent = e.index == currentIndex,
        isPlaying = isPlaying,
        leading = leading,
        trailing = trailing,
        onLongClick = onLongClick?.let { { it(e) } },
        onLongClickLabel = addLabel,
        onClick = onClick
    )
}
