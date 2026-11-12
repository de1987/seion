package org.seion.ui.library

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.scale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.seion.R
import org.seion.art.ArtRef
import org.seion.library.AlbumRow
import org.seion.library.AlbumSort
import org.seion.library.NameCount
import org.seion.library.TrackEntity
import org.seion.library.likePattern
import org.seion.playlists.toNewEntry
import org.seion.ui.Format
import org.seion.ui.Screen
import org.seion.ui.WordSafe
import org.seion.ui.ambient.FeatheredCover
import org.seion.ui.ambient.ambientFilter
import org.seion.ui.components.Chip
import org.seion.ui.components.CompactArtistLine
import org.seion.ui.components.Cover
import org.seion.ui.components.EmptyState
import org.seion.ui.components.ListHeader
import org.seion.ui.components.LocalContainer
import org.seion.ui.components.MenuAction
import org.seion.ui.components.Pill
import org.seion.ui.components.SectionLabel
import org.seion.ui.components.TrackRow
import org.seion.ui.components.artistSubtitle
import org.seion.ui.components.rememberCover
import org.seion.ui.components.rowClick
import org.seion.ui.components.trackSubtitle
import org.seion.ui.theme.Roboto
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionType

fun AlbumRow.artRef() = ArtRef(samplePath, folder, artist, album)

fun TrackEntity.artRef() = ArtRef(path, folder, albumArtist ?: artist, album)

// ---------------------------------------------------------------- Library home

private data class Tile(val label: Int, @param:DrawableRes val icon: Int, val count: String, val tag: String, val screen: Screen)

@Composable
fun LibraryHome() {
    val c = LocalContainer.current
    val b = LocalBrowse.current
    val min = minDurationMs()
    val dao = c.db.tracks()
    val songs by rememberFlowState(min, 0) { dao.songCount(min) }
    val albums by rememberFlowState(min, emptyList()) { dao.albums(min) }
    val artists by rememberFlowState(min, emptyList()) { dao.artists(min) }
    val genres by rememberFlowState(min, emptyList()) { dao.genres(min) }
    val folders by rememberFlowState(Unit, 0) { dao.folderCount() }
    val playlists by rememberFlowState(Unit, 0) { c.playlists.count() }
    val scanning by c.scans.isScanning.collectAsStateWithLifecycle(false)
    val tiles =
        listOf(
            Tile(R.string.all_songs, R.drawable.ic_note, pluralStringResource(R.plurals.n_songs, songs, songs), "tile_songs", Screen.Songs),
            Tile(
                R.string.albums,
                R.drawable.ic_album,
                pluralStringResource(R.plurals.n_albums, albums.size, albums.size),
                "tile_albums",
                Screen.Albums
            ),
            Tile(
                R.string.artists,
                R.drawable.ic_artist,
                pluralStringResource(R.plurals.n_artists, artists.size, artists.size),
                "tile_artists",
                Screen.Artists
            ),
            Tile(
                R.string.genres,
                R.drawable.ic_genre,
                pluralStringResource(R.plurals.n_genres, genres.size, genres.size),
                "tile_genres",
                Screen.Genres
            ),
            Tile(
                R.string.playlists,
                R.drawable.ic_playlist,
                pluralStringResource(R.plurals.n_playlists, playlists, playlists),
                "tile_playlists",
                Screen.Playlists
            ),
            Tile(
                R.string.folders,
                R.drawable.ic_folder,
                pluralStringResource(R.plurals.n_folders, folders, folders),
                "tile_folders",
                Screen.FolderRoots
            ),
            Tile(R.string.recently_added, R.drawable.ic_recent, stringResource(R.string.newest_first), "tile_recent", Screen.Recent)
        )
    BrowseScaffold(stringResource(R.string.library), showBack = false) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = rememberEntryGridState(),
            contentPadding = bottomPadding(),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(span = { GridItemSpan(3) }) { SearchField(onClick = { b.go(Screen.Search) }) }
            items(tiles, key = { it.tag }) { t -> LibraryTile(t) { b.go(t.screen) } }
            if (scanning) {
                item(span = { GridItemSpan(3) }) {
                    Text(
                        stringResource(R.string.scanning),
                        style = SeionType.RowSub,
                        modifier = Modifier.padding(8.dp).testTag("scan_status")
                    )
                }
            } else if (songs == 0) {
                item(span = { GridItemSpan(3) }) {
                    EmptyState(
                        R.drawable.ic_music_off,
                        stringResource(R.string.empty_library_title),
                        stringResource(R.string.empty_library_body)
                    ) {
                        Pill(stringResource(R.string.music_folders), "empty_open_folders", onClick = { b.go(Screen.MusicFolders) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SeionColors.SearchField)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag("home_search"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(R.drawable.ic_search), null, tint = SeionColors.Muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        // Shrinks rather than truncates (pt-BR at 1.3× font scale).
        BasicText(
            stringResource(R.string.search_hint),
            style = TextStyle(color = SeionColors.Muted, fontFamily = Roboto, fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 14.sp, stepSize = 0.5.sp)
        )
    }
}

@Composable
private fun LibraryTile(t: Tile, onClick: () -> Unit) {
    Column(
        Modifier
            .heightIn(min = 70.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SeionColors.Tile)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 8.dp, top = 9.dp, bottom = 8.dp)
            .testTag(t.tag)
    ) {
        Icon(painterResource(t.icon), null, tint = Color.White, modifier = Modifier.size(20.dp))
        val label = stringResource(t.label)
        val measurer = rememberTextMeasurer()
        BoxWithConstraints(Modifier.padding(top = 5.dp)) {
            val widthPx = constraints.maxWidth.toFloat()
            // The size at which no word breaks inside itself (no "Recentl / y" at 2.0x font scale).
            val size =
                remember(label, widthPx, measurer) {
                    WordSafe.fontSize(label, widthPx, 2) { text, sp ->
                        measurer.measure(
                            text,
                            TextStyle(fontFamily = Roboto, fontSize = sp.sp),
                            softWrap = false,
                            maxLines = 1
                        ).size.width.toFloat()
                    }
                }
            Text(
                label,
                fontSize = size.sp,
                lineHeight = (size * 1.2f).sp,
                color = SeionColors.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(hyphens = Hyphens.None)
            )
        }
        TileCount(t.count, Modifier.padding(top = 2.dp))
    }
}

/**
 * A home tile's count ("5052 músicas"): shrinks to fit instead of ending in "…"; above 1.5× font scale it may also
 * wrap at the space, since shrinking alone isn't enough at 2.0.
 */
@Composable
internal fun TileCount(text: String, modifier: Modifier = Modifier) {
    val twoLines = LocalDensity.current.fontScale > 1.5f
    BasicText(
        text,
        style = TextStyle(color = Color(0xFF8D8F96), fontFamily = Roboto, fontSize = 10.5.sp, lineHeight = 13.sp),
        maxLines = if (twoLines) 2 else 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 10.5.sp, stepSize = 0.5.sp),
        modifier = modifier.testTag("tile_count")
    )
}

// ---------------------------------------------------------------- Track lists

@Composable
fun TrackList(
    tracks: List<TrackEntity>,
    header: String?,
    modifier: Modifier = Modifier,
    numbered: Boolean = false,
    top: (@Composable () -> Unit)? = null
) {
    val b = LocalBrowse.current
    val p = LocalPlayerState.current
    val picker = LocalPlaylistPicker.current
    val addLabel = stringResource(R.string.add_to_playlist)
    LazyColumn(modifier.fillMaxSize(), state = rememberEntryListState(), contentPadding = bottomPadding()) {
        if (top != null) item(key = "top") { top() }
        if (header != null) {
            item(key = "header") {
                ListHeader(header) {
                    if (tracks.isNotEmpty()) {
                        Pill(stringResource(R.string.shuffle), "list_shuffle", icon = R.drawable.ic_shuffle) {
                            b.playTracks(tracks, 0, shuffle = true)
                        }
                    }
                }
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            val sub = if (numbered) artistSubtitle(t.artist) else trackSubtitle(t)
            TrackRow(
                title = t.title,
                subtitle = sub.text,
                subtitleArtists = sub,
                durationMs = t.durationMs,
                tag = "track_$i",
                artRef = if (numbered) null else t.artRef(),
                number = if (numbered) (t.trackNumber ?: (i + 1)) else null,
                isCurrent = p.current?.mediaId == t.path,
                isPlaying = p.isPlaying,
                modifier = Modifier.animateItem(),
                onLongClick = { picker.openTracks(t.title, listOf(t), single = true) },
                onLongClickLabel = addLabel
            ) { b.playTracks(tracks, i) }
        }
    }
}

@Composable
fun SongsScreen() {
    val dao = LocalContainer.current.db.tracks()
    val min = minDurationMs()
    val tracks by rememberFlowState(min, emptyList()) { dao.songs(min) }
    BrowseScaffold(stringResource(R.string.all_songs)) {
        TrackList(tracks, pluralStringResource(R.plurals.n_songs, tracks.size, tracks.size))
    }
}

@Composable
fun RecentScreen() {
    val dao = LocalContainer.current.db.tracks()
    val min = minDurationMs()
    val tracks by rememberFlowState(min, emptyList()) { dao.recentlyAdded(min, 100) }
    BrowseScaffold(stringResource(R.string.recently_added), extraMenu = addAllMenu(stringResource(R.string.recently_added), tracks)) {
        TrackList(tracks, pluralStringResource(R.plurals.n_songs, tracks.size, tracks.size))
    }
}

// ---------------------------------------------------------------- Albums

@Composable
fun AlbumsScreen() {
    val c = LocalContainer.current
    val dao = c.db.tracks()
    val min = minDurationMs()
    val settings by c.settingsState.collectAsStateWithLifecycle()
    val sort = settings.albumSort
    val albums by rememberFlowState(min to sort, emptyList()) { dao.albums(min, sort) }
    BrowseScaffold(stringResource(R.string.albums)) {
        AlbumGrid(albums, header = pluralStringResource(R.plurals.n_albums, albums.size, albums.size)) {
            AlbumSortChip(sort) { c.appScope.launch { c.settings.setAlbumSort(it) } }
        }
    }
}

/** The label of each album order, in the chip's menu order. */
private val AlbumSort.label: Int get() = when (this) {
    AlbumSort.TITLE -> R.string.album_sort_title
    AlbumSort.ARTIST -> R.string.album_sort_artist
    AlbumSort.YEAR -> R.string.album_sort_year
    AlbumSort.RECENT -> R.string.recently_added
}

/** "Title ▾" (the sort chip): opens the four orders, the current one checked; the choice is kept in the settings. */
@Composable
internal fun AlbumSortChip(sort: AlbumSort, onSort: (AlbumSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = stringResource(sort.label)
    Box {
        Chip(current, "album_sort", description = stringResource(R.string.album_sort_description, current), dropdown = true) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            for (option in AlbumSort.entries) {
                val selected = option == sort
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    onClick = {
                        open = false
                        if (!selected) onSort(option)
                    },
                    trailingIcon = if (selected) {
                        { Icon(painterResource(R.drawable.ic_check), null, tint = SeionColors.Accent, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    modifier = Modifier.testTag("album_sort_${option.name.lowercase()}").semantics { this.selected = selected }
                )
            }
        }
    }
}

@Composable
fun AlbumGrid(albums: List<AlbumRow>, header: String?, headerAction: (@Composable () -> Unit)? = null) {
    val b = LocalBrowse.current
    val addAlbum = rememberAddAlbum()
    val addLabel = stringResource(R.string.add_to_playlist)
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = rememberEntryGridState(),
        contentPadding = bottomPadding(16.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (header !=
            null
        ) {
            item(span = { GridItemSpan(2) }) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(header, style = SeionType.RowSub, modifier = Modifier.weight(1f))
                    headerAction?.invoke()
                }
            }
        }
        items(albums, key = { it.albumKey }) { a ->
            Column(
                Modifier
                    .combinedClickable(onLongClickLabel = addLabel, onLongClick = { addAlbum(a) }) { b.go(Screen.Album(a.albumKey)) }
                    .testTag("album_${a.title}")
            ) {
                val bmp = rememberCover(a.artRef(), 160.dp)
                org.seion.ui.components.CoverBitmap(bmp, Modifier.fillMaxWidth().aspectRatio(1f), 4.dp)
                Text(
                    a.title,
                    fontSize = 13.5.sp,
                    color = SeionColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp) // the card's width: see TrackRow
                )
                CompactArtistLine(a.artist, style = SeionType.RowSub)
            }
        }
    }
}

@Composable
fun AlbumScreen(key: String) {
    val dao = LocalContainer.current.db.tracks()
    val b = LocalBrowse.current
    val min = minDurationMs()
    val album by rememberFlowState(key, null) { dao.album(key) }
    val tracks by rememberFlowState(key to min, emptyList()) { dao.albumTracks(key, min) }
    val a = album
    BrowseScaffold(a?.title.orEmpty(), extraMenu = addAllMenu(a?.title.orEmpty(), tracks) + albumCoverMenu(a)) {
        TrackList(tracks, header = null, numbered = true, top = {
            if (a !=
                null
            ) {
                AlbumHeader(a, tracks, onPlay = { b.playTracks(tracks, 0) }, onShuffle = { b.playTracks(tracks, 0, shuffle = true) })
            }
        })
    }
}

/** Album header: the cover's blurred glow behind, the feathered cover in front, info, Play and Shuffle. */
@Composable
private fun AlbumHeader(a: AlbumRow, tracks: List<TrackEntity>, onPlay: () -> Unit, onShuffle: () -> Unit) {
    val bmp = rememberCover(a.artRef(), 320.dp)
    // At least 166 dp; taller when the pills wrap (long labels, large text).
    Box(Modifier.fillMaxWidth().heightIn(min = 166.dp)) {
        if (bmp != null) {
            HeaderGlow(bmp)
            FeatheredCover(bmp, 1f, true, Modifier.offset(16.dp, 8.dp).size(128.dp))
        } else {
            Cover(null, 128.dp, Modifier.offset(16.dp, 8.dp))
        }
        HeaderStack(
            text = {
                Column(Modifier.padding(start = 158.dp, end = 12.dp, top = 14.dp)) {
                    Text(
                        a.title,
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        color = SeionColors.Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("album_title")
                    )
                    CompactArtistLine(
                        a.artist,
                        Modifier.padding(top = 4.dp),
                        LocalTextStyle.current.merge(TextStyle(fontSize = 13.5.sp, color = Color(0xFFC4C5CA))),
                        tag = "album_artist"
                    )
                    val (h, m) = Format.totalMinutes(tracks.sumOf { it.durationMs })
                    val parts =
                        listOfNotNull(
                            a.year?.toString(),
                            pluralStringResource(R.plurals.n_tracks, tracks.size, tracks.size),
                            if (h > 0) stringResource(R.string.hours_minutes, h, m) else stringResource(R.string.minutes, m)
                        )
                    Text(parts.joinToString(" · "), style = SeionType.RowSub, modifier = Modifier.padding(top = 8.dp))
                }
            },
            actions = { AlbumHeaderActions(onPlay, onShuffle, Modifier.padding(start = 158.dp, end = 12.dp, bottom = 12.dp)) }
        )
    }
}

/**
 * A page header's decorative glow (album, playlist): the cover's colours behind the header, fading into the page. A 12 px
 * copy upscaled with bilinear filtering is already a smooth blur, so nothing is blurred per frame while the list scrolls.
 * It matches the header's size and never sets it: laid out with fillMaxSize in a list (unbounded height), the square
 * bitmap made the header as tall as it is wide (HeaderGlowHeightTest).
 */
@Composable
internal fun BoxScope.HeaderGlow(bmp: androidx.compose.ui.graphics.ImageBitmap) {
    val filter = remember { ambientFilter(1.5f, 0.6f) }
    val glow = remember(bmp) { bmp.asAndroidBitmap().scale(12, 12).asImageBitmap() }
    androidx.compose.foundation.Image(
        glow,
        null,
        contentScale = ContentScale.Crop,
        colorFilter = filter,
        filterQuality = FilterQuality.High,
        modifier = Modifier.matchParentSize()
    )
    Box(
        Modifier.matchParentSize().background(
            androidx.compose.ui.graphics.Brush
                .verticalGradient(0.4f to Color.Transparent, 1f to SeionColors.Page.copy(alpha = 0.95f))
        )
    )
}

/**
 * The header's text above its pills: the pills keep their usual position (110 dp) and move down when the text is
 * taller (large font scale), so they never cover the "N tracks · M min" line.
 */
@Composable
internal fun HeaderStack(text: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Layout(contents = listOf(text, actions)) { (t, a), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val tp = t.single().measure(loose)
        val ap = a.single().measure(loose)
        val y = maxOf(110.dp.roundToPx(), tp.height + 8.dp.roundToPx())
        layout(constraints.maxWidth, y + ap.height) {
            tp.place(0, 0)
            ap.place(0, y)
        }
    }
}

/**
 * The album header's Play and Shuffle pills. They keep their natural width and wrap to a second row when the column is
 * too narrow ("Reproduzir" + "Aleatório" in pt-BR, any language at a large font scale), never squeezing a label.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlbumHeaderActions(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(stringResource(R.string.play), "album_play", solid = true, icon = R.drawable.ic_play, onClick = onPlay)
        Pill(stringResource(R.string.shuffle), "album_shuffle", icon = R.drawable.ic_shuffle, onClick = onShuffle)
    }
}

// ---------------------------------------------------------------- Artists and genres

@Composable
fun NameList(
    rows: List<NameCount>,
    unknownLabel: String,
    @DrawableRes icon: Int,
    tagPrefix: String,
    onLongClick: ((String?) -> Unit)? = null,
    onClick: (String?) -> Unit
) {
    val addLabel = stringResource(R.string.add_to_playlist)
    LazyColumn(Modifier.fillMaxSize(), state = rememberEntryListState(), contentPadding = bottomPadding()) {
        item { ListHeader(pluralStringResource(R.plurals.n_entries, rows.size, rows.size)) }
        itemsIndexed(rows, key = { _, r -> r.name ?: "\u0000" }) { i, r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .rowClick({ onClick(r.name) }, onLongClick?.let { l -> { l(r.name) } }, addLabel)
                    .padding(horizontal = 16.dp)
                    .testTag("${tagPrefix}_$i"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(SeionColors.Tile), contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), null, tint = SeionColors.Secondary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        r.name ?: unknownLabel,
                        style = SeionType.RowTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth() // the whole column: right-to-left names get cut too early otherwise
                    )
                    Text(
                        pluralStringResource(R.plurals.n_albums, r.albumCount, r.albumCount) + " · " +
                            pluralStringResource(R.plurals.n_songs, r.trackCount, r.trackCount),
                        style = SeionType.RowSub,
                        maxLines = 1
                    )
                }
                Icon(painterResource(R.drawable.ic_chevron_right), null, tint = SeionColors.Faint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun ArtistsScreen() {
    val dao = LocalContainer.current.db.tracks()
    val b = LocalBrowse.current
    val min = minDurationMs()
    val rows by rememberFlowState(min, emptyList()) { dao.artists(min) }
    BrowseScaffold(stringResource(R.string.artists)) {
        val picker = LocalPlaylistPicker.current
        val unknown = stringResource(R.string.unknown_artist)
        NameList(rows, unknown, R.drawable.ic_artist, "artist", onLongClick = { name ->
            picker.open(AddRequest(name ?: unknown) { dao.artistTracks(name, min).first().map { it.toNewEntry() } })
        }) { b.go(Screen.Artist(it)) }
    }
}

@Composable
fun ArtistScreen(name: String?) {
    val dao = LocalContainer.current.db.tracks()
    val b = LocalBrowse.current
    val min = minDurationMs()
    val albums by rememberFlowState(name to min, emptyList()) { dao.artistAlbums(name, min) }
    val tracks by rememberFlowState(name to min, emptyList()) { dao.artistTracks(name, min) }
    val addAlbum = rememberAddAlbum()
    val addLabel = stringResource(R.string.add_to_playlist)
    BrowseScaffold(
        name ?: stringResource(R.string.unknown_artist),
        extraMenu = addAllMenu(name ?: stringResource(R.string.unknown_artist), tracks)
    ) {
        TrackList(tracks, header = null, top = {
            Column {
                SectionLabel(stringResource(R.string.albums))
                albums.forEach { a ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .rowClick({ b.go(Screen.Album(a.albumKey)) }, { addAlbum(a) }, addLabel)
                            .padding(horizontal = 16.dp)
                            .testTag("artist_album_${a.title}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Cover(a.artRef(), 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                a.title,
                                style = SeionType.RowTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth() // right-to-left names get cut too early otherwise
                            )
                            Text(
                                listOfNotNull(
                                    a.year?.toString(),
                                    pluralStringResource(R.plurals.n_tracks, a.trackCount, a.trackCount)
                                ).joinToString(" · "),
                                style = SeionType.RowSub
                            )
                        }
                    }
                }
                ListHeader(pluralStringResource(R.plurals.n_songs, tracks.size, tracks.size)) {
                    if (tracks.isNotEmpty()) {
                        Pill(stringResource(R.string.shuffle), "list_shuffle", icon = R.drawable.ic_shuffle) {
                            b.playTracks(tracks, 0, true)
                        }
                    }
                }
            }
        })
    }
}

@Composable
fun GenresScreen() {
    val dao = LocalContainer.current.db.tracks()
    val b = LocalBrowse.current
    val min = minDurationMs()
    val rows by rememberFlowState(min, emptyList()) { dao.genres(min) }
    BrowseScaffold(stringResource(R.string.genres)) {
        val picker = LocalPlaylistPicker.current
        val unknown = stringResource(R.string.unknown_genre)
        NameList(rows, unknown, R.drawable.ic_genre, "genre", onLongClick = { name ->
            picker.open(AddRequest(name ?: unknown) { dao.genreTracks(name, min).first().map { it.toNewEntry() } })
        }) { b.go(Screen.Genre(it)) }
    }
}

@Composable
fun GenreScreen(name: String?) {
    val dao = LocalContainer.current.db.tracks()
    val min = minDurationMs()
    val tracks by rememberFlowState(name to min, emptyList()) { dao.genreTracks(name, min) }
    BrowseScaffold(
        name ?: stringResource(R.string.unknown_genre),
        extraMenu = addAllMenu(name ?: stringResource(R.string.unknown_genre), tracks)
    ) {
        TrackList(tracks, pluralStringResource(R.plurals.n_songs, tracks.size, tracks.size))
    }
}

// ---------------------------------------------------------------- Search

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen() {
    val c = LocalContainer.current
    val b = LocalBrowse.current
    val dao = c.db.tracks()
    val min = minDurationMs()
    var query by rememberSaveable { mutableStateOfString() }
    val q = query.trim()
    val songs by rememberFlowState(q to min, emptyList()) { if (q.isEmpty()) flowOf(emptyList()) else dao.search(likePattern(q), min) }
    val albums by rememberFlowState(min, emptyList()) { dao.albums(min) }
    val artists by rememberFlowState(min, emptyList()) { dao.artists(min) }
    val albumHits = if (q.isEmpty()) {
        emptyList()
    } else {
        albums.filter {
            it.title.contains(q, true) || it.artist?.contains(q, true) == true
        }.take(6)
    }
    val artistHits = if (q.isEmpty()) emptyList() else artists.filter { it.name?.contains(q, true) == true }.take(6)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BrowseScaffold(stringResource(R.string.search), showSearch = false) {
        Row(
            Modifier.fillMaxWidth().padding(
                horizontal = 16.dp,
                vertical = 4.dp
            ).height(44.dp).clip(RoundedCornerShape(22.dp)).background(SeionColors.SearchField).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(R.drawable.ic_search), null, tint = SeionColors.Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(stringResource(R.string.search_hint), color = SeionColors.Muted, fontSize = 15.sp, maxLines = 1)
                BasicTextField(
                    query,
                    { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = SeionColors.Ink, fontFamily = Roboto, fontSize = 15.sp),
                    cursorBrush = SolidColor(SeionColors.Accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("search_input")
                )
            }
            if (query.isNotEmpty()) {
                org.seion.ui.components.IconBtn(R.drawable.ic_close, stringResource(R.string.clear), "search_clear", iconSize = 18.dp) {
                    query =
                        ""
                }
            }
        }
        val p = LocalPlayerState.current
        val picker = LocalPlaylistPicker.current
        val addAlbum = rememberAddAlbum()
        val addLabel = stringResource(R.string.add_to_playlist)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomPadding()) {
            if (q.isNotEmpty() && songs.isEmpty() && albumHits.isEmpty() && artistHits.isEmpty()) {
                item { EmptyState(R.drawable.ic_search, stringResource(R.string.no_results), null) }
            }
            if (artistHits.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.artists)) }
                items(artistHits, key = { "ar" + it.name }) { r ->
                    TrackRow(
                        r.name.orEmpty(),
                        pluralStringResource(
                            R.plurals.n_songs,
                            r.trackCount,
                            r.trackCount
                        ),
                        0,
                        "search_artist_${r.name}",
                        onLongClick = {
                            picker.open(AddRequest(r.name.orEmpty()) { dao.artistTracks(r.name, min).first().map { it.toNewEntry() } })
                        },
                        onLongClickLabel = addLabel,
                        onClick = {
                            b.go(Screen.Artist(r.name))
                        }
                    )
                }
            }
            if (albumHits.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.albums)) }
                items(albumHits, key = { "al" + it.albumKey }) { a ->
                    val sub = artistSubtitle(a.artist)
                    TrackRow(
                        a.title,
                        sub.text,
                        0,
                        "search_album_${a.title}",
                        artRef = a.artRef(),
                        subtitleArtists = sub,
                        onLongClick = { addAlbum(a) },
                        onLongClickLabel = addLabel,
                        onClick = { b.go(Screen.Album(a.albumKey)) }
                    )
                }
            }
            if (songs.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.songs)) }
                itemsIndexed(songs, key = { _, t -> "s" + t.id }) { i, t ->
                    val sub = trackSubtitle(t)
                    TrackRow(
                        t.title,
                        sub.text,
                        t.durationMs,
                        "search_song_$i",
                        artRef = t.artRef(),
                        subtitleArtists = sub,
                        isCurrent = p.current?.mediaId == t.path,
                        isPlaying = p.isPlaying,
                        onLongClick = { picker.openTracks(t.title, listOf(t), single = true) },
                        onLongClickLabel = addLabel
                    ) { b.playTracks(songs, i) }
                }
            }
        }
    }
}

/** "Add to playlist" in a page's ⋮: every song the page lists ([label] names the page in the picker). */
@Composable
internal fun addAllMenu(label: String, tracks: List<TrackEntity>): List<MenuAction> {
    if (tracks.isEmpty()) return emptyList()
    val picker = LocalPlaylistPicker.current
    return listOf(MenuAction(stringResource(R.string.add_to_playlist), "menu_add_to_playlist") { picker.openTracks(label, tracks) })
}

/** The album page's ⋮: Details, Change cover and, when the album has a chosen cover, Use original cover. */
@Composable
internal fun albumCoverMenu(a: AlbumRow?): List<MenuAction> {
    if (a == null) return emptyList()
    val c = LocalContainer.current
    val details = LocalDetailsSheet.current
    val covers = LocalCoverPicker.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val subject = CoverSubject.Album(a)
    val chosen = rememberHasChosenCover(subject)
    return listOfNotNull(
        MenuAction(stringResource(R.string.details), "menu_details") { details.open(subject) },
        MenuAction(stringResource(R.string.change_cover), "menu_change_cover") { covers.pick(subject) },
        if (chosen) {
            MenuAction(stringResource(R.string.use_original_cover), "menu_reset_cover") {
                c.useOriginalCover(subject) { toast(context, res.getString(R.string.cover_restored)) }
            }
        } else {
            null
        }
    )
}

/** Touch and hold on an album (grid, artist page, search): the album's songs as the album page lists them. */
@Composable
internal fun rememberAddAlbum(): (AlbumRow) -> Unit {
    val c = LocalContainer.current
    val picker = LocalPlaylistPicker.current
    val min = minDurationMs()
    return remember(picker, min) {
        { a ->
            picker.open(
                AddRequest(a.title, subject = CoverSubject.Album(a)) {
                    c.db.tracks().albumTracks(a.albumKey, min).first().map { it.toNewEntry() }
                }
            )
        }
    }
}

private fun mutableStateOfString() = androidx.compose.runtime.mutableStateOf("")
