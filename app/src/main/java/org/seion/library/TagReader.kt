package org.seion.library

import android.media.MediaMetadataRetriever
import java.io.File

data class Tags(
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val genre: String?,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val hasEmbeddedArt: Boolean
)

/** Reads tags from one file; behind an interface so the scanner is testable and the reader replaceable. */
fun interface TagReader {
    fun read(file: File): Tags?
}

/**
 * The platform's MediaMetadataRetriever: zero extra dependencies, and it reads every supported format's tags
 * (ID3v2 for MP3, MP4 atoms for AAC/ALAC, Vorbis comments for FLAC/Ogg/Opus) plus embedded pictures.
 * It is slower than a pure-Kotlin parser (one native extractor per file), which only matters on the first scan
 * of a big library: the incremental scan re-reads changed files only.
 */
class RetrieverTagReader : TagReader {
    override fun read(file: File): Tags? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            fun key(k: Int) = TagParsing.clean(mmr.extractMetadata(k))
            Tags(
                title = key(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = key(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                genre = TagParsing.genre(key(MediaMetadataRetriever.METADATA_KEY_GENRE)),
                year =
                TagParsing.year(key(MediaMetadataRetriever.METADATA_KEY_YEAR))
                    ?: TagParsing.year(key(MediaMetadataRetriever.METADATA_KEY_DATE)),
                trackNumber = TagParsing.number(key(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNumber = TagParsing.number(key(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                hasEmbeddedArt = mmr.embeddedPicture != null
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }
}

/** Tag value clean-up (pure, unit-tested). */
object TagParsing {
    fun clean(value: String?): String? = value?.trim()?.trim('\u0000')?.takeIf { it.isNotEmpty() }

    /** "3/12" -> 3, "07" -> 7. */
    fun number(value: String?): Int? = value
        ?.substringBefore('/')
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    /** "2021", "2021-05-01", "20210501T000000.000Z" (MMR's date) -> 2021. */
    fun year(value: String?): Int? {
        val digits = value?.trim()?.take(4) ?: return null
        // 1904 is the QuickTime/MP4 epoch that files without a year report.
        return digits.toIntOrNull()?.takeIf { it in 1000..2999 && it != 1904 }
    }

    /** Drops ID3v1 numeric references like "(17)" that the platform passes through. */
    fun genre(value: String?): String? = value?.takeUnless { Regex("""\(?\d+\)?""").matches(it) }
}
