package org.seion.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.seion.AppContainer
import org.seion.library.NameCount
import org.seion.library.TrackEntity
import org.seion.library.likePattern
import org.seion.playlists.PlaylistItem
import org.seion.playlists.PlaylistRules
import org.seion.playlists.PlaylistSummary

/**
 * The browse tree's data: the library index with the app's rules (the short-track setting, the Albums grid's title
 * order, the Playlists screen's order) and playlists.db; the live queue comes from the service ([queue], read on the
 * main thread there).
 */
class LibraryBrowseSource(private val container: AppContainer, private val queue: suspend () -> BrowseQueue) : BrowseSource {
    private val tracks get() = container.db.tracks()

    private suspend fun minMs() = container.settings.current().minDurationMs

    override suspend fun albums(): List<BrowseAlbum> = withContext(Dispatchers.IO) {
        tracks.browseAlbums(minMs()).map { BrowseAlbum(it.trackId, it.title, it.artist, it.trackCount) }
    }

    override suspend fun albumTracks(anyTrackId: Long): List<TrackEntity>? = withContext(Dispatchers.IO) {
        val key = tracks.albumKeyOf(anyTrackId) ?: return@withContext null
        tracks.albumTracks(key, minMs()).first()
    }

    override suspend fun artists(): List<NameCount> = withContext(Dispatchers.IO) { tracks.artists(minMs()).first() }

    override suspend fun artistTracks(name: String?): List<TrackEntity> = withContext(Dispatchers.IO) {
        tracks.artistTracks(name, minMs()).first()
    }

    override suspend fun playlists(): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        val sort = container.settings.current().playlistSort
        PlaylistRules.sorted(container.playlists.summaries().first(), sort, { it.name }, { it.updatedAt }, { it.id })
    }

    override suspend fun playlistItems(id: Long): List<PlaylistItem>? = withContext(Dispatchers.IO) {
        if (!container.playlists.exists(id)) null else container.playlists.items(id).first()
    }

    override suspend fun songCount(): Int = withContext(Dispatchers.IO) { tracks.songCount(minMs()).first() }

    override suspend fun songs(offset: Int, limit: Int): List<TrackEntity> = withContext(Dispatchers.IO) {
        tracks.songsPage(minMs(), limit, offset)
    }

    override suspend fun allSongs(): List<TrackEntity> = withContext(Dispatchers.IO) { tracks.songs(minMs()).first() }

    override suspend fun search(query: String): List<TrackEntity> = withContext(Dispatchers.IO) {
        tracks.search(likePattern(query), minMs()).first()
    }

    override suspend fun queue(): BrowseQueue = queue.invoke()

    override suspend fun trackIds(paths: List<String>): Map<String, Long> = withContext(Dispatchers.IO) {
        paths.distinct().chunked(CHUNK).flatMap { tracks.byPaths(it) }.associate { it.path to it.id }
    }

    private companion object {
        /** SQLite's bound-parameter limit is 999 on older platforms. */
        const val CHUNK = 500
    }
}
