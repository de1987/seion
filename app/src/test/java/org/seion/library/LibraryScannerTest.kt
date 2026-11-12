package org.seion.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The scanner against an in-memory Room DB: incremental by path + lastModified, short-track filter at query time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LibraryScannerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: SeionDatabase
    private val reads = mutableListOf<String>()

    private val reader =
        TagReader { f ->
            reads += f.name
            if (f.name.startsWith("corrupt")) {
                null
            } else {
                Tags(
                    title = f.nameWithoutExtension.uppercase(),
                    artist = "Artist",
                    albumArtist = null,
                    album = "Album",
                    genre = null,
                    year = 2020,
                    trackNumber = 1,
                    discNumber = null,
                    durationMs = if (f.name.startsWith("short")) 10_000 else 200_000,
                    hasEmbeddedArt = false
                )
            }
        }

    @Before fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), SeionDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After fun tearDown() = db.close()

    private fun scanner() = LibraryScanner(db.tracks(), reader, isVolumeRoot = { false }, now = { 1_000L })

    @Test fun `incremental scan only reads new and changed files`() = runTest {
        val root = tmp.newFolder("Music")
        val album = File(root, "Artist/Album").apply { mkdirs() }
        val a = File(album, "a.flac").apply { writeText("a") }
        File(album, "b.mp3").writeText("b")
        File(album, "cover.jpg").writeText("img")
        File(root, ".hidden").mkdirs()
        File(root, ".hidden/x.mp3").writeText("x")

        val first = scanner().scan(listOf(root.path))
        assertEquals(2, first.found)
        assertEquals(2, first.read)

        reads.clear()
        val second = scanner().scan(listOf(root.path))
        assertEquals(0, second.read)
        assertEquals(2, second.unchanged)
        assertEquals(emptyList<String>(), reads)

        a.setLastModified(a.lastModified() + 5_000)
        File(album, "c.opus").writeText("c")
        File(album, "b.mp3").delete()
        val third = scanner().scan(listOf(root.path))
        assertEquals(2, third.read)
        assertEquals(1, third.removed)
        assertEquals(setOf("a.flac", "c.opus"), reads.toSet())
        assertEquals(listOf("A", "C"), db.tracks().songs(0).first().map { it.title })
    }

    @Test fun `untagged or unreadable files fall back to the file and folder names`() = runTest {
        val root = tmp.newFolder("Music")
        val dir = File(root, "Field Recordings").apply { mkdirs() }
        File(dir, "corrupt take.wav").writeText("not audio")
        scanner().scan(listOf(root.path))
        val t = db.tracks().songs(0).first().single()
        assertEquals("corrupt take", t.title)
        assertEquals("Field Recordings", t.albumTitle)
        assertEquals(0L, t.durationMs)
    }

    @Test fun `short tracks are hidden only while the setting is on`() = runTest {
        val root = tmp.newFolder("Music")
        File(root, "short jingle.mp3").writeText("s")
        File(root, "long song.mp3").writeText("l")
        scanner().scan(listOf(root.path))
        assertEquals(1, db.tracks().songCount(30_000).first())
        assertEquals(2, db.tracks().songCount(0).first())
    }

    @Test fun `a root that is no longer configured is dropped from the index`() = runTest {
        val one = tmp.newFolder("one").also { File(it, "x.mp3").writeText("x") }
        val two = tmp.newFolder("two").also { File(it, "y.mp3").writeText("y") }
        scanner().scan(listOf(one.path, two.path))
        assertEquals(2, db.tracks().songCount(0).first())
        scanner().scan(listOf(one.path))
        assertEquals(1, db.tracks().songCount(0).first())
    }

    @Test fun `sorting ignores a leading The and artists are listed by album artist`() = runTest {
        val root = tmp.newFolder("Music")
        val r =
            TagReader { f ->
                val n = f.nameWithoutExtension
                Tags(
                    title = n,
                    artist = if (n == "Guest Song") "Guest" else "The Band",
                    albumArtist = if (n == "Guest Song") "The Band" else null,
                    album = "Album",
                    genre = null,
                    year = null,
                    trackNumber = null,
                    discNumber = null,
                    durationMs = 200_000,
                    hasEmbeddedArt = false
                )
            }
        listOf("The Zebra", "apple", "Mango", "Guest Song").forEach { File(root, "$it.mp3").writeText(it) }
        LibraryScanner(db.tracks(), r, isVolumeRoot = { false }).scan(listOf(root.path))
        assertEquals(listOf("apple", "Guest Song", "Mango", "The Zebra"), db.tracks().songs(0).first().map { it.title })
        // "Guest Song" has album artist "The Band": one artist row, not two.
        assertEquals(listOf("The Band" to 4), db.tracks().artists(0).first().map { it.name to it.trackCount })
    }

    private fun tagged(artist: String?) = TagReader { f ->
        Tags(
            title = null, artist = artist, albumArtist = null, album = null, genre = null, year = null,
            trackNumber = null, discNumber = null, durationMs = 200_000, hasEmbeddedArt = false
        )
    }

    /** The file as the online lookup left it: title and artist filled online, FOUND. */
    private suspend fun identifyOnline(path: String) {
        val t = db.tracks().byPaths(listOf(path)).single()
        db.tracks().upsert(
            listOf(
                t.copy(
                    title = "Dreams",
                    titleSource = TagSource.ONLINE,
                    artist = "Fleetwood Mac",
                    artistSource = TagSource.ONLINE,
                    lookupState = LookupState.FOUND,
                    lookupAt = 5L,
                    lookupSource = "musicbrainz",
                    lookupRef = "mbid"
                )
            )
        )
    }

    @Test fun `sources follow the tags and the file-name fallback`() = runTest {
        val root = tmp.newFolder("Music")
        val f = File(root, "03 - Fleetwood Mac - Dreams.mp3").apply { writeText("x") }
        LibraryScanner(db.tracks(), tagged(null), isVolumeRoot = { false }).scan(listOf(root.path))
        val t = db.tracks().byPaths(listOf(f.path)).single()
        assertEquals(TagSource.FALLBACK, t.titleSource)
        assertEquals(TagSource.MISSING, t.artistSource)
        assertEquals(TagSource.MISSING, t.albumSource)
        assertEquals(LookupState.NONE, t.lookupState)
    }

    @Test fun userTagWinsOverOnline() = runTest {
        val root = tmp.newFolder("Music")
        val f = File(root, "03 - Fleetwood Mac - Dreams.mp3").apply { writeText("x") }
        LibraryScanner(db.tracks(), tagged(null), isVolumeRoot = { false }).scan(listOf(root.path))
        identifyOnline(f.path)
        f.setLastModified(f.lastModified() + 5_000) // the user tagged the file on a PC
        LibraryScanner(db.tracks(), tagged("B"), isVolumeRoot = { false }).scan(listOf(root.path))
        val t = db.tracks().byPaths(listOf(f.path)).single()
        assertEquals("B", t.artist)
        assertEquals(TagSource.TAG, t.artistSource)
        assertEquals("Dreams", t.title) // the title tag is still missing: the online value stays
        assertEquals(TagSource.ONLINE, t.titleSource)
        assertEquals(LookupState.FOUND, t.lookupState)
        assertEquals("mbid", t.lookupRef)
    }

    @Test fun onlineValueSurvivesRescan() = runTest {
        val root = tmp.newFolder("Music")
        val f = File(root, "03 - Fleetwood Mac - Dreams.mp3").apply { writeText("x") }
        LibraryScanner(db.tracks(), tagged(null), isVolumeRoot = { false }).scan(listOf(root.path))
        identifyOnline(f.path)
        f.setLastModified(f.lastModified() + 5_000)
        LibraryScanner(db.tracks(), tagged(null), isVolumeRoot = { false }).scan(listOf(root.path))
        val t = db.tracks().byPaths(listOf(f.path)).single()
        assertEquals("Fleetwood Mac", t.artist)
        assertEquals(TagSource.ONLINE, t.artistSource)
        assertEquals("Dreams", t.title)
        assertEquals(LookupState.FOUND, t.lookupState)
        assertEquals(5L, t.lookupAt)
        assertEquals("musicbrainz", t.lookupSource)
    }
}
