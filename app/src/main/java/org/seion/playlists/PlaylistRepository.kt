package org.seion.playlists

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.media3.common.MediaItem
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import org.seion.art.ArtRef
import org.seion.library.AudioFiles
import org.seion.library.TrackDao
import org.seion.library.TrackEntity
import org.seion.playback.MediaItems

/** One row of a playlist: the entry, the library's row for its file (if indexed) and whether the file is there. */
data class PlaylistItem(val entry: PlaylistEntryEntity, val track: TrackEntity?, val available: Boolean) {
    val path: String get() = entry.path
    val title: String get() = track?.title ?: entry.title
    val artist: String? get() = track?.artist ?: entry.artist
    val album: String? get() = track?.albumTitle ?: entry.album
    val durationMs: Long get() = track?.durationMs ?: entry.durationMs

    fun artRef(): ArtRef = track?.let { ArtRef(it.path, it.folder, it.albumArtist ?: it.artist, it.album) }
        ?: ArtRef(entry.path, entry.path.substringBeforeLast('/'), entry.albumArtist ?: entry.artist, entry.album)

    fun mediaItem(): MediaItem = track?.let(MediaItems::fromTrack)
        ?: MediaItems.fromSnapshot(entry.path, entry.title, entry.artist, entry.album, entry.albumArtist, entry.durationMs)

    /** This row as a song to add to another playlist (the library's names when indexed). */
    fun toNewEntry() = NewEntry(entry.path, title, artist, album, track?.albumArtist ?: entry.albumArtist, durationMs)

    /** Same album for the mosaic: the library's album key, else album name + folder. */
    val albumKey: String get() = track?.albumKey ?: albumKeyOf(entry.path, entry.album)
}

private fun albumKeyOf(path: String, album: String?): String = (album?.lowercase() ?: "") + "\u0001" + path.substringBeforeLast('/')

fun TrackEntity.toNewEntry() = NewEntry(path, title, artist, albumTitle, albumArtist, durationMs)

/** A queue item (the media id is the file path). */
fun MediaItem.toNewEntry(): NewEntry {
    val m = mediaMetadata
    return NewEntry(
        path = mediaId,
        title = m.title?.toString() ?: AudioFiles.titleFromFileName(mediaId.substringAfterLast('/')),
        artist = m.artist?.toString(),
        album = m.albumTitle?.toString(),
        albumArtist = m.albumArtist?.toString(),
        durationMs = m.durationMs ?: 0L
    )
}

/** What an import did. [id] is null when nothing could be imported. */
data class ImportResult(val id: Long?, val name: String, val added: Int, val notFound: Int)

/** Playlists over [dao], resolved against the library ([tracks]); every call is main-safe. */
class PlaylistRepository(
    private val dao: PlaylistDao,
    private val tracks: TrackDao,
    private val volumeRoots: () -> List<String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fileExists: (String) -> Boolean = { File(it).isFile }
) {
    fun summaries(): Flow<List<PlaylistSummary>> = dao.summaries()

    fun summary(id: Long): Flow<PlaylistSummary?> = dao.summary(id)

    fun count(): Flow<Int> = dao.count()

    /** The mosaic covers of every playlist (PlaylistRules.mosaic over its first entries), by playlist id. */
    fun covers(): Flow<Map<Long, List<ArtRef>>> = dao.heads(MOSAIC_LOOKAHEAD).map { heads ->
        heads.groupBy { it.playlistId }.mapValues { (_, rows) ->
            PlaylistRules.mosaic(rows) { albumKeyOf(it.path, it.album) }.map {
                ArtRef(it.path, it.path.substringBeforeLast('/'), it.albumArtist ?: it.artist, it.album)
            }
        }
    }

    /**
     * The playlist's rows, resolved against the library and the files again whenever either changes (a rescan, a card
     * mounted). A row whose file is neither indexed nor on disk is unavailable: shown dimmed, skipped by Play.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun items(id: Long): Flow<List<PlaylistItem>> = combine(dao.entries(id), tracks.songCount(0)) { entries, _ -> entries }
        .mapLatest { resolve(it) }
        .flowOn(io)

    private suspend fun resolve(entries: List<PlaylistEntryEntity>): List<PlaylistItem> {
        val byPath = entries.map { it.path }.distinct().chunked(CHUNK).flatMap { tracks.byPaths(it) }.associateBy { it.path }
        val items = entries.map { e ->
            val t = byPath[e.path]
            PlaylistItem(e, t, t != null || fileExists(e.path))
        }
        // Keep the snapshots in step with the library (an online identification, edited tags): the lists' counts and
        // lengths and a later missing-file row show the latest names. Converges: unchanged rows are not written.
        val stale = items.mapNotNull { item ->
            val t = item.track ?: return@mapNotNull null
            val fresh = item.entry.copy(
                title = t.title,
                artist = t.artist,
                album = t.albumTitle,
                albumArtist = t.albumArtist,
                durationMs = t.durationMs
            )
            fresh.takeIf { it != item.entry }
        }
        if (stale.isNotEmpty()) runCatching { dao.updateSnapshots(stale) }.onFailure { Log.w(TAG, "snapshot refresh failed", it) }
        return items
    }

    suspend fun names(): List<String> = withContext(io) { dao.names() }

    suspend fun exists(id: Long): Boolean = withContext(io) { dao.exists(id) }

    suspend fun create(name: String, songs: List<NewEntry> = emptyList()): Long = withContext(io) {
        dao.create(PlaylistNames.clean(name), songs, now())
    }

    suspend fun rename(id: Long, name: String) = withContext(io) { dao.rename(id, PlaylistNames.clean(name), now()) }

    suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }

    suspend fun add(id: Long, songs: List<NewEntry>): AddResult = withContext(io) { dao.append(id, songs, now()) }

    /** The playlist's songs become [entries], in this order (reorder, remove, undo). */
    suspend fun setEntries(id: Long, entries: List<PlaylistEntryEntity>) = withContext(io) { dao.replaceEntries(id, entries, now()) }

    /**
     * Imports an M3U/M3U8 file as a new playlist named after the file. Each location is resolved against the file's own
     * folder (when the picker tells it), then looked up in the library by its file name and parent folders (a playlist
     * made on a computer); duplicates are skipped like any add, locations not found are counted.
     */
    suspend fun import(resolver: ContentResolver, uri: Uri, displayName: String?, fallbackName: String): ImportResult = withContext(io) {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("unreadable")
        val entries = M3u.parse(M3u.decode(bytes))
        val baseDir = documentPath(uri)?.substringBeforeLast('/')
        val known = tracks.stamps().map { it.path }
        val knownSet = known.toHashSet()
        val byName = M3u.indexByFileName(known)
        val paths = ArrayList<String>()
        var notFound = 0
        for (e in entries) {
            coroutineContext.ensureActive()
            val direct = M3u.resolve(e.location, baseDir)?.takeIf { it in knownSet || fileExists(it) }
            val path = direct ?: M3u.matchBySuffix(e.location, byName)
            if (path == null) notFound++ else paths += path
        }
        val rows = paths.distinct().chunked(CHUNK).flatMap { tracks.byPaths(it) }.associateBy { it.path }
        val titles = entries.associate { e -> (M3u.resolve(e.location, baseDir) ?: e.location) to e.title }
        val songs = paths.map { p ->
            rows[p]?.toNewEntry() ?: NewEntry(p, titles[p] ?: AudioFiles.titleFromFileName(p.substringAfterLast('/')), null, null, null, 0)
        }
        val base = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: fallbackName
        val name = PlaylistNames.unique(base, dao.names(), fallbackName)
        if (songs.isEmpty()) return@withContext ImportResult(null, name, 0, notFound)
        val plan = PlaylistRules.planAdd(emptyList(), songs) { it.path }
        val id = dao.create(name, plan.toAdd, now())
        ImportResult(id, name, plan.toAdd.size, notFound)
    }

    /** Writes the playlist as M3U8 into [uri] (a file the user created in the system picker). */
    suspend fun export(resolver: ContentResolver, uri: Uri, id: Long) = withContext(io) {
        val entries = dao.entriesNow(id)
        val baseDir = documentPath(uri)?.substringBeforeLast('/')
        val roots = volumeRoots()
        val text =
            M3u.write(
                entries.map { M3u.Item(it.path, it.title, it.artist, it.durationMs) },
                baseDir
            ) { p -> roots.filter { p == it || p.startsWith("$it/") }.maxByOrNull { it.length } }
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("not writable")
    }

    /** Every audio file under [dir] (its own files first, then each subfolder, both in name order), as the library knows them. */
    suspend fun folderSongs(dir: File): List<NewEntry> = withContext(io) {
        val files = ArrayList<String>()
        val roots = volumeRoots().toSet()
        walk(dir, 0, files, roots)
        val rows = files.chunked(CHUNK).flatMap { tracks.byPaths(it) }.associateBy { it.path }
        files.map { p ->
            rows[p]?.toNewEntry() ?: NewEntry(p, AudioFiles.titleFromFileName(p.substringAfterLast('/')), null, null, null, 0)
        }
    }

    /** One file (a folder row's audio file), from the library when it is indexed. */
    suspend fun fileSong(path: String): NewEntry = withContext(io) {
        tracks.byPaths(listOf(path)).firstOrNull()?.toNewEntry()
            ?: NewEntry(path, AudioFiles.titleFromFileName(path.substringAfterLast('/')), null, null, null, 0)
    }

    private suspend fun walk(dir: File, depth: Int, out: MutableList<String>, roots: Set<String>) {
        if (depth > MAX_DEPTH) return
        coroutineContext.ensureActive()
        val children = dir.listFiles().orEmpty().filterNot { AudioFiles.isHidden(it.name) }
        children
            .filter { it.isFile && AudioFiles.isAudio(it.name) }
            .sortedWith(compareBy(AudioFiles.NAME_ORDER) { it.name })
            .forEach { out += it.absolutePath }
        children
            .filter { it.isDirectory && !AudioFiles.shouldSkipDir(it) { d -> d.absolutePath in roots } }
            .sortedWith(compareBy(AudioFiles.NAME_ORDER) { it.name })
            .forEach { walk(it, depth + 1, out, roots) }
    }

    /**
     * The real path of a file picked in the system picker, when it lives in shared storage (`primary:Music/x.m3u` →
     * `/storage/emulated/0/Music/x.m3u`); null for other providers (then only absolute and library-matched paths work).
     */
    private fun documentPath(uri: Uri): String? = runCatching {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val id = DocumentsContract.getDocumentId(uri)
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val root = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
        if (rel.isEmpty()) root else "$root/$rel"
    }.getOrNull()

    private companion object {
        const val TAG = "SeionPlaylists"
        const val CHUNK = 500
        const val MAX_DEPTH = 16

        /** How many first entries per playlist are read to find four different albums for the mosaic. */
        const val MOSAIC_LOOKAHEAD = 24
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
