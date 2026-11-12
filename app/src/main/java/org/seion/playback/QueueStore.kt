package org.seion.playback

import android.util.Log
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The last queue (paths), the current index and position: restored when the service starts again. */
@Serializable
data class SavedQueue(val paths: List<String>, val index: Int, val positionMs: Long)

/**
 * The current track and position, saved on their own every few seconds while playing (a few dozen bytes instead of
 * every path: a 5,000-song queue is about 350 KB). [path] ties it to the saved paths.
 */
@Serializable
data class SavedPosition(val path: String, val index: Int, val positionMs: Long)

/** How the two files make one saved queue (unit-tested in QueueSplitTest). */
object QueueSplit {
    /** The position part of [queue]: null without a current track. */
    fun position(queue: SavedQueue): SavedPosition? =
        queue.paths.getOrNull(queue.index)?.let { SavedPosition(it, queue.index, queue.positionMs) }

    /**
     * [queue] with [position]'s track and position. The paths can be older than the position (a queue change, then a
     * kill before its full save): the track is then looked up in the saved paths, the occurrence nearest the position's
     * index. Without a position (no position file) or when its track isn't in the saved paths (a new list never
     * written): [queue] as it is.
     */
    fun merge(queue: SavedQueue, position: SavedPosition?): SavedQueue {
        if (position == null) return queue
        val index = indexOf(queue.paths, position.path, position.index) ?: return queue
        return queue.copy(index = index, positionMs = position.positionMs.coerceAtLeast(0))
    }

    /** The index of [path] in [paths] nearest [near] (the lower one on a tie); null when it isn't there. */
    private fun indexOf(paths: List<String>, path: String, near: Int): Int? {
        if (paths.getOrNull(near) == path) return near
        var best: Int? = null
        paths.forEachIndexed { i, p ->
            if (p == path && (best == null || kotlin.math.abs(i - near) < kotlin.math.abs(best!! - near))) best = i
        }
        return best
    }
}

/**
 * `queue.json` holds the paths, written when the queue changes
 * and on pause, stop and close; `queue-position.json` next to it holds the current track and position, written every
 * few seconds while playing. A full save writes both, so the position file is never older than the paths.
 */
class QueueStore(private val file: File, private val positionFile: File = File(file.parentFile, "queue-position.json")) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): SavedQueue? {
        val queue = read(file, SavedQueue.serializer())?.takeIf { it.paths.isNotEmpty() } ?: return null
        return QueueSplit.merge(queue, read(positionFile, SavedPosition.serializer()))
    }

    /** The whole queue (paths, then the position); false when it failed (logged). */
    @Synchronized
    fun save(queue: SavedQueue): Boolean {
        // A failed queue write leaves the position too: a position for paths that never landed would be worthless.
        if (!write(file, json.encodeToString(SavedQueue.serializer(), queue))) return false
        QueueSplit.position(queue)?.let(::savePosition)
        return true
    }

    /** Only the current track and position (the paths stay as they are); false when it failed (logged). */
    @Synchronized
    fun savePosition(position: SavedPosition): Boolean = write(positionFile, json.encodeToString(SavedPosition.serializer(), position))

    private fun <T> read(f: File, serializer: KSerializer<T>): T? = runCatching {
        json.decodeFromString(serializer, f.readText())
    }.getOrNull()

    /** Writes via a temporary file and a rename (a kill mid-write keeps the old file). */
    private fun write(f: File, text: String): Boolean = runCatching {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        check(tmp.renameTo(f)) { "rename to ${f.name} failed" }
    }.onFailure { Log.w("SeionPlayback", "saving ${f.name} failed", it) }.isSuccess
}

/** What to restore from a saved queue, given which files exist now. */
data class RestorePlan(val paths: List<String>, val index: Int, val positionMs: Long)

/**
 * Restore rule (unit-tested): missing files are dropped and the index is re-mapped so the same track stays current;
 * if that track is gone, the next existing one starts from 0 (else the previous one); nothing readable (e.g. all-files
 * access revoked) restores nothing and keeps the saved queue for later.
 */
object QueueRestore {
    fun plan(saved: SavedQueue, exists: (String) -> Boolean): RestorePlan? {
        val keep = saved.paths.map(exists)
        val existing = saved.paths.filterIndexed { i, _ -> keep[i] }
        if (existing.isEmpty()) return null
        val current = saved.index.coerceIn(0, saved.paths.lastIndex)
        if (keep[current]) return RestorePlan(existing, keep.take(current).count { it }, saved.positionMs)
        val before = keep.take(current).count { it }
        return RestorePlan(existing, before.coerceAtMost(existing.lastIndex), 0)
    }
}

/** The media-button resumption (a key after the session was released: headset, Bluetooth). */
object QueueResume {
    /**
     * Whether a media key with no session may start the service in the foreground: only with file access and a saved
     * queue that still has a readable file. Otherwise Media3's resumption would fail, the player would never reach the
     * foreground and Android would end the app for a foreground service that never started. Cheapest checks first; the
     * file walk stops at the first existing file.
     */
    fun mayStart(hasAccess: () -> Boolean, load: () -> SavedQueue?, exists: (String) -> Boolean): Boolean {
        if (!hasAccess()) return false
        val saved = load() ?: return false
        return saved.paths.any(exists)
    }
}
