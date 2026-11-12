package org.seion.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.library.LookupState
import org.seion.library.TagSource
import org.seion.library.TrackEntity

class IdentifyPolicyTest {
    private fun track(
        title: String = "03 - Fleetwood Mac - Dreams",
        titleSource: Int = TagSource.FALLBACK,
        artist: String? = null,
        album: String? = null,
        folder: String = "/m/Music",
        lookup: Int = LookupState.NONE
    ) = TrackEntity(
        path = "$folder/$title.mp3", folder = folder, fileName = "$title.mp3", title = title, artist = artist, albumArtist = null,
        album = album, albumKey = "folder:$folder", genre = null, year = null, trackNumber = null, discNumber = null,
        durationMs = 256_540, lastModified = 1, sizeBytes = 1, dateAdded = 1, hasEmbeddedArt = false,
        titleSource = titleSource, artistSource = if (artist == null) TagSource.MISSING else TagSource.TAG,
        albumSource = if (album == null) TagSource.MISSING else TagSource.TAG, lookupState = lookup
    )

    private val match = RecordingCandidate("Fleetwood Mac", "Dreams (2004 Remaster)", listOf("Rumours"), 256_800, "", "mbid-1")

    @Test fun eligibility() {
        assertFalse(IdentifyPolicy.eligible(track(title = "Dreams", titleSource = TagSource.TAG, artist = "Fleetwood Mac")))
        assertFalse(IdentifyPolicy.eligible(track(lookup = LookupState.FOUND)))
        assertFalse(IdentifyPolicy.eligible(track(lookup = LookupState.NOT_FOUND)))
        assertTrue(IdentifyPolicy.eligible(track()))
        assertTrue(IdentifyPolicy.eligible(track(title = "Dreams", titleSource = TagSource.TAG, artist = null)))
    }

    @Test fun onlyMissingFieldsAreFilled() {
        val t = track(artist = "X")
        val out = IdentifyPolicy.apply(t, match, "musicbrainz", 99)
        assertEquals("Dreams", out.title) // the base title
        assertEquals(TagSource.ONLINE, out.titleSource)
        assertEquals("X", out.artist) // the tag stays
        assertEquals(TagSource.TAG, out.artistSource)
        assertNull(out.album) // "Music" is not a release title
        assertNull(out.albumArtist)
        assertEquals(LookupState.FOUND, out.lookupState)
        assertEquals("musicbrainz", out.lookupSource)
        assertEquals("mbid-1", out.lookupRef)
        assertEquals(99L, out.lookupAt)
    }

    @Test fun `the album is written only when the folder names a release`() {
        val inRumours = IdentifyPolicy.apply(track(folder = "/m/Fleetwood Mac/Rumours"), match, "musicbrainz", 1)
        assertEquals("Rumours", inRumours.album)
        assertEquals(TagSource.ONLINE, inRumours.albumSource)
        assertEquals("rumours\u0001folder:/m/Fleetwood Mac/Rumours", inRumours.albumKey)
        val elsewhere = IdentifyPolicy.apply(track(folder = "/m/Best of 70s"), match, "musicbrainz", 1)
        assertNull(elsewhere.album)
    }

    @Test fun `no match records not found and changes nothing else`() {
        val t = track()
        val out = IdentifyPolicy.apply(t, null, "musicbrainz", 5)
        assertEquals(t.copy(lookupState = LookupState.NOT_FOUND, lookupAt = 5, lookupSource = "musicbrainz"), out)
    }

    @Test fun `an edition's decorations are dropped when the folder names the base album`() {
        // The matched release was the anniversary box; the folder is "Nevermind".
        val box =
            RecordingCandidate("Nirvana", "Smells Like Teen Spirit", listOf("Nevermind (30th Anniversary Super Deluxe)"), 301_000, "", "r")
        val out = IdentifyPolicy.apply(track(folder = "/m/Pre/Nevermind"), box, "musicbrainz", 1)
        assertEquals("Nevermind", out.album)
        assertEquals("nevermind\u0001folder:/m/Pre/Nevermind", out.albumKey)
        // Among several agreeing releases, the one the folder names exactly.
        val both = box.copy(albums = listOf("Nevermind (Remastered)", "Nevermind (Deluxe Edition)", "Nevermind"))
        assertEquals("Nevermind", IdentifyPolicy.apply(track(folder = "/m/Nevermind"), both, "musicbrainz", 1).album)
        // A folder that names the edition keeps it.
        val deluxe = IdentifyPolicy.apply(track(folder = "/m/Nevermind (Deluxe Edition)"), both, "musicbrainz", 1)
        assertEquals("Nevermind (Deluxe Edition)", deluxe.album)
        // An album from the user's tags is never changed.
        val tagged = IdentifyPolicy.apply(
            track(album = "Nevermind (30th Anniversary Super Deluxe)", folder = "/m/Nevermind"),
            box,
            "musicbrainz",
            1
        )
        assertEquals("Nevermind (30th Anniversary Super Deluxe)", tagged.album)
        assertEquals(TagSource.TAG, tagged.albumSource)
    }

    /**
     * A folder that names an edition of the matched title keeps its own name, whether or not the recording
     * lists that edition; a plain folder gets the base title; a folder about another album writes nothing.
     */
    @Test fun `the album a folder gives, by folder and the recording's releases`() {
        val plain = listOf("Nevermind")
        val box = listOf("Nevermind (30th Anniversary Super Deluxe)")
        val several = listOf("Nevermind (Remastered)", "Nevermind (Deluxe Edition)", "Nevermind")
        val deluxe = listOf("Nevermind (Deluxe Edition)")
        val cases =
            listOf(
                // The folder names an edition the recording doesn't list: the folder's name.
                Triple("Nevermind (30th Anniversary Super Deluxe)", plain, "Nevermind (30th Anniversary Super Deluxe)"),
                Triple("Nevermind [Deluxe Edition]", plain, "Nevermind [Deluxe Edition]"),
                Triple("Nevermind - Remastered 2011", plain, "Nevermind - Remastered 2011"),
                Triple("Nevermind (30th Anniversary Super Deluxe)", deluxe, "Nevermind (30th Anniversary Super Deluxe)"),
                // The folder names a listed edition: still the folder's own spelling (MusicBrainz lists
                // "Nevermind (deluxe edition)").
                Triple("Nevermind (Deluxe Edition)", several, "Nevermind (Deluxe Edition)"),
                Triple("Nevermind (30th Anniversary Super Deluxe)", box, "Nevermind (30th Anniversary Super Deluxe)"),
                Triple("Nevermind [Deluxe Edition]", listOf("Nevermind", "Nevermind (deluxe edition)"), "Nevermind [Deluxe Edition]"),
                // A plain folder that names a release keeps the release's spelling.
                Triple("rumours", listOf("Rumours"), "Rumours"),
                // A plain folder: the base title, never the matched edition.
                Triple("Nevermind", box, "Nevermind"),
                Triple("Nevermind", several, "Nevermind"),
                Triple("Nevermind", plain, "Nevermind"),
                // A disc, a year or a format is no edition: the base title.
                Triple("Nevermind (Disc 1)", plain, "Nevermind"),
                Triple("Nevermind (1991)", plain, "Nevermind"),
                Triple("Nevermind [FLAC]", plain, "Nevermind"),
                // A folder about another album never leaks its name in, decorated or not.
                Triple("In Utero (Deluxe Edition)", plain, null),
                Triple("Greatest Hits", plain, null),
                Triple("Best of 70s", listOf("Rumours"), null)
            )
        for ((folder, releases, expected) in cases) {
            val match = RecordingCandidate("Nirvana", "Come as You Are", releases, 219_000, "", "r")
            val out = IdentifyPolicy.apply(track(folder = "/m/Albums/$folder"), match, "musicbrainz", 1)
            assertEquals("folder \"$folder\", releases $releases", expected, out.album)
            assertEquals("folder \"$folder\": source", if (expected == null) TagSource.MISSING else TagSource.ONLINE, out.albumSource)
        }
    }
}
