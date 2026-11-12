package org.seion.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.net.URLDecoder
import java.net.URLEncoder
import org.seion.library.NameCount
import org.seion.library.TrackEntity
import org.seion.playlists.PlaylistItem
import org.seion.playlists.PlaylistRules
import org.seion.playlists.PlaylistSummary

/**
 * The media ids of the browse tree that Android Auto, AVRCP browsing and other media browsers see.
 * They are opaque: a node or a song is named by a library row id, a playlist id, a playlist entry id or
 * a queue position, **never by a file path** (an album key holds the folder's path for untagged albums, so albums are
 * named by one of their track ids). A playable id carries its list, so a tap plays that list from the song, as in the
 * app: `<list>|<key>`. Queue items (the session's own playlist) keep the file path as their media id; a path always
 * starts with "/", which no browse id does.
 */
object BrowseIds {
    const val ROOT = "root"
    const val RECENT = "recent"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val PLAYLISTS = "playlists"
    const val SONGS = "songs"

    private const val ALBUM = "album/"
    private const val ARTIST = "artist/"
    private const val PLAYLIST = "playlist/"
    private const val SEARCH = "search/"
    private const val SEP = '|'
    private const val GROUP = '@'

    /** A list that can be browsed and played from (the part of an id before [SEP]). */
    sealed interface Node {
        data object Root : Node

        /** The play queue from the current song on (the system's "recent" root answers with its first item). */
        data object Recent : Node

        data object Albums : Node

        data object Artists : Node

        data object Playlists : Node

        data object Songs : Node

        /** An album, by the id of any of its tracks. */
        data class Album(val trackId: Long) : Node

        /** An artist (album artist, else artist, as the Artists tab groups); null = no artist tag. */
        data class Artist(val name: String?) : Node

        data class Playlist(val id: Long) : Node

        /** The songs a search found (playable only; never listed as a node). */
        data class Search(val query: String) : Node
    }

    /** A playable song inside [list]: a track id, a playlist entry id, or (in [Node.Recent]) `<playlist index>.<hash>`. */
    data class Song(val list: Node, val key: String)

    fun isBrowseId(id: String): Boolean = id.isNotEmpty() && !id.startsWith("/")

    fun node(n: Node): String = when (n) {
        Node.Root -> ROOT
        Node.Recent -> RECENT
        Node.Albums -> ALBUMS
        Node.Artists -> ARTISTS
        Node.Playlists -> PLAYLISTS
        Node.Songs -> SONGS
        is Node.Album -> ALBUM + n.trackId
        // "~" is no encoded name (URL encoding leaves it alone, but an artist named "~" encodes to "%7E" below).
        is Node.Artist -> ARTIST + (n.name?.let(::encode) ?: "~")
        is Node.Playlist -> PLAYLIST + n.id
        is Node.Search -> SEARCH + encode(n.query)
    }

    fun song(list: Node, key: String): String = node(list) + SEP + key

    /** Section [index] of a long list (`<list>@<n>`; see BrowseTree.children). Encoded names never contain "@". */
    fun group(nodeId: String, index: Int): String = "$nodeId$GROUP$index"

    /** (list id, section) of a section id; (id, null) for anything else. */
    fun splitGroup(id: String): Pair<String, Int?> {
        val at = id.lastIndexOf(GROUP)
        if (at <= 0 || id.indexOf(SEP) >= 0) return id to null
        val index = id.substring(at + 1).toIntOrNull()?.takeIf { it >= 0 } ?: return id to null
        return id.substring(0, at) to index
    }

    /** The queue key of playlist index [index]: the index plus a short hash of its media id, checked on play. */
    fun queueKey(index: Int, mediaId: String): String = "$index.${Integer.toHexString(mediaId.hashCode())}"

    fun parseNode(id: String): Node? = when {
        id == ROOT -> Node.Root
        id == RECENT -> Node.Recent
        id == ALBUMS -> Node.Albums
        id == ARTISTS -> Node.Artists
        id == PLAYLISTS -> Node.Playlists
        id == SONGS -> Node.Songs
        id.startsWith(ALBUM) -> id.removePrefix(ALBUM).toLongOrNull()?.let { Node.Album(it) }
        id.startsWith(ARTIST) -> id.removePrefix(ARTIST).let { if (it == "~") Node.Artist(null) else decode(it)?.let(Node::Artist) }
        id.startsWith(PLAYLIST) -> id.removePrefix(PLAYLIST).toLongOrNull()?.let { Node.Playlist(it) }
        id.startsWith(SEARCH) -> decode(id.removePrefix(SEARCH))?.let(Node::Search)
        else -> null
    }

    /** A playable id, or null (a node id, a path, junk). Names are encoded, so the last [SEP] always splits. */
    fun parseSong(id: String): Song? {
        val at = id.lastIndexOf(SEP)
        if (at <= 0 || at == id.lastIndex) return null
        val list = parseNode(id.substring(0, at)) ?: return null
        if (list == Node.Root || list == Node.Albums || list == Node.Artists || list == Node.Playlists) return null
        return Song(list, id.substring(at + 1))
    }

    // Percent-encoding (UTF-8), so names never contain "/", "|" or "@"; "+" (form encoding of a space) is written as %20.
    // The charset is passed by name: the Charset overloads are API 33+ and throw NoSuchMethodError on Android 12, which
    // broke search and every artist id there.
    private fun encode(s: String): String = URLEncoder.encode(s, UTF_8).replace("+", "%20").replace("~", "%7E")

    private fun decode(s: String): String? = runCatching { URLDecoder.decode(s.replace("+", "%2B"), UTF_8) }.getOrNull()

    private const val UTF_8 = "UTF-8"
}

/** One album row of the browse tree, named by one of its tracks (see [BrowseIds]), whose cover it shows. */
data class BrowseAlbum(val trackId: Long, val title: String, val artist: String?, val trackCount: Int)

/** The live play queue: [items] in playlist order, the [playOrder] (the shuffle order while shuffled), the current index. */
data class BrowseQueue(val items: List<MediaItem>, val playOrder: List<Int>, val current: Int, val positionMs: Long) {
    companion object {
        val EMPTY = BrowseQueue(emptyList(), emptyList(), -1, 0)
    }
}

/** What the browse tree reads: the library index (with the short-track rule applied), playlists.db and the queue. */
interface BrowseSource {
    suspend fun albums(): List<BrowseAlbum>

    /** The album of track [anyTrackId], in track order; null when that track is gone. */
    suspend fun albumTracks(anyTrackId: Long): List<TrackEntity>?

    suspend fun artists(): List<NameCount>

    suspend fun artistTracks(name: String?): List<TrackEntity>

    /** In the Playlists screen's order. */
    suspend fun playlists(): List<PlaylistSummary>

    /** The playlist's rows (unavailable ones included); null when there is no such playlist. */
    suspend fun playlistItems(id: Long): List<PlaylistItem>?

    suspend fun songCount(): Int

    /** All songs' page in the Songs list order (title, the leading-"The" rule). */
    suspend fun songs(offset: Int, limit: Int): List<TrackEntity>

    suspend fun allSongs(): List<TrackEntity>

    /** The app's search (title, artist or album), at most 200 songs. */
    suspend fun search(query: String): List<TrackEntity>

    suspend fun queue(): BrowseQueue

    /** Library row ids of these files (for queue items' covers); a file the index doesn't know is missing. */
    suspend fun trackIds(paths: List<String>): Map<String, Long>
}

/** The tree's words (from the resources, in Seion's language). */
interface BrowseStrings {
    fun title(node: BrowseIds.Node): String

    fun unknownArtist(): String

    fun songs(count: Int): String
}

/** A list to play: queue items (path media ids, Seion's artwork URIs) and where to start. */
data class BrowsePlay(val items: List<MediaItem>, val start: Int, val positionMs: Long = 0, val sameQueue: Boolean = false)

/**
 * The browse tree: root → Play queue (the "recent" content), Albums, Artists,
 * Playlists, Songs (paged; 5,000+ songs). Every item a browser sees is built here, from opaque ids, titles and
 * `content://` covers ([artUri]); never from a file path. [play] turns a browse id back into queue items for the
 * session (the same items the app would queue: shuffle and repeat rules stay the session's).
 */
class BrowseTree(private val source: BrowseSource, private val strings: BrowseStrings, private val artUri: (Long) -> Uri) {
    /** Root children, in this order; a car that shows at most N tabs keeps the first N of [ROOT_PRIORITY]. */
    fun root(childrenLimit: Int = Int.MAX_VALUE): List<MediaItem> {
        val keep = ROOT_PRIORITY.take(childrenLimit.coerceAtLeast(0)).toSet()
        return ROOT_ORDER.filter { it in keep }.map(::nodeItem)
    }

    fun rootItem(): MediaItem = nodeItem(BrowseIds.Node.Root)

    /**
     * A page of [parentId]'s children; null when the parent doesn't exist (any more). A browser that asks for everything
     * at once (the platform's MediaBrowser protocol without paging: Android Auto, Bluetooth; Media3 then cuts the answer
     * at 256 KB, about 500 songs) gets a long list as sections of [GROUP_SIZE] (`<list>@<n>`, "First title – Last
     * title"), so all 5,000 songs stay reachable; a paging browser gets the flat list.
     */
    suspend fun children(parentId: String, page: Int, pageSize: Int, childrenLimit: Int = Int.MAX_VALUE): List<MediaItem>? {
        val (nodeId, group) = BrowseIds.splitGroup(parentId)
        val node = BrowseIds.parseNode(nodeId) ?: return null
        if (pageSize <= 0 || page < 0) return emptyList()
        val offset = page.toLong() * pageSize
        if (group != null) {
            if (node == BrowseIds.Node.Root) return null
            val all = flat(node) ?: return null
            if (group.toLong() * GROUP_SIZE >= all.size) return null
            return all.page(group.toLong() * GROUP_SIZE, GROUP_SIZE).page(offset, pageSize)
        }
        if (node == BrowseIds.Node.Root) return root(childrenLimit).page(offset, pageSize)
        if (pageSize >= UNPAGED) {
            val all = flat(node) ?: return null
            return (if (all.size > GROUP_SIZE) groups(nodeId, all) else all).page(offset, pageSize)
        }
        // Songs are paged in the database (5,000+ rows); the other lists are small enough to slice.
        if (node == BrowseIds.Node.Songs) {
            if (offset >= Int.MAX_VALUE) return emptyList()
            return source.songs(offset.toInt(), pageSize).map { trackItem(BrowseIds.Node.Songs, it) }
        }
        return flat(node)?.page(offset, pageSize)
    }

    /** All of [node]'s children (not the root's). */
    private suspend fun flat(node: BrowseIds.Node): List<MediaItem>? = when (node) {
        BrowseIds.Node.Root -> root()
        BrowseIds.Node.Songs -> source.allSongs().map { trackItem(BrowseIds.Node.Songs, it) }
        BrowseIds.Node.Albums -> source.albums().map(::albumItem)
        BrowseIds.Node.Artists -> source.artists().map(::artistItem)
        BrowseIds.Node.Playlists -> source.playlists().map { playlistNodeItem(it) }
        is BrowseIds.Node.Album -> source.albumTracks(node.trackId)?.map { trackItem(node, it) }
        is BrowseIds.Node.Artist -> source.artistTracks(node.name).map { trackItem(node, it) }
        is BrowseIds.Node.Playlist -> source.playlistItems(node.id)?.filter { it.available }?.let { entryItems(node, it) }
        BrowseIds.Node.Recent -> recentItems()
        is BrowseIds.Node.Search -> searchItems(node.query)
    }

    /** Sections of [GROUP_SIZE] children, named by their first and last titles. */
    private fun groups(nodeId: String, all: List<MediaItem>): List<MediaItem> = all.chunked(GROUP_SIZE).mapIndexed { i, chunk ->
        val first = chunk.first().mediaMetadata.title
        val last = chunk.last().mediaMetadata.title
        browsable(
            BrowseIds.group(nodeId, i),
            "$first – $last",
            strings.songs(chunk.size).takeIf {
                chunk.first().isSong()
            },
            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            null
        )
    }

    private fun MediaItem.isSong() = mediaMetadata.isPlayable == true

    /** The item [id] names (a node or a song), or null. */
    suspend fun item(id: String): MediaItem? {
        BrowseIds.parseSong(id)?.let { song ->
            return when (val list = song.list) {
                is BrowseIds.Node.Playlist -> {
                    val entry = song.key.toLongOrNull() ?: return null
                    source.playlistItems(list.id)?.firstOrNull {
                        it.entry.id == entry && it.available
                    }?.let { entryItems(list, listOf(it)).single() }
                }
                BrowseIds.Node.Recent -> recentItems().firstOrNull { it.mediaId == id }
                else -> {
                    val trackId = song.key.toLongOrNull() ?: return null
                    tracksOf(list)?.firstOrNull { it.id == trackId }?.let { trackItem(list, it) }
                }
            }
        }
        val (nodeId, group) = BrowseIds.splitGroup(id)
        if (group != null) return children(nodeId, 0, Int.MAX_VALUE)?.getOrNull(group)?.takeIf { it.mediaId == id }
        val node = BrowseIds.parseNode(id) ?: return null
        return when (node) {
            is BrowseIds.Node.Album -> source.albums().firstOrNull { it.trackId == node.trackId }?.let(::albumItem)
                ?: source.albumTracks(node.trackId)?.firstOrNull()?.let { t ->
                    albumItem(BrowseAlbum(node.trackId, t.albumTitle, t.albumArtist ?: t.artist, 0))
                }
            is BrowseIds.Node.Artist -> source.artists().firstOrNull { it.name.equals(node.name, ignoreCase = true) }?.let(::artistItem)
            is BrowseIds.Node.Playlist -> source.playlists().firstOrNull { it.id == node.id }?.let { playlistNodeItem(it) }
            is BrowseIds.Node.Search -> null
            else -> nodeItem(node)
        }
    }

    /** What a search box finds (the app's search: title, artist or album). */
    suspend fun search(query: String): List<MediaItem> = searchItems(query)

    /**
     * The queue a browse id starts: the song's list from that song (an album, an artist, a playlist's available songs,
     * all songs, the search results), or the play queue at that song. A voice request ("play …") arrives as an empty id
     * with a [searchQuery]: its results, or the play queue when it is empty ("play music"). null = nothing to play.
     */
    suspend fun play(id: String, searchQuery: String? = null): BrowsePlay? {
        if (id.isEmpty()) {
            val q = searchQuery?.trim().orEmpty()
            if (q.isEmpty()) return resumeQueue(null)
            val found = source.search(q)
            return if (found.isEmpty()) null else BrowsePlay(found.map(MediaItems::fromTrack), 0)
        }
        val song = BrowseIds.parseSong(id)
        if (song == null) {
            // A node id played as a whole (a car's "play" on a list): its first song.
            return when (val node = BrowseIds.parseNode(id)) {
                null, BrowseIds.Node.Root, BrowseIds.Node.Albums, BrowseIds.Node.Artists, BrowseIds.Node.Playlists -> null
                BrowseIds.Node.Recent -> resumeQueue(null)
                is BrowseIds.Node.Playlist -> playlistPlay(node.id, null)
                else -> tracksOf(node)?.takeIf { it.isNotEmpty() }?.let { BrowsePlay(it.map(MediaItems::fromTrack), 0) }
            }
        }
        return when (val list = song.list) {
            BrowseIds.Node.Recent -> resumeQueue(song.key)
            is BrowseIds.Node.Playlist -> playlistPlay(list.id, song.key.toLongOrNull() ?: return null)
            else -> {
                val trackId = song.key.toLongOrNull() ?: return null
                val tracks = tracksOf(list) ?: return null
                val start = tracks.indexOfFirst { it.id == trackId }
                if (start < 0) null else BrowsePlay(tracks.map(MediaItems::fromTrack), start)
            }
        }
    }

    /** A single song for "add to queue" from a browser: null for a node or a gone song. */
    suspend fun single(id: String): MediaItem? {
        val p = play(id) ?: return null
        if (BrowseIds.parseSong(id) == null) return null
        return p.items.getOrNull(p.start)
    }

    // --- items ---

    private fun nodeItem(node: BrowseIds.Node): MediaItem = MediaItem
        .Builder()
        .setMediaId(BrowseIds.node(node))
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(strings.title(node))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(
                    when (node) {
                        BrowseIds.Node.Albums -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
                        BrowseIds.Node.Artists -> MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
                        BrowseIds.Node.Playlists -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
                        else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                    }
                ).build()
        ).build()

    private fun albumItem(a: BrowseAlbum): MediaItem = browsable(
        BrowseIds.node(BrowseIds.Node.Album(a.trackId)),
        a.title,
        a.artist ?: strings.unknownArtist(),
        MediaMetadata.MEDIA_TYPE_ALBUM,
        artUri(a.trackId)
    )

    private fun artistItem(a: NameCount): MediaItem = browsable(
        BrowseIds.node(BrowseIds.Node.Artist(a.name)),
        a.name ?: strings.unknownArtist(),
        strings.songs(a.trackCount),
        MediaMetadata.MEDIA_TYPE_ARTIST,
        null
    )

    private suspend fun playlistNodeItem(p: PlaylistSummary): MediaItem {
        // The cover: the first available song's that the library knows (the mosaic's first tile).
        val first = source.playlistItems(p.id)?.firstOrNull { it.available && it.track != null }?.track
        return browsable(
            BrowseIds.node(BrowseIds.Node.Playlist(p.id)),
            p.name,
            strings.songs(p.trackCount),
            MediaMetadata.MEDIA_TYPE_PLAYLIST,
            first?.let { artUri(it.id) }
        )
    }

    private fun browsable(id: String, title: String, subtitle: String?, type: Int, art: Uri?): MediaItem = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(art)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .build()
        ).build()

    private fun trackItem(list: BrowseIds.Node, t: TrackEntity): MediaItem =
        song(BrowseIds.song(list, t.id.toString()), t.title, t.artist, t.albumTitle, t.durationMs, artUri(t.id))

    private fun entryItems(list: BrowseIds.Node.Playlist, items: List<PlaylistItem>): List<MediaItem> = items.map { item ->
        song(
            BrowseIds.song(list, item.entry.id.toString()),
            item.title,
            item.artist,
            item.album,
            item.durationMs,
            item.track?.let { artUri(it.id) }
        )
    }

    private fun song(id: String, title: String, artist: String?, album: String?, durationMs: Long, art: Uri?): MediaItem = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setArtist(artist)
                .setSubtitle(artist ?: strings.unknownArtist())
                .setAlbumTitle(album)
                .setArtworkUri(art)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .apply { if (durationMs > 0) setDurationMs(durationMs) }
                .build()
        ).build()

    /** The play queue from the current song on, in play order (the shuffle order while shuffled). */
    private suspend fun recentItems(): List<MediaItem> {
        val q = source.queue()
        if (q.items.isEmpty()) return emptyList()
        val at = q.playOrder.indexOf(q.current).coerceAtLeast(0)
        val order = q.playOrder.drop(at)
        val ids = source.trackIds(order.map { q.items[it].mediaId })
        return order.map { index ->
            val item = q.items[index]
            val m = item.mediaMetadata
            song(
                BrowseIds.song(BrowseIds.Node.Recent, BrowseIds.queueKey(index, item.mediaId)),
                m.title?.toString().orEmpty(),
                m.artist?.toString(),
                m.albumTitle?.toString(),
                m.durationMs ?: 0,
                ids[item.mediaId]?.let(artUri)
            )
        }
    }

    private suspend fun searchItems(query: String): List<MediaItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return source.search(q).map { trackItem(BrowseIds.Node.Search(q), it) }
    }

    private suspend fun tracksOf(list: BrowseIds.Node): List<TrackEntity>? = when (list) {
        is BrowseIds.Node.Album -> source.albumTracks(list.trackId)
        is BrowseIds.Node.Artist -> source.artistTracks(list.name)
        BrowseIds.Node.Songs -> source.allSongs()
        is BrowseIds.Node.Search -> source.search(list.query)
        else -> null
    }

    private suspend fun playlistPlay(id: Long, entryId: Long?): BrowsePlay? {
        val items = source.playlistItems(id) ?: return null
        val tapped = if (entryId == null) items.indexOfFirst { it.available } else items.indexOfFirst { it.entry.id == entryId }
        val (playable, start) = PlaylistRules.playable(items, tapped, PlaylistItem::available) ?: return null
        return BrowsePlay(playable.map { it.mediaItem() }, start)
    }

    /**
     * The play queue at [key] (`<index>.<hash>`, checked against the queue as it is now; a queue that changed since the
     * browser listed it is searched for the same file), or at the current song for null. [BrowsePlay.sameQueue]: the
     * session should seek inside the queue it has rather than replace it, so a shuffle order (Edit order) survives.
     */
    private suspend fun resumeQueue(key: String?): BrowsePlay? {
        val q = source.queue()
        if (q.items.isEmpty()) return null
        if (key == null) return BrowsePlay(q.items, q.current.coerceIn(0, q.items.lastIndex), q.positionMs, sameQueue = true)
        val index = key.substringBefore('.').toIntOrNull() ?: return null
        val hash = key.substringAfter('.', "")
        val at = if (index in q.items.indices && BrowseIds.queueKey(index, q.items[index].mediaId) == key) {
            index
        } else {
            q.items.indices.firstOrNull { BrowseIds.queueKey(it, q.items[it].mediaId).substringAfter('.') == hash } ?: return null
        }
        return BrowsePlay(q.items, at, if (at == q.current) q.positionMs else 0, sameQueue = true)
    }

    companion object {
        /**
         * Children per section for a browser that asks for everything at once: about 150 KB of songs, well under the
         * 256 KB that Media3 cuts a platform MediaBrowser answer at.
         */
        const val GROUP_SIZE = 300

        /** A page size this large means "everything" (the platform protocol without paging asks for Int.MAX_VALUE). */
        const val UNPAGED = Int.MAX_VALUE / 2

        /** The root's order: the queue first, then the library's tabs. */
        val ROOT_ORDER = listOf(
            BrowseIds.Node.Recent,
            BrowseIds.Node.Albums,
            BrowseIds.Node.Artists,
            BrowseIds.Node.Playlists,
            BrowseIds.Node.Songs
        )

        /**
         * What a car with fewer tabs keeps: the library's lists first; the play queue goes first (cars show their own
         * queue), then Songs (every song is under Albums and Artists too).
         */
        val ROOT_PRIORITY = listOf(
            BrowseIds.Node.Albums,
            BrowseIds.Node.Artists,
            BrowseIds.Node.Playlists,
            BrowseIds.Node.Songs,
            BrowseIds.Node.Recent
        )

        private fun <T> List<T>.page(offset: Long, size: Int): List<T> {
            if (offset >= this.size) return emptyList()
            val from = offset.toInt()
            val to = (from.toLong() + size).coerceAtMost(this.size.toLong()).toInt()
            return subList(from, to)
        }
    }
}
