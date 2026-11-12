package org.seion.meta

import org.seion.art.CoverMatch

/** One recording an online source returned. [durationMs] null = unknown (never accepted). */
data class RecordingCandidate(
    val artist: String,
    val title: String,
    val albums: List<String>,
    val durationMs: Long?,
    /** MB disambiguation + release-group secondary types. */
    val extra: String,
    val id: String
)

/**
 * Whether a MusicBrainz recording is the untagged track (title, artist and duration must all agree). A wrong artist is
 * worse than unknown: the thresholds are strict.
 */
object MetadataMatch {
    const val TITLE_MIN = 0.90
    const val ARTIST_MIN = 0.80
    const val MAX_DURATION_GAP_MS = 3_000L
    const val ALBUM_MIN = 0.85

    /** Live, karaoke, remixes, covers...: versions that are not the original recording. */
    private val VERSION =
        Regex(
            "(?i)\\b(live|ao\\s*vivo|en\\s*vivo|en\\s*directo|karaoke|instrumental|backing|tribute|cover|covered|" +
                "originally\\s*(performed|by)|in\\s*the\\s*style\\s*of|made\\s*famous|as\\s*made|" +
                "remix|re-mix|mix|edit|acoustic|ac[uú]stico|" +
                "demo|8-?bit|lullaby|piano\\s*version|orchestral|medley|sped\\s*up|slowed|nightcore|reprise|session|orgel|music\\s*box|" +
                "symphonic|version|versão|versión|ver\\.)\\b|オルゴール"
        )
    private val TRIBUTE_ARTIST =
        Regex(
            "(?i)karaoke|tribute|cover|backing|hits|workshop|piano man|lullaby|orchestra|ensemble|band of|the\\s+\\w+\\s+players|" +
                "singers?\\b|remixers|all\\s*stars|vitamin string|made famous|originally by"
        )

    fun titleSim(parsed: String, candidate: String): Double =
        maxOf(CoverMatch.sim(parsed, candidate), CoverMatch.sim(CoverMatch.baseTitle(parsed), CoverMatch.baseTitle(candidate)))

    data class Passing(val candidate: RecordingCandidate, val signals: Int, val gapMs: Long, val titleSim: Double)

    /** The candidate when it passes every rule, with its signal count (title + duration + artist [+ album]). */
    fun passing(p: ParsedName, durationMs: Long, c: RecordingCandidate): Passing? {
        val artist = p.artist ?: return null // no artist derivable: never identified by text
        val d = c.durationMs ?: return null
        if (durationMs <= 0) return null
        if (VERSION.containsMatchIn(c.title + " " + c.extra) && !VERSION.containsMatchIn(p.title)) return null
        if (TRIBUTE_ARTIST.containsMatchIn(c.artist) && !TRIBUTE_ARTIST.containsMatchIn(artist)) return null
        if (CoverMatch.crossScript(artist, c.artist) || CoverMatch.crossScript(p.title, c.title)) return null
        val ts = titleSim(p.title, c.title)
        if (ts < TITLE_MIN) return null
        val gap = kotlin.math.abs(d - durationMs)
        if (gap > MAX_DURATION_GAP_MS) return null
        if (CoverMatch.artistSim(artist, c.artist) < ARTIST_MIN) return null
        var signals = 3
        if (albumAgrees(p.album, c)) signals++
        return Passing(c, signals, gap, ts)
    }

    fun albumAgrees(album: String?, c: RecordingCandidate): Boolean =
        album != null && c.albums.any { CoverMatch.albumSim(album, it) >= ALBUM_MIN }

    /** The best passing candidate: most signals, then the smallest duration gap, then the closest title. */
    fun best(p: ParsedName, durationMs: Long, candidates: List<RecordingCandidate>): Passing? = candidates
        .mapNotNull { passing(p, durationMs, it) }
        .sortedWith(compareByDescending<Passing> { it.signals }.thenBy { it.gapMs }.thenByDescending { it.titleSim })
        .firstOrNull()
}
