package org.seion.art

import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Covers the user chose for an album (kept in Seion's own storage, never written into the music
 * folders): `files/covers/<key>.jpg`, one per folder + album ([key]). They win over embedded art, folder images and
 * online covers everywhere, because [AlbumArtResolver.resolveLocal] asks here first, and they survive rescans and
 * "Clear online cover cache" (the files directory, not the cache). An album split over several folders gets one file per
 * folder (the album page passes all of them).
 */
class CoverOverrides(private val dir: File) {
    /** The keys that have a file; read once from the directory, then kept in step with [set] and [remove]. */
    private val keys: MutableSet<String> by lazy {
        ConcurrentHashMap.newKeySet<String>().apply {
            dir.list().orEmpty().filter { it.endsWith(EXT) }.mapTo(this) { it.removeSuffix(EXT) }
        }
    }

    /** The chosen cover of [folder] + [album], or null. */
    fun file(folder: String?, album: String?): File? {
        if (folder.isNullOrEmpty()) return null
        val k = key(folder, album)
        return if (k in keys) File(dir, k + EXT) else null
    }

    fun has(folders: Collection<String>, album: String?): Boolean = folders.any { file(it, album) != null }

    /** Stores [jpeg] as the cover of [album] in each of [folders]; returns the keys written. */
    fun set(folders: Collection<String>, album: String?, jpeg: ByteArray): Set<String> {
        dir.mkdirs()
        val written = folders.filter { it.isNotEmpty() }.map { key(it, album) }.toSet()
        for (k in written) {
            val tmp = File(dir, "$k.tmp")
            tmp.writeBytes(jpeg)
            if (!tmp.renameTo(File(dir, k + EXT))) throw IOException("cannot store the chosen cover")
            keys += k
        }
        Log.i(TAG, "chosen cover stored for ${written.size} folder(s)")
        return written
    }

    /** "Use original cover": the chosen cover of [album] goes from each of [folders]; returns the keys removed. */
    fun remove(folders: Collection<String>, album: String?): Set<String> {
        val removed = folders.filter {
            it.isNotEmpty()
        }.map { key(it, album) }.filter { k -> File(dir, k + EXT).delete() || k in keys }.toSet()
        keys -= removed
        Log.i(TAG, "chosen cover removed for ${removed.size} folder(s)")
        return removed
    }

    companion object {
        private const val TAG = "SeionArt"
        private const val EXT = ".jpg"

        /**
         * The album's identity as the art cache sees it ("art per album": one cover per folder + album):
         * the folder path without a trailing slash and the lower-cased, trimmed album tag (none for an
         * untagged folder album), hashed like the online cache's keys (no names or paths in file names). Every
         * [ArtRef] carries both (rows, the album page, queue items, the browse tree), so every screen finds the same
         * file; the library's album key can't be used, since a queue item's ref doesn't carry the album artist.
         */
        fun key(folder: String, album: String?): String {
            val raw = folder.trimEnd('/') + "\u0001" + album?.trim()?.lowercase().orEmpty()
            return MessageDigest
                .getInstance("SHA-256")
                .digest(raw.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(40)
        }

        /** The key [ref] is looked up under (null when it has no folder). */
        fun keyOf(ref: ArtRef): String? = ref.folder?.takeIf { it.isNotEmpty() }?.let { key(it, ref.album) }
    }
}
