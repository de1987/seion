package org.seion.ui

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The browse state on disk (`files/nav.json`), so a real close (swiped away from Recents, Back from the library home,
 * a restart, an update) comes back to the same place. A file of its own, like the queue's `queue.json`, rather than
 * the settings DataStore: it changes on every navigation and scroll, and every write to the settings would re-emit
 * them to everything that watches the settings.
 *
 * [save] keeps the latest value and writes it after [debounceMs] on [io] (via a temporary file and a rename, so a
 * kill mid-write keeps the old file); [flush] writes it at once. [load] blocks: call it off the main thread.
 */
class NavStore(
    private val file: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 300
) {
    private val pending = AtomicReference<SavedNav?>(null)
    private val writing = Mutex()
    private var job: Job? = null

    fun load(): SavedNav? = runCatching { if (file.isFile) decode(file.readText()) else null }.getOrNull()

    fun save(nav: SavedNav) {
        pending.set(nav)
        job?.cancel()
        job =
            scope.launch(io) {
                delay(debounceMs)
                write()
            }
    }

    fun flush() {
        job?.cancel()
        job = scope.launch(io) { write() }
    }

    private suspend fun write() = writing.withLock {
        val nav = pending.getAndSet(null) ?: return@withLock
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encode(nav))
            check(tmp.renameTo(file)) { "rename to ${file.name} failed" }
        }.onFailure { Log.w("SeionNav", "saving the screens failed", it) }
    }

    /** The file's format: [version] first, so a future layout can't be misread as this one. */
    @Serializable
    private data class OnDisk(val version: Int, val nav: SavedNav)

    companion object {
        /** Bump when the stored layout changes incompatibly: another version starts fresh (never a crash). */
        const val VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(nav: SavedNav): String = json.encodeToString(OnDisk.serializer(), OnDisk(VERSION, nav))

        /** Null for anything unreadable, another version, or a stack that doesn't start at the library home. */
        fun decode(raw: String?): SavedNav? = raw?.let {
            runCatching { json.decodeFromString(OnDisk.serializer(), it) }
                .getOrNull()
                ?.takeIf { d -> d.version == VERSION }
                ?.nav
                ?.takeIf { nav -> nav.stack.firstOrNull()?.screen == Screen.Home }
        }
    }
}

/** What a restored screen is checked against: file access, the music folders, the files and the library index. */
interface NavRestoreEnv {
    /** All-files access (without it the library can't be read: start at the library home). */
    fun hasAccess(): Boolean

    /** The music folders in effect (the persisted setting, or the defaults). */
    suspend fun musicFolders(): List<String>

    /** The storage volumes' root directories (a folder reached through the breadcrumbs may sit above a music folder). */
    fun volumes(): List<String>

    fun isDirectory(path: String): Boolean

    /** The library index has no rows at all (first scan, or an index being rebuilt). */
    suspend fun indexEmpty(): Boolean

    suspend fun hasAlbum(key: String): Boolean

    suspend fun hasArtist(name: String?): Boolean

    suspend fun hasGenre(name: String?): Boolean

    /** The playlist still exists (it lives in its own database, never rebuilt: checked at once). */
    suspend fun hasPlaylist(id: Long): Boolean = true

    /** Suspends until a library scan has started and finished. */
    suspend fun awaitScanDone()
}

/** One restored screen: still there, gone, or not known yet (the index is still being built). */
enum class EntryCheck { VALID, INVALID, UNKNOWN }

/** A restored state, and the entries kept without being checked ([EntryCheck.UNKNOWN]: checked after the scan). */
data class RestoredNav(val nav: SavedNav, val provisional: Set<Long> = emptySet())

/**
 * The cold-start rules, so Seion continues where you were: transient screens are dropped, then the
 * stack is kept up to its first screen that is gone (a deleted or renamed folder, a folder outside the music folders,
 * an album, artist or genre no longer in the library), so Back still walks the valid parents.
 */
object NavRestore {
    /**
     * Screens never restored after a real close: Search (the screens opened from its results stay, so you land on the
     * album you were on and Back goes to the screen under Search), the folder picker (an "add folder" dialog flow) and
     * the licences (nobody comes back to read them).
     */
    fun isTransient(screen: Screen): Boolean = screen == Screen.Search || screen is Screen.FolderPicker || screen == Screen.Licenses

    /** Drops the transient screens and renumbers the depths; consecutive duplicates left by the drop collapse. */
    fun withoutTransient(nav: SavedNav): SavedNav {
        val kept = mutableListOf<NavEntry>()
        for (e in nav.stack) {
            if (isTransient(e.screen) || kept.lastOrNull()?.screen == e.screen) continue
            kept += e.copy(depth = kept.size)
        }
        return nav.copy(stack = kept, scroll = nav.scroll.filterKeys { id -> kept.any { it.id == id } })
    }

    /** Keeps the entries before the first [EntryCheck.INVALID] one; the home entry is always kept. */
    suspend fun trim(nav: SavedNav, check: suspend (NavEntry) -> EntryCheck): RestoredNav {
        val kept = mutableListOf<NavEntry>()
        val provisional = mutableSetOf<Long>()
        for ((i, e) in nav.stack.withIndex()) {
            val result = if (i == 0) EntryCheck.VALID else check(e)
            if (result == EntryCheck.INVALID) break
            if (result == EntryCheck.UNKNOWN) provisional += e.id
            kept += e
        }
        val nextId = maxOf(nav.nextId, (kept.maxOfOrNull { it.id } ?: 0) + 1)
        return RestoredNav(
            nav.copy(stack = kept, nextId = nextId, scroll = nav.scroll.filterKeys { id -> kept.any { it.id == id } }),
            provisional
        )
    }

    /** True when [path] is [dir] or inside it. */
    fun inside(path: String, dir: String): Boolean {
        val d = dir.trimEnd('/')
        return path == d || path.startsWith("$d/")
    }

    /**
     * A folder screen is still valid when its music folder still is one, the folder exists, and it is inside that music
     * folder, or one of its parents on the same volume (the breadcrumbs open those).
     */
    fun folderValid(root: String, path: String, roots: List<String>, volumes: List<String>, isDirectory: (String) -> Boolean): Boolean {
        if (path.split('/').any { it == ".." || it == "." }) return false
        if (roots.none { it.trimEnd('/') == root.trimEnd('/') }) return false
        val placed = inside(path, root) || (inside(root, path) && volumes.any { inside(path, it) })
        return placed && isDirectory(path)
    }

    /** The whole cold-start restore; null starts fresh at the library home. */
    suspend fun restore(saved: SavedNav, env: NavRestoreEnv): RestoredNav? {
        if (!env.hasAccess()) return null
        val nav = withoutTransient(saved)
        if (nav.stack.firstOrNull()?.screen != Screen.Home) return null
        val roots = env.musicFolders()
        val volumes = env.volumes()
        val indexEmpty = env.indexEmpty()
        return trim(nav) { e ->
            when (val s = e.screen) {
                is Screen.Folder -> if (folderValid(
                        s.root,
                        s.path,
                        roots,
                        volumes,
                        env::isDirectory
                    )
                ) {
                    EntryCheck.VALID
                } else {
                    EntryCheck.INVALID
                }
                is Screen.Playlist -> if (env.hasPlaylist(s.id)) EntryCheck.VALID else EntryCheck.INVALID
                is Screen.Album, is Screen.Artist, is Screen.Genre ->
                    if (indexEmpty) {
                        EntryCheck.UNKNOWN
                    } else if (inIndex(s, env)) {
                        EntryCheck.VALID
                    } else {
                        EntryCheck.INVALID
                    }
                else -> EntryCheck.VALID
            }
        }
    }

    /** The ids of [nav]'s album, artist and genre screens (the ones a scan can remove). */
    fun indexEntries(nav: SavedNav): Set<Long> =
        nav.stack.filter { it.screen is Screen.Album || it.screen is Screen.Artist || it.screen is Screen.Genre }.map { it.id }.toSet()

    /** After the scan: the [ids] are checked against the index now (an empty index: they're gone). */
    suspend fun recheck(nav: SavedNav, ids: Set<Long>, env: NavRestoreEnv): SavedNav =
        trim(nav) { e -> if (e.id in ids && !inIndex(e.screen, env)) EntryCheck.INVALID else EntryCheck.VALID }.nav

    private suspend fun inIndex(s: Screen, env: NavRestoreEnv): Boolean = when (s) {
        is Screen.Album -> env.hasAlbum(s.key)
        is Screen.Artist -> env.hasArtist(s.name)
        is Screen.Genre -> env.hasGenre(s.name)
        else -> true
    }
}
