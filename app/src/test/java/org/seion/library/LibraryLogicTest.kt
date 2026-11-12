package org.seion.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `extension filter accepts every v1 format, case-insensitively`() {
        for (n in listOf("a.mp3", "b.M4A", "c.flac", "d.wav", "e.ogg", "f.oga", "g.opus", "h.aac", "Ü ñ 🎵.FLAC")) {
            assertTrue(n, AudioFiles.isAudio(n))
        }
    }

    @Test fun `extension filter rejects everything else`() {
        val names = listOf("cover.jpg", "a.dsf", "b.dff", "album.cue", "x.wma", "y.ape", "notes.txt", "mp3", ".mp3", "track.", "a.mp3.part")
        for (n in names) {
            assertFalse(n, AudioFiles.isAudio(n))
        }
    }

    @Test fun `scanner skips hidden folders, Android at a volume root and nomedia folders`() {
        val root = tmp.newFolder("vol")
        val hidden = File(root, ".thumbs").apply { mkdirs() }
        val android = File(root, "Android").apply { mkdirs() }
        val nested = File(root, "Music/Android").apply { mkdirs() }
        val nomedia = File(root, "Ringtones").apply { mkdirs() }
        File(nomedia, ".nomedia").writeText("")
        val music = File(root, "Music").apply { mkdirs() }
        val isRoot = { f: File -> f == root }
        assertTrue(AudioFiles.shouldSkipDir(hidden, isRoot))
        assertTrue(AudioFiles.shouldSkipDir(android, isRoot))
        assertFalse(AudioFiles.shouldSkipDir(nested, isRoot))
        assertTrue(AudioFiles.shouldSkipDir(nomedia, isRoot))
        assertFalse(AudioFiles.shouldSkipDir(music, isRoot))
    }

    @Test fun `folder listing puts folders first, then audio files, both in name order`() {
        val dir = tmp.newFolder("album")
        listOf("b", "A", "c").forEach { File(dir, it).mkdirs() }
        listOf("02 two.mp3", "01 One.flac", "cover.jpg", "10 ten.opus", ".hidden.mp3").forEach { File(dir, it).writeText("x") }
        File(dir, ".git").mkdirs()
        val l = listFolder(dir)
        assertEquals(listOf("A", "b", "c"), l.folders.map { it.name })
        assertEquals(listOf("01 One.flac", "02 two.mp3", "10 ten.opus"), l.files.map { it.name })
    }

    @Test fun `first scan reads every file`() {
        val plan = ScanPlanner.plan(emptyMap(), listOf(FoundFile("/a", 1, 10), FoundFile("/b", 2, 10)))
        assertEquals(listOf("/a", "/b"), plan.toRead.map { it.path })
        assertTrue(plan.toRemove.isEmpty())
    }

    @Test fun `incremental scan re-reads only new or modified files and removes missing ones`() {
        val indexed = mapOf("/same" to 5L, "/changed" to 5L, "/gone" to 5L)
        val found = listOf(FoundFile("/same", 5, 1), FoundFile("/changed", 6, 1), FoundFile("/new", 1, 1))
        val plan = ScanPlanner.plan(indexed, found)
        assertEquals(listOf("/changed", "/new"), plan.toRead.map { it.path })
        assertEquals(listOf("/gone"), plan.toRemove)
        assertEquals(1, plan.unchanged)
    }

    @Test fun `a second identical scan does nothing`() {
        val found = listOf(FoundFile("/a", 1, 1), FoundFile("/b", 2, 1))
        val plan = ScanPlanner.plan(found.associate { it.path to it.lastModified }, found)
        assertTrue(plan.toRead.isEmpty())
        assertTrue(plan.toRemove.isEmpty())
        assertEquals(2, plan.unchanged)
    }

    @Test fun `duplicate paths in one walk are read once`() {
        val plan = ScanPlanner.plan(emptyMap(), listOf(FoundFile("/a", 1, 1), FoundFile("/a", 1, 1)))
        assertEquals(1, plan.toRead.size)
    }

    @Test fun `tag parsing`() {
        assertEquals(3, TagParsing.number("3/12"))
        assertEquals(7, TagParsing.number(" 07 "))
        assertNull(TagParsing.number("0"))
        assertNull(TagParsing.number("x"))
        assertEquals(2021, TagParsing.year("2021-05-01"))
        assertEquals(1959, TagParsing.year("19590817T000000.000Z"))
        assertNull(TagParsing.year("0000"))
        assertNull(TagParsing.genre("(17)"))
        assertEquals("Jazz", TagParsing.genre("Jazz"))
        assertNull(TagParsing.clean("  \u0000 "))
    }

    @Test fun `album keys group by album artist, else by folder`() {
        assertEquals(AlbumKeys.of("Blue", "Joni", "/m/x"), AlbumKeys.of("BLUE", "joni", "/m/y"))
        assertNotEquals(AlbumKeys.of("Hits", null, "/m/one"), AlbumKeys.of("Hits", null, "/m/two"))
        assertEquals("folder:/m/untagged", AlbumKeys.of(null, "X", "/m/untagged"))
    }

    @Test fun `like pattern escapes wildcards`() {
        assertEquals("%100\\%\\_x%", likePattern(" 100%_x "))
    }

    @Test fun `music folders list internal storage first, then cards`() {
        val roots = listOf("/storage/0000-0000", "/storage/emulated/0/Music", "/storage/emulated/0/Download/x", "/storage/0000-0000")
        assertEquals(
            listOf("/storage/emulated/0/Download/x", "/storage/emulated/0/Music", "/storage/0000-0000"),
            orderRoots(roots, "/storage/emulated/0")
        )
        assertEquals(listOf("/a", "/b"), orderRoots(listOf("/b", "/a"), null))
    }
}
