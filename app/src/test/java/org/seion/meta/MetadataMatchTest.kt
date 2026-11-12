package org.seion.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Metadata matching against fixed reference vectors. */
class MetadataMatchTest {
    private val queen = ParsedName("Queen", "Bohemian Rhapsody", null, junk = false)
    private val dur = 354_550L

    private fun c(
        artist: String = "Queen",
        title: String = "Bohemian Rhapsody",
        ms: Long? = 356_000,
        extra: String = "",
        albums: List<String> = emptyList()
    ) = RecordingCandidate(artist, title, albums, ms, extra, "id")

    @Test fun `the studio recording passes`() {
        assertNotNull(MetadataMatch.passing(queen, dur, c()))
    }

    @Test fun `version markers reject live, karaoke and symphonic versions`() {
        assertNull(MetadataMatch.passing(queen, dur, c(title = "Bohemian Rhapsody (Live)")))
        assertNull(MetadataMatch.passing(queen, dur, c(extra = "karaoke Compilation")))
        assertNull(MetadataMatch.passing(queen, dur, c(title = "Bohemian Rhapsody (Symphonic Version)")))
        assertNull(MetadataMatch.passing(queen, dur, c(extra = "live, 1982-06-05: Milton Keynes Bowl")))
    }

    @Test fun `a cover with the same title and length but another artist is rejected`() {
        assertNull(MetadataMatch.passing(queen, dur, c(artist = "The Piano Guys")))
        assertNull(MetadataMatch.passing(queen, dur, c(artist = "Queen Tribute Band")))
    }

    @Test fun `a duration gap over three seconds is rejected`() {
        assertNull(MetadataMatch.passing(queen, dur, c(ms = dur + 3_400)))
        assertNotNull(MetadataMatch.passing(queen, dur, c(ms = dur - 2_900)))
    }

    @Test fun `cross-script is rejected for metadata`() {
        val p = ParsedName("방탄소년단", "봄날", null, junk = false)
        assertNull(MetadataMatch.passing(p, 273_590, c(artist = "BTS", title = "Spring Day", ms = 274_000)))
    }

    @Test fun `no artist, no match`() {
        assertNull(
            MetadataMatch.passing(ParsedName(null, "Creep", null, false), 238_410, c(artist = "Radiohead", title = "Creep", ms = 238_000))
        )
    }

    @Test fun `the most signals win, then the smallest gap`() {
        val p = ParsedName("Queen", "Bohemian Rhapsody", "A Night at the Opera", false)
        val far = c(ms = dur + 2_000).copy(id = "far")
        val withAlbum = c(ms = dur + 2_500, albums = listOf("A Night at the Opera")).copy(id = "album")
        val near = c(ms = dur + 100).copy(id = "near")
        assertEquals("album", MetadataMatch.best(p, dur, listOf(far, near, withAlbum))!!.candidate.id)
        assertEquals("near", MetadataMatch.best(queen, dur, listOf(far, near))!!.candidate.id)
    }
}
