package org.seion.meta

import org.seion.art.CoverMatch
import org.seion.library.AlbumKeys
import org.seion.library.LookupState
import org.seion.library.TagSource
import org.seion.library.TrackEntity

/** Which rows are looked up and what a match may write. Pure, unit-tested. */
object IdentifyPolicy {
    /** A file-name title or no artist, and never looked up before. Fully tagged rows are never touched. */
    fun eligible(t: TrackEntity): Boolean = t.lookupState == LookupState.NONE && (t.titleSource == TagSource.FALLBACK || t.artist == null)

    fun parse(t: TrackEntity): ParsedName = FileNameParser.parse(t.path, t.artist, t.album)

    /**
     * The row with only its missing fields filled from [match]: the title when it is the file name, the artist when
     * there is none, the album only when the tag is missing and the parent folder agrees with one of the recording's
     * releases ([albumName]).
     * An album artist is never written. The lookup columns record the result.
     */
    fun apply(t: TrackEntity, match: RecordingCandidate?, source: String, now: Long): TrackEntity {
        if (match == null) {
            return t.copy(lookupState = LookupState.NOT_FOUND, lookupAt = now, lookupSource = source, lookupRef = null)
        }
        var out = t.copy(lookupState = LookupState.FOUND, lookupAt = now, lookupSource = source, lookupRef = match.id)
        if (t.titleSource == TagSource.FALLBACK) out = out.copy(title = CoverMatch.baseTitle(match.title), titleSource = TagSource.ONLINE)
        if (t.artist == null) out = out.copy(artist = match.artist, artistSource = TagSource.ONLINE)
        val folder = t.folder.substringAfterLast('/')
        val album = if (t.album == null && !FileNameParser.isGenericFolder(folder)) albumName(folder, match.albums) else null
        if (album != null) {
            out = out.copy(album = album, albumSource = TagSource.ONLINE, albumKey = AlbumKeys.of(album, t.albumArtist, t.folder))
        }
        return out
    }

    /**
     * The album to write for a folder that agrees with some of [releases] (null when none does: a folder about another
     * album never gives its name). A folder that is an edition of the matched title ([isEditionOf]) keeps its own name,
     * whether the recording lists that edition or not ("Nevermind (30th Anniversary Super Deluxe)"); else
     * the release the folder names, in the release's spelling; else the title without its edition decorations
     * ("Nevermind" for a "Nevermind" folder matched to the anniversary box, [CoverMatch.baseTitle]).
     */
    internal fun albumName(folder: String, releases: List<String>): String? {
        val release =
            releases
                .filter { CoverMatch.albumSim(folder, it) >= MetadataMatch.ALBUM_MIN }
                .maxByOrNull { CoverMatch.sim(folder, it) } ?: return null
        val base = CoverMatch.baseTitle(release)
        return when {
            isEditionOf(folder, base) -> folder.trim()
            CoverMatch.sim(folder, release) >= MetadataMatch.ALBUM_MIN -> release
            CoverMatch.sim(folder, base) >= MetadataMatch.ALBUM_MIN -> base
            else -> release
        }
    }

    /**
     * [folder] is [base] plus an edition decoration, as [CoverMatch.baseTitle] strips them: a bracketed segment or an
     * edition dash suffix ("Nevermind [Deluxe Edition]", "Nevermind - Remastered 2011"). A disc, volume, year or format
     * ("Nevermind (Disc 1)", "(1991)", "[FLAC]": [FileNameParser.isGenericFolder]) is no edition.
     */
    internal fun isEditionOf(folder: String, base: String): Boolean {
        val folderBase = CoverMatch.baseTitle(folder)
        if (CoverMatch.sim(folderBase, base) < MetadataMatch.ALBUM_MIN) return false
        val decoration = CoverMatch.fold(folder).replaceFirst(CoverMatch.fold(folderBase), "").trim()
        return !FileNameParser.isGenericFolder(decoration)
    }
}
