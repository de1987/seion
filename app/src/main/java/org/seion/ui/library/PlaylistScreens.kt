package org.seion.ui.library

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.seion.R
import org.seion.playlists.PlaylistItem
import org.seion.playlists.PlaylistRules
import org.seion.playlists.PlaylistSort
import org.seion.playlists.PlaylistSummary
import org.seion.ui.Screen
import org.seion.ui.ambient.FeatheredCover
import org.seion.ui.components.EmptyState
import org.seion.ui.components.ListHeader
import org.seion.ui.components.LocalContainer
import org.seion.ui.components.MenuAction
import org.seion.ui.components.OverflowMenu
import org.seion.ui.components.Pill
import org.seion.ui.components.TrackRow
import org.seion.ui.components.artistSubtitle
import org.seion.ui.components.rememberCover
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionType

private const val M3U_MIME = "audio/x-mpegurl"

/** What the system picker may offer for Import (Android names .m3u/.m3u8 differently across versions and providers). */
private val M3U_TYPES =
    arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/x-scpls", "text/plain")

/** Rows of a missing file: dimmed like a disabled control (M3's 38 %). */
private const val UNAVAILABLE_ALPHA = 0.38f

/** Play, Rename, Export and Delete of a playlist, shared by its row's ⋮ and its page's ⋮ (with their dialogs). */
private class PlaylistActions(val rename: (Long) -> Unit, val delete: (Long) -> Unit, val export: (PlaylistSummary) -> Unit)

/**
 * The dialogs and system pickers behind [PlaylistActions] (rename, delete, export), hosted by both playlist screens.
 * [onDeleted] runs after a delete (the playlist's page goes back).
 */
@Composable
private fun rememberPlaylistActions(playlists: List<PlaylistSummary>, onDeleted: (Long) -> Unit = {}): PlaylistActions {
    val c = LocalContainer.current
    val ui = LocalContext.current
    val res = LocalResources.current
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    var exporting by rememberSaveable { mutableStateOf<Long?>(null) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(M3U_MIME)) { uri: Uri? ->
            val id = exporting
            exporting = null
            if (uri == null || id == null) return@rememberLauncherForActivityResult
            val name = displayName(ui, uri) ?: ""
            c.appScope.launch(Dispatchers.Main) {
                val ok = runCatching { c.playlists.export(ui.contentResolver, uri, id) }.isSuccess
                toast(ui.applicationContext, if (ok) res.getString(R.string.exported, name) else res.getString(R.string.export_failed))
            }
        }
    renaming?.let { id ->
        val p = playlists.firstOrNull { it.id == id }
        if (p == null) {
            renaming = null
        } else {
            PlaylistNameDialog(
                title = stringResource(R.string.rename_playlist),
                confirmLabel = stringResource(R.string.save),
                initial = p.name,
                others = playlists.filter { it.id != id }.map { it.name },
                onConfirm = { name ->
                    renaming = null
                    c.appScope.launch { c.playlists.rename(id, name) }
                },
                onDismiss = { renaming = null }
            )
        }
    }
    deleting?.let { id ->
        val p = playlists.firstOrNull { it.id == id }
        if (p == null) {
            deleting = null
        } else {
            DeletePlaylistDialog(
                p.name,
                onConfirm = {
                    deleting = null
                    c.appScope.launch(Dispatchers.Main) {
                        c.playlists.delete(id)
                        onDeleted(id)
                    }
                },
                onDismiss = { deleting = null }
            )
        }
    }
    return remember(exportLauncher) {
        PlaylistActions(
            rename = { renaming = it },
            delete = { deleting = it },
            export = { p ->
                exporting = p.id
                // A file name the systems accept: no path separators.
                exportLauncher.launch(p.name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".m3u8")
            }
        )
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) cur.getString(0) else null
    }
}.getOrNull()

// ---------------------------------------------------------------- Playlists

/** The user's playlists: New playlist, one row per playlist (art, name, songs · length, ⋮), sorted by name or recency. */
@Composable
fun PlaylistsScreen() {
    val c = LocalContainer.current
    val b = LocalBrowse.current
    val ui = LocalContext.current
    val res = LocalResources.current
    val settings by c.settingsState.collectAsStateWithLifecycle()
    val (rows, covers) = sortedPlaylists()
    val list = rows.orEmpty()
    var naming by rememberSaveable { mutableStateOf(false) }
    val actions = rememberPlaylistActions(list)
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val fallback = res.getString(R.string.playlist)
            c.appScope.launch(Dispatchers.Main) {
                val result = runCatching { c.playlists.import(ui.contentResolver, uri, displayName(ui, uri), fallback) }.getOrNull()
                val text =
                    when {
                        result == null -> res.getString(R.string.import_failed)
                        result.id == null -> res.getString(R.string.import_nothing)
                        else -> {
                            val main = res.getQuantityString(R.plurals.imported_songs, result.added, result.added, result.name)
                            if (result.notFound > 0) {
                                main + " · " + res.getQuantityString(R.plurals.n_not_found, result.notFound, result.notFound)
                            } else {
                                main
                            }
                        }
                    }
                toast(ui.applicationContext, text)
            }
        }
    val sortAction =
        if (settings.playlistSort == PlaylistSort.NAME) {
            MenuAction(stringResource(R.string.sort_by_recent), "menu_sort_recent") {
                c.appScope.launch { c.settings.setPlaylistSort(PlaylistSort.RECENT) }
            }
        } else {
            MenuAction(stringResource(R.string.sort_by_name), "menu_sort_name") {
                c.appScope.launch { c.settings.setPlaylistSort(PlaylistSort.NAME) }
            }
        }
    BrowseScaffold(
        stringResource(R.string.playlists),
        extraMenu = listOf(
            MenuAction(stringResource(R.string.new_playlist), "menu_new_playlist") { naming = true },
            MenuAction(stringResource(R.string.import_playlist), "menu_import_playlist") { importLauncher.launch(M3U_TYPES) },
            sortAction
        )
    ) {
        LazyColumn(Modifier.fillMaxSize(), state = rememberEntryListState(), contentPadding = bottomPadding()) {
            item(key = "header") {
                ListHeader(pluralStringResource(R.plurals.n_playlists, list.size, list.size)) {
                    Pill(stringResource(R.string.new_playlist), "playlists_new", icon = R.drawable.ic_add) { naming = true }
                }
            }
            if (rows != null && list.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        R.drawable.ic_playlist,
                        stringResource(R.string.no_playlists_title),
                        stringResource(R.string.no_playlists_body)
                    )
                }
            }
            itemsIndexed(list, key = { _, p -> p.id }) { i, p ->
                PlaylistRowContent(
                    p,
                    covers[p.id].orEmpty(),
                    Modifier.animateItem().clickable { b.go(Screen.Playlist(p.id)) }.testTag("playlist_$i")
                ) {
                    OverflowMenu(
                        listOf(
                            MenuAction(stringResource(R.string.play), "playlist_play_$i") {
                                c.appScope.launch(Dispatchers.Main) {
                                    val items = c.playlists.items(p.id).first()
                                    PlaylistRules.playable(
                                        items,
                                        items.indexOfFirst {
                                            it.available
                                        },
                                        PlaylistItem::available
                                    )?.let { (l, _) ->
                                        b.play(l.map { it.mediaItem() }, 0, false)
                                    }
                                }
                            },
                            MenuAction(stringResource(R.string.rename_playlist), "playlist_rename_$i") { actions.rename(p.id) },
                            MenuAction(stringResource(R.string.export_playlist), "playlist_export_$i") { actions.export(p) },
                            MenuAction(stringResource(R.string.delete_playlist), "playlist_delete_$i") { actions.delete(p.id) }
                        ),
                        tag = "playlist_more_$i",
                        description = stringResource(R.string.more_options_for, p.name),
                        tint = SeionColors.Faint
                    )
                }
            }
        }
    }
    if (naming) {
        val others = list.map { it.name }
        PlaylistNameDialog(
            title = stringResource(R.string.new_playlist),
            confirmLabel = stringResource(R.string.create),
            initial = suggestedName(others),
            others = others,
            onConfirm = { name ->
                naming = false
                // The new, empty playlist opens: its page says how to fill it.
                c.appScope.launch(Dispatchers.Main) { b.go(Screen.Playlist(c.playlists.create(name))) }
            },
            onDismiss = { naming = false }
        )
    }
}

// ---------------------------------------------------------------- One playlist

/** [PlaylistSummary] once loaded; [summary] null = the playlist is gone. */
private data class Loaded(val summary: PlaylistSummary?)

/**
 * One playlist: the album page's header (mosaic, name, songs · length, Play / Shuffle), its songs (tap plays from there,
 * touch and hold adds to another playlist), "Edit order" (drag handles, ×, Undo) and the playlist's actions in ⋮.
 */
@Composable
fun PlaylistScreen(id: Long) {
    val c = LocalContainer.current
    val b = LocalBrowse.current
    val p = LocalPlayerState.current
    val ui = LocalContext.current
    val res = LocalResources.current
    val picker = LocalPlaylistPicker.current
    val loaded by rememberFlowState(id, null as Loaded?) { c.playlists.summary(id).map { Loaded(it) } }
    val items by rememberFlowState(id, null as List<PlaylistItem>?) { c.playlists.items(id) }
    val (all, _) = sortedPlaylists()
    val summary = loaded?.summary
    var editing by rememberSaveable { mutableStateOf(false) }
    val undo = rememberUndoState()
    val list = items.orEmpty()
    val missing = list.count { !it.available }
    // Deleted (here, or from the Playlists screen under a restored stack): this page goes back.
    LaunchedEffect(loaded) {
        if (loaded != null && loaded?.summary == null && b.vm.top.screen == Screen.Playlist(id)) b.vm.pop()
    }
    BackHandler(enabled = editing) { editing = false }
    val actions = rememberPlaylistActions(all.orEmpty(), onDeleted = { if (b.vm.top.screen == Screen.Playlist(id)) b.vm.pop() })

    fun remove(items: List<PlaylistItem>, gone: Set<Long>, text: String) {
        val previous = items.map { it.entry }
        undo.show(text) { c.appScope.launch { c.playlists.setEntries(id, previous) } }
        c.appScope.launch { c.playlists.setEntries(id, previous.filter { it.id !in gone }) }
    }

    val menu =
        if (summary == null) {
            emptyList()
        } else {
            listOfNotNull(
                if (!editing &&
                    list.size > 1
                ) {
                    MenuAction(stringResource(R.string.edit_order), "menu_edit_order") { editing = true }
                } else {
                    null
                },
                MenuAction(stringResource(R.string.rename_playlist), "menu_rename_playlist") { actions.rename(id) },
                if (missing > 0) {
                    val text = pluralStringResource(R.plurals.removed_unavailable, missing, missing)
                    MenuAction(stringResource(R.string.remove_unavailable), "menu_remove_unavailable") {
                        remove(list, list.filter { !it.available }.map { it.entry.id }.toSet(), text)
                    }
                } else {
                    null
                },
                MenuAction(stringResource(R.string.export_playlist), "menu_export_playlist") { actions.export(summary) },
                MenuAction(stringResource(R.string.delete_playlist), "menu_delete_playlist") { actions.delete(id) }
            )
        }
    BrowseScaffold(summary?.name.orEmpty(), extraMenu = menu, onBack = if (editing) ({ editing = false }) else null) {
        Box(Modifier.fillMaxSize()) {
            if (editing) {
                EditableList(list, id, onRemove = { item ->
                    remove(list, setOf(item.entry.id), res.getString(R.string.removed_from_playlist, item.title))
                }) { editing = false }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = rememberEntryListState(), contentPadding = bottomPadding()) {
                    item(key = "header") {
                        if (summary != null) {
                            PlaylistHeader(
                                summary.name,
                                list,
                                missing,
                                onPlay = { playFrom(list, list.indexOfFirst { it.available }, b) },
                                onShuffle = {
                                    val playable = list.filter { it.available }
                                    if (playable.isNotEmpty()) b.play(playable.map { it.mediaItem() }, 0, true)
                                }
                            )
                        }
                    }
                    if (items != null && list.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                R.drawable.ic_playlist,
                                stringResource(R.string.playlist_empty_title),
                                stringResource(R.string.playlist_empty_body)
                            )
                        }
                    }
                    itemsIndexed(list, key = { _, it -> it.entry.id }) { i, item ->
                        val sub = if (item.available) {
                            artistSubtitle(
                                item.artist ?: stringResource(R.string.unknown_artist),
                                item.album
                            )
                        } else {
                            null
                        }
                        val unavailableText = stringResource(R.string.not_available)
                        val addLabel = stringResource(R.string.add_to_playlist)
                        TrackRow(
                            title = item.title,
                            subtitle = sub?.text ?: unavailableText,
                            subtitleArtists = sub,
                            durationMs = if (item.available) item.durationMs else 0,
                            tag = "pl_track_$i",
                            artRef = item.artRef(),
                            isCurrent = item.available && p.current?.mediaId == item.path,
                            isPlaying = p.isPlaying,
                            modifier = Modifier.animateItem().alpha(if (item.available) 1f else UNAVAILABLE_ALPHA),
                            onLongClick = {
                                val song = item.toNewEntry()
                                picker.open(AddRequest(item.title) { listOf(song) })
                            },
                            onLongClickLabel = addLabel
                        ) {
                            if (item.available) {
                                playFrom(list, i, b)
                            } else {
                                toast(ui.applicationContext, res.getString(R.string.song_not_available, item.title))
                            }
                        }
                    }
                }
            }
            UndoBar(undo, Modifier.align(Alignment.BottomCenter))
        }
    }
}

private fun playFrom(items: List<PlaylistItem>, tapped: Int, b: BrowseActions) {
    val (playable, start) = PlaylistRules.playable(items, tapped, PlaylistItem::available) ?: return
    b.play(playable.map { it.mediaItem() }, start, false)
}

/** The album page's header for a playlist: mosaic (or the one cover, feathered), name, "Playlist", count · length, pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaylistHeader(name: String, items: List<PlaylistItem>, missing: Int, onPlay: () -> Unit, onShuffle: () -> Unit) {
    val playable = remember(items) { items.filter { it.available } }
    val refs = remember(playable) { PlaylistRules.mosaic(playable) { it.albumKey }.map { it.artRef() } }
    val bmp = rememberCover(refs.firstOrNull(), 320.dp)
    Box(Modifier.fillMaxWidth().heightIn(min = 166.dp).testTag("pl_header")) {
        if (bmp != null) HeaderGlow(bmp)
        if (bmp != null && refs.size == 1) {
            FeatheredCover(bmp, 1f, true, Modifier.offset(16.dp, 8.dp).size(128.dp))
        } else {
            PlaylistArt(refs, 128.dp, Modifier.offset(16.dp, 8.dp), corner = 4.dp)
        }
        HeaderStack(
            text = {
                Column(Modifier.padding(start = 158.dp, end = 12.dp, top = 14.dp)) {
                    Text(
                        name,
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        color = SeionColors.Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("pl_title")
                    )
                    Text(
                        stringResource(R.string.playlist),
                        style = LocalTextStyle.current.merge(TextStyle(fontSize = 13.5.sp, color = Color(0xFFC4C5CA))),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    val parts =
                        listOfNotNull(
                            songsAndLength(items.size, playable.sumOf { it.durationMs }),
                            if (missing > 0) pluralStringResource(R.plurals.n_unavailable, missing, missing) else null
                        )
                    Text(parts.joinToString(" · "), style = SeionType.RowSub, modifier = Modifier.padding(top = 8.dp).testTag("pl_count"))
                }
            },
            actions = {
                if (playable.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(start = 158.dp, end = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Pill(stringResource(R.string.play), "pl_play", solid = true, icon = R.drawable.ic_play, onClick = onPlay)
                        Pill(stringResource(R.string.shuffle), "pl_shuffle", icon = R.drawable.ic_shuffle, onClick = onShuffle)
                    }
                } else {
                    Box(Modifier.height(32.dp).padding(bottom = 12.dp))
                }
            }
        )
    }
}

/**
 * "Edit order" (rearranging the songs by hand), the component the play queue shares ([EditOrderList]): drag handles,
 * ×, Done, TalkBack's Move up / Move down. Every drop and removal is saved at once.
 */
@Composable
private fun EditableList(items: List<PlaylistItem>, id: Long, onRemove: (PlaylistItem) -> Unit, onDone: () -> Unit) {
    val c = LocalContainer.current
    EditOrderList(
        items,
        key = { it.entry.id },
        tag = "pl",
        removeDescription = stringResource(R.string.remove_from_playlist),
        onMove = { _, _, list -> c.appScope.launch { c.playlists.setEntries(id, list.map { it.entry }) } },
        onRemove = { _, item -> onRemove(item) },
        onDone = onDone
    ) { pos, item, leading, trailing ->
        val sub = if (item.available) artistSubtitle(item.artist ?: stringResource(R.string.unknown_artist), item.album) else null
        TrackRow(
            title = item.title,
            subtitle = sub?.text ?: stringResource(R.string.not_available),
            subtitleArtists = sub,
            durationMs = 0,
            tag = "pl_edit_$pos",
            artRef = item.artRef(),
            modifier = Modifier.alpha(if (item.available) 1f else UNAVAILABLE_ALPHA),
            leading = leading,
            trailing = trailing,
            onClick = null
        )
    }
}
