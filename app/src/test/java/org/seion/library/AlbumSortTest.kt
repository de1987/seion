package org.seion.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Albums without an album tag show their folder's name ([AlbumRow.title]) and must sort by that name with the rest,
 * a leading "The " ignored like every other sort key. Sorting by the folder's whole path ("/storage/…") would put
 * them all at the top of the grid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AlbumSortTest {
    private lateinit var db: SeionDatabase

    @Before fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), SeionDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After fun tearDown() = db.close()

    private fun row(folder: String, album: String?, artist: String? = "Artist", year: Int? = null) = TrackEntity(
        path = "$folder/01.flac", folder = folder, fileName = "01.flac", title = "01", artist = artist, albumArtist = null,
        album = album, albumKey = album?.lowercase() ?: folder, genre = null, year = year, trackNumber = 1, discNumber = null,
        durationMs = 200_000, lastModified = 1, sizeBytes = 1, dateAdded = 1, hasEmbeddedArt = false
    )

    private val rows =
        listOf(
            row("/storage/emulated/0/Music/Zeta", "Zeta"),
            row("/storage/emulated/0/Music/Untagged/Alpha", null),
            row("/storage/emulated/0/Music/The Beta", null),
            row("/storage/emulated/0/Music/Gamma", "The Gamma"),
            row("/storage/0000-0000/Music/delta", null)
        )

    @Test fun `untagged albums sort by their folder name with the rest`() = runTest {
        db.tracks().upsert(rows)
        val titles = db.tracks().albums(0).first().map { it.title }
        assertEquals(listOf("Alpha", "The Beta", "delta", "The Gamma", "Zeta"), titles)
    }

    @Test fun `an artist's albums of the same year sort by folder name too`() = runTest {
        db.tracks().upsert(rows)
        val titles = db.tracks().artistAlbums("Artist", 0).first().map { it.title }
        assertEquals(listOf("Alpha", "The Beta", "delta", "The Gamma", "Zeta"), titles)
    }

    @Test fun `the grid's other orders are artist, year (newest first) and recently added`() = runTest {
        fun r(album: String, artist: String?, year: Int?, added: Long) =
            row("/m/$album", album, artist, year).copy(path = "/m/$album/01.flac", dateAdded = added)
        db.tracks().upsert(
            listOf(
                r("Moon", "The Zebras", 2019, 30),
                r("Sun", "Ana Lima", 2023, 10),
                r("Star", "Ana Lima", null, 20),
                r("Rain", null, 2001, 40)
            )
        )
        suspend fun titles(sort: AlbumSort) = db.tracks().albums(0, sort).first().map { it.title }
        assertEquals(listOf("Moon", "Rain", "Star", "Sun"), titles(AlbumSort.TITLE))
        // "The Zebras" sorts as "Zebras"; albums without an artist last; an artist's albums by title.
        assertEquals(listOf("Star", "Sun", "Moon", "Rain"), titles(AlbumSort.ARTIST))
        assertEquals(listOf("Sun", "Moon", "Rain", "Star"), titles(AlbumSort.YEAR))
        assertEquals(listOf("Rain", "Moon", "Star", "Sun"), titles(AlbumSort.RECENT))
    }
}
