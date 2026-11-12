package org.seion.regressions

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.art.ArtRef
import org.seion.library.TagParsing
import org.seion.playback.QueueLogic
import org.seion.playback.QueueRestore
import org.seion.playback.RestorePlan
import org.seion.playback.SavedQueue
import org.seion.ui.Format
import org.seion.ui.ambient.AmbientContrast
import org.seion.ui.ambient.ArtStats

/** Regression tests for queue, restore, metadata and layout edge cases. */
class EdgeCaseTest {
    // Two tracks of one album with different embedded art must not share one cover.
    @Test fun `cover identity is per track, not per folder and album`() {
        val a = ArtRef("/m/Album/01.flac", "/m/Album", "Artist", "Album")
        val b = ArtRef("/m/Album/02.flac", "/m/Album", "Artist", "Album")
        assertNotEquals(a.cacheKey, b.cacheKey)
        // Without a track (album rows), the album-level key still applies.
        assertEquals(ArtRef(null, "/m/A", "x", "y").cacheKey, ArtRef(null, "/m/A", "X", "Y").cacheKey)
    }

    // Removing the playing item must move on first (repeat-one would otherwise end the queue).
    @Test fun `removing the playing item skips to the next item first`() {
        assertTrue(QueueLogic.skipBeforeRemoving(removeIndex = 2, currentIndex = 2, hasNext = true))
        assertTrue(!QueueLogic.skipBeforeRemoving(removeIndex = 1, currentIndex = 2, hasNext = true))
        assertTrue(!QueueLogic.skipBeforeRemoving(removeIndex = 2, currentIndex = 2, hasNext = false))
    }

    // A restore without file access, or with the current file missing, must not land on another track in error.
    @Test fun `restore keeps the current track when other files are missing`() {
        val saved = SavedQueue(listOf("/a", "/b", "/c", "/d"), index = 2, positionMs = 5_000)
        assertEquals(RestorePlan(listOf("/b", "/c", "/d"), 1, 5_000), QueueRestore.plan(saved) { it != "/a" })
    }

    @Test fun `restore moves to the next existing file from 0 when the current one is gone`() {
        val saved = SavedQueue(listOf("/a", "/b", "/c", "/d"), index = 2, positionMs = 5_000)
        assertEquals(RestorePlan(listOf("/a", "/b", "/d"), 2, 0), QueueRestore.plan(saved) { it != "/c" })
    }

    @Test fun `nothing restored when no file is readable (access revoked)`() {
        val saved = SavedQueue(listOf("/a", "/b"), index = 1, positionMs = 5_000)
        assertNull(QueueRestore.plan(saved) { false })
    }

    // MP4 files without a year report the QuickTime epoch (1904); 0 is no year either.
    @Test fun `1904 and 0 are no year`() {
        assertNull(TagParsing.year("1904"))
        assertNull(TagParsing.year("19040101T000000.000Z"))
        assertNull(TagParsing.year("0"))
        assertEquals(1959, TagParsing.year("1959"))
    }

    // Elapsed never exceeds the total, with the same rounding for both.
    @Test fun `elapsed is clamped to the total`() {
        assertEquals("00:39", Format.elapsed(40_050, 39_900))
        assertEquals(Format.duration(39_900), Format.elapsed(39_950, 39_900))
        assertEquals("00:10", Format.elapsed(10_400, 39_900))
        assertEquals("00:05", Format.elapsed(5_000, 0)) // unknown total: no clamp
    }

    // Very light covers get a wider feather (and stronger dimming) so no box edge shows.
    @Test fun `bright covers get a wider feather and stronger dimming`() {
        fun solid(argb: Int) = ArtStats.of(IntArray(256) { argb }, 16, 16)
        val white = AmbientContrast.compute(solid(0xFFFFFFFF.toInt()))
        val dark = AmbientContrast.compute(solid(0xFF201008.toInt()))
        assertTrue("white feather ${white.feather}", white.feather >= 0.15f)
        assertEquals(0.09f, dark.feather, 0.001f)
        assertTrue("white cover brightness ${white.coverBrightness}", white.coverBrightness <= 0.62f)
        assertTrue(white.greyContrast >= 4.5)
    }

    // Right after starting a new list the UI shows the new start item, not the old queue's item.
    @Test fun `a pending new list wins over a stale controller item for a moment`() {
        assertEquals("new", QueueLogic.displayedCurrent(controllerId = "old", pendingId = "new", pendingAgeMs = 300))
        assertEquals("old", QueueLogic.displayedCurrent(controllerId = "old", pendingId = "new", pendingAgeMs = 5_000))
        assertEquals("x", QueueLogic.displayedCurrent(controllerId = "x", pendingId = null, pendingAgeMs = 0))
    }

    // pt-BR labels that were truncated at normal size must fit their slots.
    @Test fun `pt-BR tile and bar labels fit`() {
        val xml = File("src/main/res/values-pt-rBR/strings.xml").readText()
        fun str(name: String) = Regex("<string name=\"$name\">([^<]*)</string>").find(xml)!!.groupValues[1]
        for (n in listOf("all_songs", "recently_added", "newest_first")) assertTrue("$n=${str(n)}", str(n).length <= 13)
        assertTrue(str("play_queue"), str("play_queue").length <= 14)
    }
}
