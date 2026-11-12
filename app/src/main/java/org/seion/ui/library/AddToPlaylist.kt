package org.seion.ui.library

import android.content.Context
import android.content.res.Resources
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.seion.R
import org.seion.art.ArtRef
import org.seion.library.TrackEntity
import org.seion.playlists.AddResult
import org.seion.playlists.NewEntry
import org.seion.playlists.PlaylistNames
import org.seion.playlists.PlaylistRules
import org.seion.playlists.PlaylistSummary
import org.seion.playlists.toNewEntry
import org.seion.ui.Format
import org.seion.ui.components.Cover
import org.seion.ui.components.LocalContainer
import org.seion.ui.components.NoCoverColors
import org.seion.ui.components.Pill
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionType

/**
 * One "Add to playlist": [label] says what is added (a song's title, "Album · 8 songs"); [songs] load when the picker
 * opens. A [subject] (one song, one album) adds Details and Change cover to the sheet.
 */
class AddRequest(val label: String, val subject: CoverSubject? = null, val songs: suspend () -> List<NewEntry>)

/** The one Add to playlist picker of the window (shown by [AddToPlaylistHost], over Now playing too). */
class PlaylistPicker {
    var request by mutableStateOf<AddRequest?>(null)
        private set

    fun open(request: AddRequest) {
        this.request = request
    }

    fun close() {
        request = null
    }

    /** Songs already loaded (a page's tracks); touch and hold on one song also offers its Details and Change cover. */
    fun openTracks(label: String, tracks: List<TrackEntity>, single: Boolean = false) {
        val songs = tracks.map { it.toNewEntry() }
        open(AddRequest(label, subject = if (single) tracks.singleOrNull()?.let(CoverSubject::Track) else null) { songs })
    }
}

val LocalPlaylistPicker = staticCompositionLocalOf { PlaylistPicker() }

/** "N songs · M min" (the queue's and the lists' header form). */
@Composable
fun songsAndLength(count: Int, durationMs: Long): String {
    val (h, m) = Format.totalMinutes(durationMs)
    return pluralStringResource(R.plurals.n_songs, count, count) + " · " +
        (if (h > 0) stringResource(R.string.hours_minutes, h, m) else stringResource(R.string.minutes, m))
}

/**
 * A playlist's art: the 2×2 mosaic of its first four different albums, one cover when it has fewer albums, or the
 * playlist glyph on the no-cover ink when it is empty (PlaylistRules.mosaic).
 */
@Composable
fun PlaylistArt(refs: List<ArtRef>, size: Dp, modifier: Modifier = Modifier, corner: Dp = 3.dp) {
    // Decorative: TalkBack reads the playlist's name and count, not four "No cover art" tiles.
    PlaylistArtImage(refs, size, modifier.clearAndSetSemantics { }, corner)
}

@Composable
private fun PlaylistArtImage(refs: List<ArtRef>, size: Dp, modifier: Modifier, corner: Dp) {
    when {
        refs.isEmpty() ->
            Box(
                // The no-cover ink with the tiles' faint white on top: it stays visible on the sheet's panel too.
                modifier.size(size).clip(RoundedCornerShape(corner)).background(NoCoverColors.Tile).background(SeionColors.Tile),
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(R.drawable.ic_playlist), null, tint = NoCoverColors.Mid, modifier = Modifier.size(size * 0.5f))
            }
        refs.size < 4 -> Cover(refs.first(), size, modifier, corner)
        else ->
            Column(modifier.size(size).clip(RoundedCornerShape(corner))) {
                for (row in 0 until 2) {
                    Row {
                        for (col in 0 until 2) Cover(refs[row * 2 + col], size / 2, corner = 0.dp)
                    }
                }
            }
    }
}

/** A playlist row's content (the Playlists screen and the picker): art, name, "N songs · M min". 56 dp. */
@Composable
fun PlaylistRowContent(p: PlaylistSummary, refs: List<ArtRef>, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlaylistArt(refs, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(p.name, style = SeionType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                songsAndLength(p.trackCount, p.durationMs),
                style = SeionType.RowSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        trailing?.invoke()
    }
}

/** The playlists in the user's chosen order (the Playlists screen and the picker agree). */
@Composable
fun sortedPlaylists(): Pair<List<PlaylistSummary>?, Map<Long, List<ArtRef>>> {
    val c = LocalContainer.current
    val settings by c.settingsState.collectAsStateWithLifecycle()
    val rows by rememberFlowState(Unit, null as List<PlaylistSummary>?) { c.playlists.summaries() }
    val covers by rememberFlowState(Unit, emptyMap()) { c.playlists.covers() }
    val sorted = remember(rows, settings.playlistSort) {
        rows?.let { PlaylistRules.sorted(it, settings.playlistSort, { p -> p.name }, { p -> p.updatedAt }, { p -> p.id }) }
    }
    return sorted to covers
}

/**
 * The Add to playlist sheet: what is added, "New playlist" first (name it, and the songs go in), then every playlist.
 * Tapping one adds the songs at its end (a song already there is skipped) and says what happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistHost() {
    val picker = LocalPlaylistPicker.current
    val req = picker.request ?: return
    val c = LocalContainer.current
    // Messages are worded with the window's resources (Seion's language), shown through the application context.
    val res = LocalResources.current
    val context = LocalContext.current.applicationContext
    val songs by produceState<List<NewEntry>?>(null, req) { value = runCatching { req.songs() }.getOrDefault(emptyList()) }
    val (rows, covers) = sortedPlaylists()
    var naming by remember(req) { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun addTo(id: Long, name: String) {
        // The app's scope: the sheet leaves the composition at once, the write and the message still happen.
        c.appScope.launch(Dispatchers.Main) {
            val list = songs ?: runCatching { req.songs() }.getOrDefault(emptyList())
            if (list.isEmpty()) return@launch
            val result = c.playlists.add(id, list)
            toast(context, addMessage(res, result, name))
        }
        picker.close()
    }

    ModalBottomSheet(
        onDismissRequest = picker::close,
        sheetState = sheet,
        containerColor = SeionColors.Panel,
        contentColor = SeionColors.Ink,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("add_sheet")
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                stringResource(R.string.add_to_playlist),
                style = SeionType.BarTitle,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("add_title")
            )
            val count = songs?.size
            Text(
                if (count == null || count == 1) req.label else req.label + " · " + pluralStringResource(R.plurals.n_songs, count, count),
                style = SeionType.RowSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp).testTag("add_label")
            )
            req.subject?.let { subject ->
                // Touch and hold also leads to the song's or album's details and its cover.
                val details = LocalDetailsSheet.current
                val covers = LocalCoverPicker.current
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    Pill(stringResource(R.string.details), "add_details", icon = R.drawable.ic_info) {
                        picker.close()
                        details.open(subject)
                    }
                    Pill(stringResource(R.string.change_cover), "add_change_cover", icon = R.drawable.ic_set_image) {
                        picker.close()
                        covers.pick(subject)
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                item(key = "new") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(enabled = !songs.isNullOrEmpty()) { naming = true }
                            .padding(horizontal = 16.dp)
                            .testTag("add_new_playlist"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(3.dp)).background(SeionColors.Tile),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(painterResource(R.drawable.ic_add), null, tint = SeionColors.Ink, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.new_playlist), style = SeionType.RowTitle, modifier = Modifier.weight(1f))
                    }
                }
                itemsIndexed(rows.orEmpty(), key = { _, p -> p.id }) { i, p ->
                    PlaylistRowContent(
                        p,
                        covers[p.id].orEmpty(),
                        Modifier.clickable(enabled = !songs.isNullOrEmpty()) { addTo(p.id, p.name) }.testTag("add_target_$i")
                    )
                }
            }
        }
    }
    if (naming) {
        val others = rows.orEmpty().map { it.name }
        PlaylistNameDialog(
            title = stringResource(R.string.new_playlist),
            confirmLabel = stringResource(R.string.create),
            initial = suggestedName(others),
            others = others,
            onConfirm = { name ->
                naming = false
                val list = songs.orEmpty()
                c.appScope.launch(Dispatchers.Main) {
                    c.playlists.create(name, emptyList()).let { id -> toast(context, addMessage(res, c.playlists.add(id, list), name)) }
                }
                picker.close()
            },
            onDismiss = { naming = false }
        )
    }
}

/** "Playlist N", the first number not taken (New playlist's prefilled name). */
@Composable
fun suggestedName(others: List<String>): String {
    val res = LocalResources.current
    return PlaylistNames.suggest(others) { n -> res.getString(R.string.playlist_default_name, n) }
}

/** "Added to “Mix”", "Added 8 songs to “Mix”", "Already in “Mix”", "Added 5 songs to “Mix” · 3 were already there". */
fun addMessage(res: Resources, r: AddResult, name: String): String = when {
    r.added == 0 -> res.getString(R.string.already_in, name)
    r.added == 1 && r.skipped == 0 -> res.getString(R.string.added_to, name)
    else -> {
        val main = res.getQuantityString(R.plurals.added_songs_to, r.added, r.added, name)
        if (r.skipped > 0) main + " · " + res.getQuantityString(R.plurals.n_already_there, r.skipped, r.skipped) else main
    }
}

fun toast(context: Context, text: String) {
    android.util.Log.i("SeionUi", "message: $text")
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
