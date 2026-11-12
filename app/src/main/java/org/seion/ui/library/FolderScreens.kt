package org.seion.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.seion.R
import org.seion.art.ArtRef
import org.seion.library.FolderListing
import org.seion.library.TrackEntity
import org.seion.library.listFolder
import org.seion.playback.MediaItems
import org.seion.ui.Screen
import org.seion.ui.components.Breadcrumbs
import org.seion.ui.components.EmptyState
import org.seion.ui.components.ListHeader
import org.seion.ui.components.LocalContainer
import org.seion.ui.components.MenuAction
import org.seion.ui.components.Pill
import org.seion.ui.components.SectionLabel
import org.seion.ui.components.TrackRow
import org.seion.ui.components.artistSubtitle
import org.seion.ui.components.rowClick
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionType

/** The configured music folders: each opens the real file tree. */
@Composable
fun FolderRootsScreen() {
    val c = LocalContainer.current
    val ui = LocalContext.current
    val b = LocalBrowse.current
    val settings by c.settingsState.collectAsStateWithLifecycle()
    val roots = c.storageRoots.effectiveFolders(settings.musicFolders)
    BrowseScaffold(stringResource(R.string.folders)) {
        if (roots.isEmpty()) {
            EmptyState(R.drawable.ic_folder, stringResource(R.string.no_music_folders), null) {
                Pill(stringResource(R.string.music_folders), "roots_manage", onClick = { b.go(Screen.MusicFolders) })
            }
            return@BrowseScaffold
        }
        LazyColumn(Modifier.fillMaxSize(), state = rememberEntryListState(), contentPadding = bottomPadding()) {
            itemsIndexed(roots) { i, r ->
                val exists = File(r).isDirectory
                FolderRow(
                    name = c.storageRoots.labelFor(r, ui),
                    subtitle = if (exists) r else stringResource(R.string.folder_unavailable),
                    icon = if (c.storageRoots.volumes(ui).any {
                            it.removable && r.startsWith(it.dir.absolutePath)
                        }
                    ) {
                        R.drawable.ic_sd
                    } else {
                        R.drawable.ic_storage
                    },
                    tag = "root_$i",
                    enabled = exists
                ) { b.go(Screen.Folder(r, r)) }
            }
        }
    }
}

private data class FolderData(val listing: FolderListing, val counts: Map<String, Pair<Int, Int>>, val tracks: Map<String, TrackEntity>)

/** One folder of the real file tree: breadcrumbs, folders first, then audio files (name order). */
@Composable
fun FolderScreen(root: String, path: String) {
    val c = LocalContainer.current
    val ui = LocalContext.current
    val b = LocalBrowse.current
    val p = LocalPlayerState.current
    val picker = LocalPlaylistPicker.current
    val addLabel = stringResource(R.string.add_to_playlist)
    val data by produceState<FolderData?>(null, path) {
        value =
            withContext(Dispatchers.IO) {
                val listing = listFolder(File(path))
                val counts =
                    listing.folders.associate { f ->
                        val l = listFolder(f)
                        f.absolutePath to (l.folders.size to l.files.size)
                    }
                val tracks =
                    listing.files
                        .map { it.absolutePath }
                        .chunked(500)
                        .flatMap { c.db.tracks().byPaths(it) }
                        .associateBy { it.path }
                FolderData(listing, counts, tracks)
            }
    }
    // Crumbs start at the volume (its label), then one crumb per folder; the title is the folder's own name.
    val volume = c.storageRoots.volumes(ui).filter { path.startsWith(it.dir.absolutePath) }.maxByOrNull { it.dir.absolutePath.length }
    val base = volume?.dir?.absolutePath ?: root
    val rel = path.removePrefix(base).trim('/').split('/').filter { it.isNotEmpty() }
    val crumbs = listOf(volume?.label ?: c.storageRoots.labelFor(root, ui)) + rel
    val folderLabel = rel.lastOrNull() ?: crumbs.first()
    BrowseScaffold(
        folderLabel,
        extraMenu = listOf(
            MenuAction(stringResource(R.string.add_to_playlist), "menu_add_to_playlist") {
                picker.open(AddRequest(folderLabel) { c.playlists.folderSongs(File(path)) })
            }
        )
    ) {
        Breadcrumbs(crumbs) { i ->
            val target = if (i == 0) base else base.trimEnd('/') + "/" + rel.take(i).joinToString("/")
            val vm = b.vm
            if (!vm.popTo { it is Screen.Folder && it.path == target }) vm.replaceTop(Screen.Folder(root, target))
        }
        val d = data ?: return@BrowseScaffold
        val folders = d.listing.folders
        val files = d.listing.files
        if (folders.isEmpty() && files.isEmpty()) {
            EmptyState(R.drawable.ic_folder, stringResource(R.string.folder_empty), null)
            return@BrowseScaffold
        }
        val items = files.map { f -> d.tracks[f.absolutePath]?.let(MediaItems::fromTrack) ?: MediaItems.fromFile(f) }
        LazyColumn(Modifier.fillMaxSize(), state = rememberEntryListState(), contentPadding = bottomPadding()) {
            if (folders.isNotEmpty()) item(key = "fh") { SectionLabel(stringResource(R.string.folders)) }
            itemsIndexed(folders, key = { _, f -> "d" + f.absolutePath }) { i, f ->
                val (nf, na) = d.counts[f.absolutePath] ?: (0 to 0)
                FolderRow(
                    name = f.name,
                    subtitle =
                    listOfNotNull(
                        if (nf > 0) pluralStringResource(R.plurals.n_folders, nf, nf) else null,
                        if (na > 0 || nf == 0) pluralStringResource(R.plurals.n_files, na, na) else null
                    ).joinToString(" · "),
                    icon = R.drawable.ic_folder,
                    tag = "folder_$i",
                    modifier = Modifier.animateItem(),
                    onLongClick = { picker.open(AddRequest(f.name) { c.playlists.folderSongs(f) }) },
                    onLongClickLabel = addLabel
                ) { b.go(Screen.Folder(root, f.absolutePath)) }
            }
            if (files.isNotEmpty()) {
                item(key = "files") {
                    Column {
                        SectionLabel(stringResource(R.string.files))
                        ListHeader(pluralStringResource(R.plurals.n_files, files.size, files.size)) {
                            Pill(stringResource(R.string.shuffle), "folder_shuffle", icon = R.drawable.ic_shuffle) {
                                b.play(items, 0, true)
                            }
                        }
                    }
                }
            }
            itemsIndexed(files, key = { _, f -> "f" + f.absolutePath }) { i, f ->
                val t = d.tracks[f.absolutePath]
                val sub = t?.let { artistSubtitle(it.artist, it.album) }
                TrackRow(
                    title = f.name,
                    subtitle = sub?.text,
                    subtitleArtists = sub,
                    durationMs = t?.durationMs ?: 0,
                    tag = "file_$i",
                    artRef = ArtRef(f.absolutePath, path, t?.albumArtist ?: t?.artist, t?.album),
                    isCurrent = p.current?.mediaId == f.absolutePath,
                    isPlaying = p.isPlaying,
                    modifier = Modifier.animateItem(),
                    onLongClick = { picker.open(AddRequest(t?.title ?: f.name) { listOf(c.playlists.fileSong(f.absolutePath)) }) },
                    onLongClickLabel = addLabel
                ) { b.play(items, i, false) }
            }
        }
    }
}

@Composable
fun FolderRow(
    name: String,
    subtitle: String?,
    icon: Int,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .rowClick(onClick, onLongClick, onLongClickLabel, enabled)
            .padding(horizontal = 16.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(icon), null, tint = if (enabled) SeionColors.Secondary else SeionColors.Faint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                name,
                style = SeionType.RowTitle,
                color = if (enabled) SeionColors.Ink else SeionColors.Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth() // the whole column: right-to-left names get cut too early otherwise
            )
            if (subtitle != null) {
                Text(subtitle, style = SeionType.RowSub, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            }
        }
        if (enabled) Icon(painterResource(R.drawable.ic_chevron_right), null, tint = SeionColors.Faint, modifier = Modifier.size(20.dp))
    }
}
