package org.seion.playlists

import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * M3U / M3U8 playlist files (pure, unit-tested): read on import, written on export. Seion never writes a playlist file
 * by itself; both directions go through a file the user picks.
 */
object M3u {
    /** One location line, with the title and length of the `#EXTINF` line before it (if any). */
    data class Entry(val location: String, val title: String?, val durationSec: Int?)

    /** What an exported line needs. */
    data class Item(val path: String, val title: String, val artist: String?, val durationMs: Long)

    private val UTF8: Charset = Charsets.UTF_8
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    /** UTF-8 when the bytes are valid UTF-8 (M3U8, and most M3U today), else Windows-1252 (older Windows tools); no BOM. */
    fun decode(bytes: ByteArray): String {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        val body = ByteBuffer.wrap(bytes, start, bytes.size - start)
        return try {
            UTF8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(body)
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, start, bytes.size - start, WINDOWS_1252)
        }
    }

    /** The entries of an M3U text: blank lines and comments are skipped; `#EXTINF:<seconds>,<title>` labels the next line. */
    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        var title: String? = null
        var duration: Int? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                    val info = line.substring(8)
                    val comma = info.indexOf(',')
                    duration =
                        (if (comma >= 0) info.substring(0, comma) else info).trim().takeWhile { it == '-' || it.isDigit() }.toIntOrNull()
                    title = if (comma >= 0) info.substring(comma + 1).trim().ifEmpty { null } else null
                }
                continue
            }
            out += Entry(line, title, duration?.takeIf { it >= 0 })
            title = null
            duration = null
        }
        return out
    }

    /**
     * The absolute path a location points to, or null when it can't be known here: a URL other than `file:`, a path
     * from another computer (`C:\…`), or a relative path without the playlist file's folder ([baseDir]).
     */
    fun resolve(location: String, baseDir: String?): String? {
        var loc = location
        if (loc.contains("://")) {
            if (!loc.startsWith("file:", ignoreCase = true)) return null
            loc = runCatching { URI(loc).path }.getOrNull() ?: return null
        } else if (loc.startsWith("file:", ignoreCase = true)) {
            loc = runCatching { URI(loc).path }.getOrNull() ?: return null
        }
        loc = loc.replace('\\', '/')
        if (loc.length >= 2 && loc[1] == ':' && loc[0].isLetter()) return null
        val joined =
            when {
                loc.startsWith("/") -> loc
                baseDir != null -> baseDir.trimEnd('/') + "/" + loc
                else -> return null
            }
        return normalize(joined)
    }

    /** `/a/./b/../c//d` → `/a/c/d`; null when `..` climbs above the root. */
    fun normalize(path: String): String? {
        val parts = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += seg
            }
        }
        return "/" + parts.joinToString("/")
    }

    /**
     * A location that doesn't resolve on this device (a playlist made on a computer, a different folder layout) matched
     * against the library by its file name and as many parent folders as agree (ignoring case). Returns the single best
     * match, or null when nothing matches or the best matches tie.
     */
    fun matchBySuffix(location: String, byFileName: Map<String, List<String>>): String? {
        val segs = location.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        val name = segs.lastOrNull()?.lowercase() ?: return null
        val candidates = byFileName[name].orEmpty()
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0]
        val scored = candidates.map { it to agreeingSegments(segs, it.split('/').filter(String::isNotEmpty)) }
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }
        return top.singleOrNull()?.first
    }

    private fun agreeingSegments(a: List<String>, b: List<String>): Int {
        var n = 0
        while (n < a.size && n < b.size && a[a.size - 1 - n].equals(b[b.size - 1 - n], ignoreCase = true)) n++
        return n
    }

    /** The index for [matchBySuffix]: library paths by lower-cased file name. */
    fun indexByFileName(paths: Collection<String>): Map<String, List<String>> = paths.groupBy { it.substringAfterLast('/').lowercase() }

    /**
     * An M3U8 text (UTF-8): `#EXTM3U`, then `#EXTINF:<seconds>,<artist> - <title>` and the location of each item. A song
     * on the same storage volume as the playlist's folder ([baseDir]) is written relative to it, so the file keeps
     * working when the folder is copied elsewhere; anything else (or an unknown folder) gets its absolute path.
     */
    fun write(items: List<Item>, baseDir: String?, volumeOf: (String) -> String?): String {
        val sb = StringBuilder("#EXTM3U\n")
        val baseVolume = baseDir?.let(volumeOf)
        for (it in items) {
            val secs = if (it.durationMs > 0) (it.durationMs + 500) / 1000 else -1
            val label = (if (it.artist.isNullOrBlank()) it.title else "${it.artist} - ${it.title}").replace('\n', ' ').replace('\r', ' ')
            sb.append("#EXTINF:").append(secs).append(',').append(label).append('\n')
            val relative = if (baseDir != null &&
                baseVolume != null &&
                volumeOf(it.path) == baseVolume
            ) {
                relativize(baseDir, it.path)
            } else {
                null
            }
            sb.append(relative ?: it.path).append('\n')
        }
        return sb.toString()
    }

    /** [path] relative to the folder [baseDir] (`../Album/01.flac`), with `/` separators. */
    fun relativize(baseDir: String, path: String): String? = runCatching {
        File(baseDir).toPath().normalize().relativize(File(path).toPath().normalize()).toString().replace('\\', '/')
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}
