package org.seion.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.seion.AppContainer
import org.seion.R
import org.seion.art.ArtOrigin
import org.seion.art.ArtRef
import org.seion.art.CoverImport
import org.seion.art.CoverSource
import org.seion.art.OnlineKind
import org.seion.library.AlbumRow
import org.seion.library.TrackEntity
import org.seion.ui.Format
import org.seion.ui.components.LocalContainer
import org.seion.ui.components.Pill
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionType

/** What touch and hold (or a page's ⋮) is about, for Details and Change cover: one song, or one album. */
sealed interface CoverSubject {
    /** The album's tag (null for an untagged folder album) and its library key. */
    val album: String?
    val albumKey: String

    /** The ref its cover is resolved with (the details sheet's "Cover" line). */
    val artRef: ArtRef

    data class Track(val track: TrackEntity) : CoverSubject {
        override val album get() = track.album
        override val albumKey get() = track.albumKey
        override val artRef get() = track.artRef()
    }

    data class Album(val row: AlbumRow) : CoverSubject {
        override val album get() = row.album
        override val albumKey get() = row.albumKey
        override val artRef get() = row.artRef()
    }
}

/**
 * Every folder the subject's album spans (CD1/CD2 folders with one album tag and album artist are one album): a chosen
 * cover is stored for each, so every track of the album finds it ([org.seion.art.CoverOverrides.key]).
 */
suspend fun AppContainer.albumFolders(subject: CoverSubject): List<String> {
    val fromIndex = db.tracks().albumTracks(subject.albumKey, 0).first().map { it.folder }.distinct()
    return fromIndex.ifEmpty { listOfNotNull(subject.artRef.folder) }
}

/** The window's photo picker for "Change cover" (shown by [CoverPickerHost]). */
class CoverPicker {
    internal var pending: CoverSubject? = null
    internal var launch: (() -> Unit)? = null

    /** Opens the system photo picker; the picture becomes [subject]'s album cover. */
    fun pick(subject: CoverSubject) {
        pending = subject
        launch?.invoke()
    }
}

val LocalCoverPicker = staticCompositionLocalOf { CoverPicker() }

/**
 * The system photo picker (`PickVisualMedia`: no storage permission; the picker grants access to the one picture). The
 * picture is squared and stored in Seion's own storage as the album's cover ([AppContainer.setChosenCover]); the music
 * folders are never written.
 */
@Composable
fun CoverPickerHost() {
    val picker = LocalCoverPicker.current
    val c = LocalContainer.current
    val res = LocalResources.current
    val context = LocalContext.current.applicationContext
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val subject = picker.pending
            picker.pending = null
            if (uri == null || subject == null) return@rememberLauncherForActivityResult
            c.appScope.launch(Dispatchers.Main) {
                val ok =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val jpeg = CoverImport.read(context.contentResolver, uri)
                            c.setChosenCover(c.albumFolders(subject), subject.album, jpeg)
                        }.onFailure { android.util.Log.w("SeionArt", "chosen cover failed: ${it.message}") }.isSuccess
                    }
                toast(context, res.getString(if (ok) R.string.cover_changed else R.string.cover_image_failed))
            }
        }
    SideEffect {
        picker.launch = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }
}

/** "Use original cover": the chosen cover goes and the album's own art shows again. */
fun AppContainer.useOriginalCover(subject: CoverSubject, onDone: () -> Unit) {
    appScope.launch(Dispatchers.Main) {
        withContext(Dispatchers.IO) { removeChosenCover(albumFolders(subject), subject.album) }
        onDone()
    }
}

/** The window's Details sheet (shown by [DetailsHost]). */
class DetailsSheet {
    var subject by mutableStateOf<CoverSubject?>(null)
        private set

    fun open(subject: CoverSubject) {
        this.subject = subject
    }

    fun close() {
        subject = null
    }
}

val LocalDetailsSheet = staticCompositionLocalOf { DetailsSheet() }

/** Whether [subject]'s album has a chosen cover (re-read when covers change). */
@Composable
fun rememberHasChosenCover(subject: CoverSubject?): Boolean {
    val c = LocalContainer.current
    val gen by c.art.generationFlow.collectAsStateWithLifecycle()
    val has by produceState(false, subject, gen) {
        value = subject != null && withContext(Dispatchers.IO) { c.coverOverrides.has(c.albumFolders(subject), subject.album) }
    }
    return has
}

/**
 * Details of a song or an album: names, year, length, where the file is, and where its cover comes from (no
 * codec/bitrate/sample-rate text; no source is named, only its kind). Change cover and, for a chosen one,
 * Use original cover at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailsHost() {
    val sheet = LocalDetailsSheet.current
    val subject = sheet.subject ?: return
    val c = LocalContainer.current
    val covers = LocalCoverPicker.current
    val res = LocalResources.current
    val context = LocalContext.current.applicationContext
    val gen by c.art.generationFlow.collectAsStateWithLifecycle()
    // A cover that arrives online while the sheet is open updates its line.
    val hits by produceState(0, subject) { c.art.onlineHits.collect { value++ } }
    val source by produceState<CoverSource?>(null, subject, gen, hits) {
        value =
            withContext(Dispatchers.IO) { c.resolver.describe(subject.artRef) }
    }
    val albumTracks by produceState<List<TrackEntity>?>(null, subject) {
        value = (subject as? CoverSubject.Album)?.let { withContext(Dispatchers.IO) { c.db.tracks().albumTracks(it.albumKey, 0).first() } }
    }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = sheet::close,
        sheetState = state,
        containerColor = SeionColors.Panel,
        contentColor = SeionColors.Ink,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("details_sheet")
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.details),
                style = SeionType.BarTitle,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("details_title")
            )
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Column {
                    when (subject) {
                        is CoverSubject.Track -> TrackDetails(subject.track)
                        is CoverSubject.Album -> AlbumDetails(subject.row, albumTracks)
                    }
                    DetailRow(stringResource(R.string.details_cover), coverSourceText(source), "details_cover")
                }
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                Pill(stringResource(R.string.change_cover), "details_change_cover", icon = R.drawable.ic_set_image) {
                    sheet.close()
                    covers.pick(subject)
                }
                if (source?.origin == ArtOrigin.CHOSEN) {
                    Pill(stringResource(R.string.use_original_cover), "details_reset_cover", icon = R.drawable.ic_set_refresh) {
                        c.useOriginalCover(subject) { toast(context, res.getString(R.string.cover_restored)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackDetails(t: TrackEntity) {
    DetailRow(stringResource(R.string.details_title_label), t.title, "details_track_title")
    DetailRow(stringResource(R.string.details_artist), t.artist ?: stringResource(R.string.unknown_artist), "details_artist")
    DetailRow(stringResource(R.string.details_album), t.albumTitle, "details_album")
    if (t.albumArtist != null &&
        t.albumArtist != t.artist
    ) {
        DetailRow(stringResource(R.string.details_album_artist), t.albumArtist, "details_album_artist")
    }
    t.year?.let { DetailRow(stringResource(R.string.details_year), it.toString(), "details_year") }
    t.trackNumber?.let { n ->
        val text = t.discNumber?.let { d -> stringResource(R.string.details_track_on_disc, n, d) } ?: n.toString()
        DetailRow(stringResource(R.string.details_track_number), text, "details_track_number")
    }
    if (t.durationMs > 0) DetailRow(stringResource(R.string.details_length), Format.duration(t.durationMs), "details_length")
    DetailRow(stringResource(R.string.details_file), t.path, "details_file")
}

@Composable
private fun AlbumDetails(a: AlbumRow, tracks: List<TrackEntity>?) {
    DetailRow(stringResource(R.string.details_album), a.title, "details_album")
    DetailRow(stringResource(R.string.details_album_artist), a.artist ?: stringResource(R.string.unknown_artist), "details_album_artist")
    a.year?.let { DetailRow(stringResource(R.string.details_year), it.toString(), "details_year") }
    val list = tracks.orEmpty()
    val count = if (tracks == null) a.trackCount else list.size
    val length = if (tracks == null) a.durationMs else list.sumOf { it.durationMs }
    DetailRow(stringResource(R.string.details_songs), songsAndLength(count, length), "details_songs")
    val folders = list.map { it.folder }.distinct().ifEmpty { listOf(a.folder) }
    DetailRow(stringResource(R.string.details_folder), folders.joinToString("\n"), "details_folder")
}

/** One label over its value; long values (paths) wrap. */
@Composable
private fun DetailRow(label: String, value: String, tag: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = SeionType.RowSub)
            Text(
                value,
                style = SeionType.RowTitle.copy(fontSize = 14.sp, lineHeight = 19.sp),
                modifier = Modifier.padding(top = 2.dp).testTag(tag)
            )
        }
    }
}

/** "Chosen by you", "In the audio file", "An image in the album's folder", "Online, from …", or "No cover art". */
@Composable
fun coverSourceText(source: CoverSource?): String = when (source?.origin) {
    // Still reading (a disk check, no network): nothing rather than a guess.
    null -> if (source == null) "" else stringResource(R.string.no_cover)
    ArtOrigin.CHOSEN -> stringResource(R.string.cover_chosen)
    ArtOrigin.EMBEDDED -> stringResource(R.string.lookup_file)
    ArtOrigin.FOLDER -> stringResource(R.string.lookup_folder)
    ArtOrigin.ONLINE ->
        when (source.online) {
            OnlineKind.OPEN_DATABASE -> stringResource(R.string.cover_online_database)
            OnlineKind.UNKNOWN, null -> stringResource(R.string.cover_online)
        }
}
