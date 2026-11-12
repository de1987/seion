package org.seion.playlists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure playlist rules. */
class PlaylistRulesTest {
    private fun fmt(n: Int) = "Playlist $n"

    @Test fun `names are trimmed and white space is folded`() {
        assertEquals("Late Train", PlaylistNames.clean("  Late \t Train\n "))
        assertEquals("", PlaylistNames.clean(" \n\t "))
    }

    @Test fun `a name is required, at most 100 characters and unique ignoring case`() {
        assertEquals(PlaylistNames.Problem.EMPTY, PlaylistNames.problem("   ", emptyList()))
        assertEquals(PlaylistNames.Problem.TOO_LONG, PlaylistNames.problem("x".repeat(101), emptyList()))
        assertNull(PlaylistNames.problem("x".repeat(100), emptyList()))
        assertEquals(PlaylistNames.Problem.DUPLICATE, PlaylistNames.problem(" late train ", listOf("Late Train")))
        assertNull(PlaylistNames.problem("Late Train 2", listOf("Late Train")))
        // A rename passes the other playlists only: changing just the case of its own name is fine.
        assertNull(PlaylistNames.problem("late train", emptyList()))
    }

    @Test fun `new playlist suggests the first free number`() {
        assertEquals("Playlist 1", PlaylistNames.suggest(emptyList(), ::fmt))
        assertEquals("Playlist 3", PlaylistNames.suggest(listOf("playlist 1", "Playlist 2", "Playlist 4"), ::fmt))
        assertEquals("Playlist 1", PlaylistNames.suggest(listOf("Playlist", "Playlist 10"), ::fmt))
    }

    @Test fun `an imported name is made free with a counter and kept within the limit`() {
        assertEquals("Road Trip", PlaylistNames.unique("Road Trip", listOf("Mix"), "Playlist"))
        assertEquals("Road Trip (2)", PlaylistNames.unique("Road Trip", listOf("road trip"), "Playlist"))
        assertEquals("Road Trip (3)", PlaylistNames.unique("Road Trip", listOf("Road Trip", "Road Trip (2)"), "Playlist"))
        assertEquals("Playlist", PlaylistNames.unique("  ", emptyList(), "Playlist"))
        val long = "y".repeat(120)
        assertEquals(100, PlaylistNames.unique(long, emptyList(), "P").length)
        val second = PlaylistNames.unique(long, listOf("y".repeat(100)), "P")
        assertEquals(100, second.length)
        assertEquals(" (2)", second.takeLast(4))
    }

    @Test fun `adding skips songs already in the playlist and twice in the batch, and counts them`() {
        val plan = PlaylistRules.planAdd(listOf("/a", "/b"), listOf("/b", "/c", "/c", "/d", "/a")) { it }
        assertEquals(listOf("/c", "/d"), plan.toAdd)
        assertEquals(3, plan.skipped)
        val fresh = PlaylistRules.planAdd(emptyList(), listOf("/x", "/y")) { it }
        assertEquals(listOf("/x", "/y"), fresh.toAdd)
        assertEquals(0, fresh.skipped)
    }

    @Test fun `move shifts the others and ignores out-of-range positions`() {
        val l = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), PlaylistRules.move(l, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), PlaylistRules.move(l, 3, 0))
        assertEquals(listOf("a", "c", "b", "d"), PlaylistRules.move(l, 2, 1))
        assertEquals(l, PlaylistRules.move(l, 1, 1))
        assertEquals(l, PlaylistRules.move(l, -1, 2))
        assertEquals(l, PlaylistRules.move(l, 0, 4))
    }

    private data class Row(val id: Long, val name: String, val changed: Long)

    @Test fun `sort by name ignores case and a leading The, sort by recency puts the latest change first`() {
        val rows = listOf(Row(1, "zebra", 5), Row(2, "The Beatles mix", 9), Row(3, "apple", 1), Row(4, "Theory", 9))
        assertEquals(
            listOf("apple", "The Beatles mix", "Theory", "zebra"),
            PlaylistRules.sorted(rows, PlaylistSort.NAME, Row::name, Row::changed, Row::id).map { it.name }
        )
        // Same time: the newer playlist (higher id) first.
        assertEquals(
            listOf(4L, 2L, 1L, 3L),
            PlaylistRules.sorted(rows, PlaylistSort.RECENT, Row::name, Row::changed, Row::id).map { it.id }
        )
    }

    @Test fun `the mosaic takes the first song of four different albums, else one cover`() {
        val songs = listOf("a1" to "A", "a2" to "A", "b1" to "B", "c1" to "C", "b2" to "B", "d1" to "D", "e1" to "E")
        assertEquals(listOf("a1", "b1", "c1", "d1"), PlaylistRules.mosaic(songs) { it.second }.map { it.first })
        assertEquals(listOf("a1"), PlaylistRules.mosaic(songs.take(5)) { it.second }.map { it.first })
        assertEquals(emptyList<Pair<String, String>>(), PlaylistRules.mosaic(emptyList<Pair<String, String>>()) { it.second })
    }

    @Test fun `play starts at the tapped song among the playable ones, a missing file starts nothing`() {
        val items = listOf("a" to true, "gone" to false, "b" to true, "c" to true)
        val (list, start) = PlaylistRules.playable(items, 2) { it.second }!!
        assertEquals(listOf("a", "b", "c"), list.map { it.first })
        assertEquals(1, start)
        assertNull(PlaylistRules.playable(items, 1) { it.second })
        assertNull(PlaylistRules.playable(items, 9) { it.second })
        assertEquals(0, PlaylistRules.playable(items, 0) { it.second }!!.second)
    }

    @Test fun `the sort key drops only a leading The followed by a space`() {
        assertEquals("Beatles", PlaylistRules.sortKey("The Beatles"))
        assertEquals("Theory", PlaylistRules.sortKey("Theory"))
        assertEquals("The ", PlaylistRules.sortKey("The "))
    }
}
