package org.seion.meta

import org.seion.art.CoverMatch

/** What a file name and its folders say about an untagged track. */
data class ParsedName(val artist: String?, val title: String, val album: String?, val junk: Boolean)

/**
 * File-name / folder heuristics: a leading track number is stripped, "Artist - Title" splits, "Artist/Album/NN Title"
 * gives the folders' artist and album, generic folders (Music, Download, Unknown, CD1, a year...) give nothing.
 * Pure, unit-tested.
 */
object FileNameParser {
    private val GENERIC =
        setOf(
            "music", "musica", "música", "musik", "download", "downloads", "media", "whatsapp", "whatsapp audio", "audio",
            "recordings", "recorder", "sounds", "unknown artist", "unknown album", "unknown", "various artists", "various", "va",
            "misc", "telegram", "bluetooth", "ringtones", "notifications", "podcasts", "sdcard", "storage", "emulated", "0",
            "album", "albums", "artist", "artists", "songs", "mp3", "flac", "new folder"
        ).map(CoverMatch::fold).toSet()
    private val GENERIC_PATTERN = Regex("(?i)^(cd|disc|disk|vol|volume)\\s*\\d+$|^(19|20)\\d{2}$|^[0-9a-f]{4}-[0-9a-f]{4}$")
    private val TRACK_NO = Regex("(?i)^\\s*(?:disc\\s*\\d+\\s*)?(?:\\d{1,3}|[a-d]\\d{1,2})(?:\\s*[-._)]\\s*|\\s+)(?=\\S)")
    private val EXTENSION = Regex("\\.[A-Za-z0-9]{2,4}$")
    private val SPLIT = Regex("\\s+[-–—]\\s+")
    private val JUNK =
        Regex(
            "(?i)^(?:track|faixa|pista|piste|titel|title|audio|aud|rec|recording|new\\s*recording|voice|ptt|" +
                "unknown|untitled|sem\\s*t[ií]tulo|sound|song|music|record|memo|whatsapp\\s*audio)[\\s_\\-.]*\\d*$" +
                "|^(?:aud|ptt|vid|img)[-_]\\d{8}[-_]wa\\d{3,5}$|(?:19|20)\\d{2}[-_. ]?\\d{2}[-_. ]?\\d{2}|^\\d+$"
        )

    fun isGenericFolder(name: String): Boolean {
        val f = CoverMatch.fold(name)
        return f.isEmpty() || f in GENERIC || GENERIC_PATTERN.matches(name.trim()) || GENERIC_PATTERN.matches(f)
    }

    /** [path] is the file's path; [artistTag] / [albumTag] the tags that exist (null when missing). */
    fun parse(path: String, artistTag: String? = null, albumTag: String? = null): ParsedName {
        val parts = path.split('/').filter { it.isNotEmpty() }
        val stem = EXTENSION.replace(parts.lastOrNull().orEmpty(), "").replace('_', ' ').trim()
        val folders = parts.dropLast(1)
        val hadNumber = TRACK_NO.containsMatchIn(stem)
        val stripped = TRACK_NO.replaceFirst(stem, "").trim()
        var artist: String? = null
        var title = stripped
        val bits = SPLIT.split(stripped).map { it.trim() }.filter { it.isNotEmpty() }
        if (bits.size >= 2) {
            artist = bits[0]
            title = bits.drop(1).joinToString(" - ")
        }
        val parent = folders.lastOrNull()?.takeUnless(::isGenericFolder)
        val grandparent = folders.getOrNull(folders.size - 2)?.takeUnless(::isGenericFolder)
        var album: String? = null
        if (artist == null && parent != null && grandparent != null) {
            artist = grandparent
            album = parent
        } else if (parent != null && (hadNumber || albumTag == null)) {
            album = parent
        }
        val junk =
            JUNK.containsMatchIn(CoverMatch.fold(stem)) || JUNK.containsMatchIn(CoverMatch.fold(title)) || CoverMatch.fold(title).length < 2
        return ParsedName(
            artist = artistTag?.takeIf { it.isNotBlank() } ?: artist,
            title = title,
            album = albumTag?.takeIf { it.isNotBlank() } ?: album,
            junk = junk
        )
    }
}
