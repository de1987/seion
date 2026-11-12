package org.seion.playlists

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.seion.library.SeionDatabase
import org.seion.library.TrackEntity

/** The playlists database and repository (Robolectric, real SQLite): edits, duplicates, missing files, durability, import. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PlaylistDbTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val library = Room.inMemoryDatabaseBuilder(context, SeionDatabase::class.java).allowMainThreadQueries().build()
    private val db = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).allowMainThreadQueries().build()
    private val onDisk = mutableSetOf<String>()
    private var clock = 1_000L
    private val repo =
        PlaylistRepository(
            db.playlists(),
            library.tracks(),
            volumeRoots = { listOf("/storage/emulated/0") },
            now = { clock++ },
            io = Dispatchers.Unconfined,
            fileExists = { it in onDisk }
        )

    @After fun tearDown() {
        db.close()
        library.close()
        context.deleteDatabase(PlaylistDatabase.FILE)
        context.deleteDatabase("seion.db")
    }

    private fun track(path: String, title: String, album: String = "Glass Harbour", ms: Long = 60_000) = TrackEntity(
        path = path,
        folder = path.substringBeforeLast('/'),
        fileName = path.substringAfterLast('/'),
        title = title,
        artist = "Aurora Vale",
        albumArtist = null,
        album = album,
        albumKey = album.lowercase(),
        genre = null,
        year = null,
        trackNumber = null,
        discNumber = null,
        durationMs = ms,
        lastModified = 1,
        sizeBytes = 1,
        dateAdded = 1,
        hasEmbeddedArt = false
    )

    private fun song(path: String, title: String = path.substringAfterLast('/'), ms: Long = 60_000) =
        NewEntry(path, title, "A", "Al", null, ms)

    @Test fun `create, append at the end, skip duplicates, count and length`() = runTest {
        val id = repo.create("  Late   Train ", listOf(song("/m/a"), song("/m/b")))
        val r = repo.add(id, listOf(song("/m/b"), song("/m/c"), song("/m/c")))
        assertEquals(AddResult(1, 2), r)
        assertEquals(listOf("/m/a", "/m/b", "/m/c"), db.playlists().entriesNow(id).map { it.path })
        assertEquals(listOf(0, 1, 2), db.playlists().entriesNow(id).map { it.position })
        val s = repo.summary(id).first()!!
        assertEquals("Late Train", s.name)
        assertEquals(3, s.trackCount)
        assertEquals(180_000L, s.durationMs)
        assertEquals(1, repo.count().first())
    }

    @Test fun `reorder and remove renumber positions and keep entry ids, undo restores`() = runTest {
        val id = repo.create("Mix", listOf(song("/m/a"), song("/m/b"), song("/m/c")))
        val before = db.playlists().entriesNow(id)
        repo.setEntries(id, PlaylistRules.move(before, 0, 2))
        val moved = db.playlists().entriesNow(id)
        assertEquals(listOf("/m/b", "/m/c", "/m/a"), moved.map { it.path })
        assertEquals(listOf(0, 1, 2), moved.map { it.position })
        assertEquals(before.map { it.id }.toSet(), moved.map { it.id }.toSet())
        repo.setEntries(id, moved.filter { it.path != "/m/c" })
        assertEquals(listOf("/m/b", "/m/a"), db.playlists().entriesNow(id).map { it.path })
        repo.setEntries(id, before)
        assertEquals(before, db.playlists().entriesNow(id))
    }

    @Test fun `rename and delete, and deleting removes its songs too`() = runTest {
        val id = repo.create("Mix", listOf(song("/m/a")))
        val other = repo.create("Other", listOf(song("/m/a")))
        repo.rename(id, "Road Trip")
        assertEquals(listOf("Other", "Road Trip"), repo.names().sorted())
        repo.delete(id)
        assertFalse(repo.exists(id))
        assertNull(repo.summary(id).first())
        assertEquals(emptyList<PlaylistEntryEntity>(), db.playlists().entriesNow(id))
        assertEquals(1, db.playlists().entriesNow(other).size)
    }

    @Test fun `rows resolve against the library and the disk, and a missing file stays, unavailable`() = runTest {
        library.tracks().upsert(listOf(track("/m/a.flac", "Undertow")))
        onDisk += "/m/outside.mp3"
        val id = repo.create("Mix", listOf(song("/m/a.flac", "old title"), song("/m/outside.mp3", "Outside"), song("/m/gone.mp3", "Gone")))
        val items = repo.items(id).first()
        assertEquals(listOf(true, true, false), items.map { it.available })
        assertEquals(listOf("Undertow", "Outside", "Gone"), items.map { it.title })
        // The snapshot follows the library (the lists' names and lengths).
        assertEquals("Undertow", db.playlists().entriesNow(id)[0].title)
        // The file comes back (card mounted, rescan): available again.
        library.tracks().upsert(listOf(track("/m/gone.mp3", "Gone")))
        assertEquals(listOf(true, true, true), repo.items(id).first().map { it.available })
    }

    @Test fun `the mosaic covers come from the first four different albums`() = runTest {
        val id =
            repo.create(
                "Mix",
                listOf("A", "A", "B", "C", "D", "E").mapIndexed { i, al -> NewEntry("/m/$al/$i", "t$i", "x", al, null, 1) }
            )
        val covers = repo.covers().first().getValue(id)
        assertEquals(listOf("/m/A/0", "/m/B/2", "/m/C/3", "/m/D/4"), covers.map { it.trackPath })
    }

    @Test fun `import reads an M3U, matches the library, skips duplicates and counts what is missing`() = runTest {
        library.tracks().upsert(
            listOf(
                track("/storage/emulated/0/Music/Aurora Vale/Glass Harbour/01 Undertow.flac", "Undertow"),
                track("/storage/emulated/0/Music/Kelp Choir/Low Light/01 Fog Signal.ogg", "Fog Signal")
            )
        )
        val dir = File(context.cacheDir, "m3u").apply { mkdirs() }
        val file = File(dir, "Road Trip.m3u")
        file.writeText(
            "#EXTM3U\n/storage/emulated/0/Music/Aurora Vale/Glass Harbour/01 Undertow.flac\n" +
                "..\\Music\\Kelp Choir\\Low Light\\01 Fog Signal.ogg\n" +
                "/storage/emulated/0/Music/Aurora Vale/Glass Harbour/01 Undertow.flac\n" +
                "C:\\Music\\Nowhere\\missing.mp3\n"
        )
        repo.create("Road Trip", emptyList())
        val r = repo.import(context.contentResolver, Uri.fromFile(file), file.name, "Playlist")
        assertEquals("Road Trip (2)", r.name)
        assertEquals(2, r.added)
        assertEquals(1, r.notFound)
        assertEquals(listOf("Undertow", "Fog Signal"), db.playlists().entriesNow(r.id!!).map { it.title })
    }

    @Test fun `a file with nothing found imports nothing`() = runTest {
        val file = File(context.cacheDir, "empty.m3u8").apply { writeText("#EXTM3U\n/nowhere/x.mp3\n") }
        val r = repo.import(context.contentResolver, Uri.fromFile(file), file.name, "Playlist")
        assertNull(r.id)
        assertEquals(1, r.notFound)
        assertEquals(0, repo.count().first())
    }

    @Test fun `playlists live in their own file, so dropping and rebuilding the library index leaves them`() = runTest {
        val playlists = PlaylistDatabase.create(context)
        val id = playlists.playlists().create("Keep me", listOf(song("/m/a")), 1)
        playlists.close()
        val index = SeionDatabase.create(context)
        index.tracks().upsert(listOf(track("/m/a", "A")))
        index.close()
        // The index is a cache: Room's destructive fallback (or a cleared index) removes seion.db as a whole.
        assertTrue(context.deleteDatabase("seion.db"))
        val rebuilt = SeionDatabase.create(context)
        assertTrue(rebuilt.tracks().isEmpty())
        rebuilt.close()
        val reopened = PlaylistDatabase.create(context)
        assertEquals(listOf("/m/a"), reopened.playlists().entriesNow(id).map { it.path })
        reopened.close()
    }

    @Test fun `the schema is exported, so a future version can migrate it`() {
        val schema = listOf(File("schemas"), File("app/schemas")).map { File(it, "org.seion.playlists.PlaylistDatabase/1.json") }
        assertTrue("schemas/org.seion.playlists.PlaylistDatabase/1.json is missing", schema.any { it.isFile })
    }
}
