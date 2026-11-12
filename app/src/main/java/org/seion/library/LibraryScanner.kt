package org.seion.library

import android.util.Log
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ScanResult(val found: Int, val read: Int, val removed: Int, val unchanged: Int)

/** Album grouping rule (pure, unit-tested); see [TrackEntity]. */
object AlbumKeys {
    fun of(album: String?, albumArtist: String?, folder: String): String = if (album == null) {
        "folder:$folder"
    } else {
        album.lowercase() + "\u0001" + (albumArtist?.lowercase() ?: "folder:$folder")
    }
}

/**
 * Walks the configured roots with java.io.File (full file access), then applies [ScanPlanner]'s incremental
 * plan: only new or modified files get their tags read. Runs inside [ScanWorker].
 */
class LibraryScanner(
    private val dao: TrackDao,
    private val tagReader: TagReader,
    private val isVolumeRoot: (File) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun scan(roots: List<String>): ScanResult = withContext(Dispatchers.IO) {
        val found = ArrayList<FoundFile>()
        roots.map(::File).filter { it.isDirectory }.forEach { walk(it, 0, found) }
        val indexed = dao.stamps().associate { it.path to it.lastModified }
        val plan = ScanPlanner.plan(indexed, found)
        var read = 0
        plan.toRead.chunked(BATCH).forEach { batch ->
            coroutineContext.ensureActive()
            val existing = dao.byPaths(batch.map { it.path }).associateBy { it.path }
            val rows = batch.map { toEntity(it, existing[it.path]) }
            dao.apply(rows, emptyList())
            read += rows.size
        }
        dao.apply(emptyList(), plan.toRemove)
        ScanResult(found.size, read, plan.toRemove.size, plan.unchanged).also {
            Log.i(TAG, "scan roots=${roots.size} found=${it.found} read=${it.read} removed=${it.removed} unchanged=${it.unchanged}")
        }
    }

    private suspend fun walk(dir: File, depth: Int, out: MutableList<FoundFile>) {
        if (depth > MAX_DEPTH) return
        coroutineContext.ensureActive()
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) {
                if (!AudioFiles.shouldSkipDir(f, isVolumeRoot)) walk(f, depth + 1, out)
            } else if (!AudioFiles.isHidden(f.name) && AudioFiles.isAudio(f.name)) {
                out += FoundFile(f.absolutePath, f.lastModified(), f.length())
            }
        }
    }

    private fun toEntity(f: FoundFile, previous: TrackEntity?): TrackEntity {
        val file = File(f.path)
        val folder = file.parent.orEmpty()
        val tags = tagReader.read(file)
        // Per field: a tag always wins (a user's edit beats the online value); a missing tag keeps the
        // value an online lookup filled earlier; otherwise the fallback (file name / none).
        val title = pick(tags?.title, previous?.title, previous?.titleSource) { AudioFiles.titleFromFileName(file.name) }
        val artist = pick(tags?.artist, previous?.artist, previous?.artistSource) { null }
        val albumPick = pick(tags?.album, previous?.album, previous?.albumSource) { null }
        val album = albumPick.first
        val albumArtist = tags?.albumArtist
        return TrackEntity(
            id = previous?.id ?: 0,
            path = f.path,
            folder = folder,
            fileName = file.name,
            title = title.first ?: AudioFiles.titleFromFileName(file.name),
            artist = artist.first,
            albumArtist = albumArtist,
            album = album,
            albumKey = AlbumKeys.of(album, albumArtist, folder),
            genre = tags?.genre,
            year = tags?.year,
            trackNumber = tags?.trackNumber,
            discNumber = tags?.discNumber,
            durationMs = tags?.durationMs ?: 0L,
            lastModified = f.lastModified,
            sizeBytes = f.sizeBytes,
            dateAdded = previous?.dateAdded ?: minOf(f.lastModified.takeIf { it > 0 } ?: now(), now()),
            hasEmbeddedArt = tags?.hasEmbeddedArt ?: false,
            titleSource = title.second,
            artistSource = artist.second,
            albumSource = albumPick.second,
            // Carried over, so a rescan never triggers a new lookup.
            lookupState = previous?.lookupState ?: LookupState.NONE,
            lookupAt = previous?.lookupAt,
            lookupSource = previous?.lookupSource,
            lookupRef = previous?.lookupRef
        )
    }

    /** (value, source): the tag, else the previous online value, else the fallback (source FALLBACK/MISSING = 1). */
    private inline fun pick(tag: String?, previous: String?, previousSource: Int?, fallback: () -> String?): Pair<String?, Int> = when {
        tag != null -> tag to TagSource.TAG
        previousSource == TagSource.ONLINE && previous != null -> previous to TagSource.ONLINE
        else -> fallback() to TagSource.FALLBACK
    }

    companion object {
        private const val TAG = "SeionScan"
        private const val BATCH = 64
        private const val MAX_DEPTH = 16
    }
}
