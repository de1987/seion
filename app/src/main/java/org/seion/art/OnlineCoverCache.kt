package org.seion.art

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** What the disk cache knows about one artist + album. */
sealed interface CachedCover {
    data class Hit(val file: File) : CachedCover

    data object Miss : CachedCover

    data object Unknown : CachedCover
}

/**
 * Disk cache for online covers: `<key>.img` for a found cover (kept until cleared) with `<key>.src`, the name of the
 * chain's source that supplied it (for the details sheet), and `<key>.miss` for "the source has none"
 * (re-asked after [missTtlMs]). Keys hash the lower-cased names, so no names appear on disk.
 */
class OnlineCoverCache(
    private val dir: File,
    private val missTtlMs: Long = MISS_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun get(artist: String, album: String): CachedCover {
        val k = key(artist, album)
        val img = File(dir, "$k.img")
        if (img.isFile && img.length() > 0) return CachedCover.Hit(img)
        val miss = File(dir, "$k.miss")
        // A miss counts only when the current chain wrote it: an empty file (an older, single-source chain) reads as
        // Unknown, so an album that chain failed to find is tried once more with the current sources.
        if (miss.isFile && clock() - miss.lastModified() < missTtlMs && readVersion(miss) == CHAIN_VERSION) return CachedCover.Miss
        return CachedCover.Unknown
    }

    fun putHit(artist: String, album: String, bytes: ByteArray, source: String? = null): File {
        dir.mkdirs()
        val k = key(artist, album)
        val tmp = File(dir, "$k.tmp")
        tmp.writeBytes(bytes)
        val img = File(dir, "$k.img")
        if (!tmp.renameTo(img)) throw IOException("cannot store cover")
        File(dir, "$k.miss").delete()
        val src = File(dir, "$k.src")
        if (source != null) runCatching { src.writeText(source) } else src.delete()
        return img
    }

    /** The name of the source that supplied the cached cover ([NamedSource.name]), or null when not recorded. */
    fun source(artist: String, album: String): String? =
        File(dir, "${key(artist, album)}.src").takeIf { it.isFile }?.let { f -> runCatching { f.readText().trim() }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

    fun putMiss(artist: String, album: String) {
        dir.mkdirs()
        val miss = File(dir, "${key(artist, album)}.miss")
        miss.writeText(CHAIN_VERSION)
        miss.setLastModified(clock())
    }

    /** What Settings › Clear online cover cache shows under its title: downloaded covers, all entries, bytes on disk. */
    data class Stats(val covers: Int, val entries: Int, val bytes: Long)

    /** Lists the cache directory (call off the main thread). */
    fun stats(): Stats {
        // A `.src` note belongs to its cover: it is no entry of its own.
        val files = dir.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".src") }
        return Stats(files.count { it.name.endsWith(".img") && it.length() > 0 }, files.size, files.sumOf { it.length() })
    }

    /** "Clear online cover cache": hits and misses. Returns the number of entries removed. */
    fun clear(): Int = dir.listFiles().orEmpty().count { it.isFile && it.delete() && !it.name.endsWith(".src") }

    /**
     * "Find missing covers again": forgets the misses written before [writtenBefore] (the run's start, so a retried run
     * keeps the misses it wrote itself); found covers stay. Returns the number of misses removed.
     */
    fun clearMisses(writtenBefore: Long = Long.MAX_VALUE): Int = dir
        .listFiles()
        .orEmpty()
        .count { it.isFile && it.name.endsWith(".miss") && it.lastModified() < writtenBefore && it.delete() }

    private fun readVersion(f: File): String = runCatching { f.readText().trim() }.getOrDefault("")

    companion object {
        /** Written into every miss; bump when the source chain changes so old misses are retried once. */
        const val CHAIN_VERSION = "3"
        const val MISS_TTL_MS = 30L * 24 * 60 * 60 * 1000

        fun key(artist: String, album: String): String {
            val raw = artist.trim().lowercase() + "\u0001" + album.trim().lowercase()
            return MessageDigest
                .getInstance("SHA-256")
                .digest(raw.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(40)
        }
    }
}
