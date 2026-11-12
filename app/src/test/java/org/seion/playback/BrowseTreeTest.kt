package org.seion.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.seion.library.NameCount
import org.seion.library.TrackEntity
import org.seion.playlists.PlaylistEntryEntity
import org.seion.playlists.PlaylistItem
import org.seion.playlists.PlaylistSummary

/** The browse tree (Android Auto, AVRCP browsing, external search): ids, paging, playlists, search, the play queue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BrowseTreeTest {
    private val music = "/storage/emulated/0/Music"

    private fun track(
        id: Long,
        title: String,
        album: String? = "Glass Harbour",
        artist: String? = "Aurora Vale",
        folder: String = "$music/Aurora Vale/Glass Harbour"
    ) = TrackEntity(
        id = id,
        path = "$folder/$id $title.flac",
        folder = folder,
        fileName = "$id $title.flac",
        title = title,
        artist = artist,
        albumArtist = null,
        album = album,
        albumKey = album?.lowercase() ?: "folder:$folder",
        genre = null,
        year = null,
        trackNumber = id.toInt(),
        discNumber = 1,
        durationMs = 200_000,
        lastModified = 0,
        sizeBytes = 0,
        dateAdded = 0,
        hasEmbeddedArt = false
    )

    private val harbour = (1L..5L).map { track(it, "Song $it") }
    private val untagged = listOf(track(20, "Field", album = null, artist = null, folder = "$music/Recordings"))
    private val songs = (100L until 100L + 700).map { track(it, "Title %03d".format(it - 100), album = "Big", folder = "$music/Big") }

    private fun entry(id: Long, position: Int, t: TrackEntity?, path: String = t?.path ?: "$music/gone/$id.mp3") = PlaylistEntryEntity(
        id = id,
        playlistId = 7,
        position = position,
        path = path,
        title = t?.title ?: "Gone $id",
        artist = "A",
        album = null,
        albumArtist = null,
        durationMs = 1_000
    )

    /** Playlist 7: an unavailable song first, then two library songs, then a file the index doesn't know (on disk). */
    private val playlist7 = listOf(
        PlaylistItem(entry(70, 0, null), null, available = false),
        PlaylistItem(entry(71, 1, harbour[2]), harbour[2], available = true),
        PlaylistItem(entry(72, 2, harbour[0]), harbour[0], available = true),
        PlaylistItem(entry(73, 3, null, "$music/new/unindexed.mp3"), null, available = true)
    )

    private var queue = BrowseQueue.EMPTY

    private val source =
        object : BrowseSource {
            var lastPage: Pair<Int, Int>? = null

            override suspend fun albums() =
                listOf(BrowseAlbum(1, "Glass Harbour", "Aurora Vale", 5), BrowseAlbum(20, "Recordings", null, 1))

            override suspend fun albumTracks(anyTrackId: Long) = when (anyTrackId) {
                in 1L..5L -> harbour
                20L -> untagged
                else -> null
            }

            override suspend fun artists() = listOf(NameCount("Aurora Vale", 5, 1), NameCount(null, 1, 1), NameCount("AC/DC | Live", 0, 0))

            override suspend fun artistTracks(name: String?) = when (name) {
                "Aurora Vale" -> harbour
                null -> untagged
                else -> emptyList()
            }

            override suspend fun playlists() = listOf(PlaylistSummary(7, "Road trip", 0, 0, 4, 4_000))

            override suspend fun playlistItems(id: Long) = if (id == 7L) playlist7 else null

            override suspend fun songCount() = songs.size

            override suspend fun songs(offset: Int, limit: Int): List<TrackEntity> {
                lastPage = offset to limit
                return songs.drop(offset).take(limit)
            }

            override suspend fun allSongs() = songs

            override suspend fun search(query: String) = (harbour + songs).filter { it.title.contains(query, ignoreCase = true) }.take(200)

            override suspend fun queue() = queue

            override suspend fun trackIds(paths: List<String>) = (harbour + songs).filter {
                it.path in paths
            }.associate { it.path to it.id }
        }

    private val strings =
        object : BrowseStrings {
            override fun title(node: BrowseIds.Node) = node.toString()

            override fun unknownArtist() = "Unknown artist"

            override fun songs(count: Int) = "$count songs"
        }

    private val tree = BrowseTree(source, strings) { id -> Uri.parse("content://org.seion.browseart/t/$id") }

    private fun children(id: String, page: Int = 0, size: Int = 1_000) = runBlocking { tree.children(id, page, size) }

    private fun ids(items: List<MediaItem>?) = items.orEmpty().map { it.mediaId }

    /** Nothing a browser sees names a file: no media id, artwork URI or extra holds a path. */
    private fun assertNoPaths(items: List<MediaItem>) = items.forEach { item ->
        assertFalse(item.mediaId, item.mediaId.contains(music))
        assertFalse(item.mediaId, item.mediaId.startsWith("/"))
        val art = item.mediaMetadata.artworkUri?.toString().orEmpty()
        assertFalse(art, art.contains("storage") || art.contains("seion-art"))
        assertTrue(art, art.isEmpty() || art.startsWith("content://org.seion.browseart/t/"))
        assertNull(item.mediaMetadata.extras)
        assertNull(item.localConfiguration)
    }

    @Test fun `ids round-trip, names are encoded, and a path is never a browse id`() {
        val nodes = listOf(
            BrowseIds.Node.Root,
            BrowseIds.Node.Recent,
            BrowseIds.Node.Albums,
            BrowseIds.Node.Songs,
            BrowseIds.Node.Album(42),
            BrowseIds.Node.Artist("AC/DC | Live + 1 ~ ação"),
            BrowseIds.Node.Artist("~"),
            BrowseIds.Node.Artist(null),
            BrowseIds.Node.Playlist(7),
            BrowseIds.Node.Search("so what / 100%")
        )
        for (n in nodes) {
            val id = BrowseIds.node(n)
            assertEquals(id, n, BrowseIds.parseNode(id))
            assertFalse(id, id.contains('|'))
            assertTrue(BrowseIds.isBrowseId(id))
        }
        val song = BrowseIds.song(BrowseIds.Node.Artist("A|B/C"), "12")
        assertEquals(BrowseIds.Song(BrowseIds.Node.Artist("A|B/C"), "12"), BrowseIds.parseSong(song))
        assertNull("a node is not a song", BrowseIds.parseSong(BrowseIds.node(BrowseIds.Node.Album(3))))
        assertNull("lists of lists hold no songs", BrowseIds.parseSong("albums|3"))
        assertNull(BrowseIds.parseSong("nonsense|3"))
        assertNull(BrowseIds.parseSong("songs|"))
        assertFalse("a queue item's id is its path", BrowseIds.isBrowseId("$music/a.mp3"))
        assertFalse(BrowseIds.isBrowseId(""))
    }

    @Test fun `the root lists the queue, albums, artists, playlists and songs, and a car with 4 tabs drops the queue`() {
        assertEquals(listOf("recent", "albums", "artists", "playlists", "songs"), ids(tree.root()))
        assertEquals(listOf("albums", "artists", "playlists", "songs"), ids(tree.root(childrenLimit = 4)))
        assertEquals(listOf("albums", "artists", "playlists"), ids(tree.root(childrenLimit = 3)))
        assertEquals(ids(tree.root(4)), ids(runBlocking { tree.children("root", 0, 10, childrenLimit = 4) }))
        assertTrue(tree.root().all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
    }

    @Test fun `songs are paged in the database, and a page past the end or an absurd page is empty`() {
        val page2 = children("songs", page = 2, size = 50)!!
        assertEquals(100 to 50, source.lastPage)
        assertEquals(50, page2.size)
        assertEquals("songs|200", page2.first().mediaId)
        assertEquals("Title 100", page2.first().mediaMetadata.title)
        assertEquals(50, children("songs", page = 13, size = 50)!!.size)
        assertEquals(0, children("songs", page = 14, size = 50)!!.size)
        assertEquals(0, children("songs", page = 1, size = Int.MAX_VALUE / 2 - 1)!!.size)
        assertEquals(0, children("songs", page = 0, size = 0)!!.size)
        assertTrue(page2.all { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable == false })
        assertNoPaths(page2)
    }

    @Test fun `albums are named by a track id, never by their key, which can hold a folder path`() {
        val albums = children("albums")!!
        assertEquals(listOf("album/1", "album/20"), ids(albums))
        assertEquals("Unknown artist", albums[1].mediaMetadata.subtitle)
        assertNoPaths(albums)
        val tracks = children("album/20")!!
        assertEquals(listOf("album/20|20"), ids(tracks))
        assertEquals("content://org.seion.browseart/t/20", tracks.single().mediaMetadata.artworkUri.toString())
        assertNoPaths(tracks)
        assertEquals("any track names the album", listOf("album/3|1", "album/3|2"), ids(children("album/3", page = 0, size = 2)))
        assertNull("a gone album", children("album/999"))
        assertNull("not a node", children("$music/Aurora Vale"))
    }

    @Test fun `artists, the unknown artist and names with separators`() {
        val artists = children("artists")!!
        assertEquals(listOf("Aurora Vale", "Unknown artist", "AC/DC | Live"), artists.map { it.mediaMetadata.title.toString() })
        assertEquals("artist/~", artists[1].mediaId)
        assertEquals(ids(untagged.map { MediaItem.Builder().setMediaId("artist/~|${it.id}").build() }), ids(children("artist/~")))
        assertEquals(BrowseIds.Node.Artist("AC/DC | Live"), BrowseIds.parseNode(artists[2].mediaId))
        assertEquals(5, children(artists[0].mediaId)!!.size)
    }

    @Test fun `a playlist lists its available songs, by entry id, and plays them from the tapped one`() {
        val node = children("playlists")!!.single()
        assertEquals("playlist/7", node.mediaId)
        assertEquals("4 songs", node.mediaMetadata.subtitle)
        assertEquals("content://org.seion.browseart/t/3", node.mediaMetadata.artworkUri.toString())
        val songs = children("playlist/7")!!
        assertEquals(listOf("playlist/7|71", "playlist/7|72", "playlist/7|73"), ids(songs))
        assertNull("an unindexed file has no cover id", songs[2].mediaMetadata.artworkUri)
        assertNoPaths(songs + node)

        val play = runBlocking { tree.play("playlist/7|72") }!!
        assertEquals(listOf(harbour[2].path, harbour[0].path, "$music/new/unindexed.mp3"), play.items.map { it.mediaId })
        assertEquals(1, play.start)
        assertFalse(play.sameQueue)
        assertNotNull("queue items are playable files", play.items[0].localConfiguration)
        assertNull("an unavailable entry plays nothing", runBlocking { tree.play("playlist/7|70") })
        assertEquals(0, runBlocking { tree.play("playlist/7") }!!.start)
        assertNull(children("playlist/8"))
    }

    @Test fun `a song plays its list from that song, like a tap in the app`() {
        val album = runBlocking { tree.play("album/1|4") }!!
        assertEquals(harbour.map { it.path }, album.items.map { it.mediaId })
        assertEquals(3, album.start)
        val all = runBlocking { tree.play("songs|150") }!!
        assertEquals(700, all.items.size)
        assertEquals(50, all.start)
        assertEquals(harbour[0].path, runBlocking { tree.single("artist/Aurora%20Vale|1") }!!.mediaId)
        assertNull("a node is not one song", runBlocking { tree.single("album/1") })
        assertNull("a song that is gone", runBlocking { tree.play("album/1|99") })
    }

    @Test fun `search finds songs, plays the results from the tapped one, and a voice request plays its results`() {
        val found = runBlocking { tree.search("song") }
        assertEquals(5, found.size)
        assertEquals("search/song|1", found.first().mediaId)
        assertNoPaths(found)
        val tap = runBlocking { tree.play(found[2].mediaId) }!!
        assertEquals(5, tap.items.size)
        assertEquals(2, tap.start)
        assertEquals(listOf("search/song|1", "search/song|2"), ids(children(BrowseIds.node(BrowseIds.Node.Search("song")), 0, 2)))
        val voice = runBlocking { tree.play("", searchQuery = "title 01") }!!
        assertEquals(10, voice.items.size)
        assertNull("nothing found plays nothing", runBlocking { tree.play("", searchQuery = "zzz") })
        assertTrue(runBlocking { tree.search("  ") }.isEmpty())
    }

    @Test fun `the play queue node lists the queue from the current song in play order and resumes in place`() {
        assertTrue("no queue, no items", children("recent")!!.isEmpty())
        assertNull(runBlocking { tree.play("", searchQuery = "") })
        val items = harbour.map(MediaItems::fromTrack)
        // Shuffled: play order 3, 0, 4, 1, 2 with item 4 playing.
        queue = BrowseQueue(items, listOf(3, 0, 4, 1, 2), current = 4, positionMs = 61_000)
        val recent = children("recent")!!
        assertEquals(listOf("Song 5", "Song 2", "Song 3"), recent.map { it.mediaMetadata.title.toString() })
        assertNoPaths(recent)

        val atCurrent = runBlocking { tree.play(recent[0].mediaId) }!!
        assertTrue(atCurrent.sameQueue)
        assertEquals(4, atCurrent.start)
        assertEquals(61_000, atCurrent.positionMs)
        val later = runBlocking { tree.play(recent[2].mediaId) }!!
        assertEquals(2, later.start)
        assertEquals(0, later.positionMs)
        assertEquals("an empty voice request resumes the queue", 4, runBlocking { tree.play("", searchQuery = " ") }!!.start)

        // The queue changed since the browser listed it: the same file is found again, a gone file plays nothing.
        queue = BrowseQueue(listOf(items[2], items[0], items[1]), listOf(0, 1, 2), current = 0, positionMs = 0)
        assertEquals(0, runBlocking { tree.play(recent[2].mediaId) }!!.start)
        assertNull(runBlocking { tree.play(recent[0].mediaId) })
    }

    @Test fun `items are found by id`() {
        assertEquals("Glass Harbour", runBlocking { tree.item("album/1") }!!.mediaMetadata.title)
        assertEquals("Song 2", runBlocking { tree.item("album/1|2") }!!.mediaMetadata.title)
        assertEquals("Road trip", runBlocking { tree.item("playlist/7") }!!.mediaMetadata.title)
        assertEquals("playlist/7|73", runBlocking { tree.item("playlist/7|73") }!!.mediaId)
        assertNull(runBlocking { tree.item("playlist/7|70") })
        assertEquals("songs", runBlocking { tree.item("songs") }!!.mediaId)
        assertNull(runBlocking { tree.item("$music/x.mp3") })
    }

    @Test fun `a browser that asks for everything gets a long list in sections, and every song stays reachable`() {
        // The platform MediaBrowser protocol without paging asks for page 0 of Int.MAX_VALUE; Media3 cuts that answer
        // at 256 KB (about 530 songs), so a long list comes as sections of GROUP_SIZE.
        val sections = children("songs", page = 0, size = Int.MAX_VALUE)!!
        assertEquals(listOf("songs@0", "songs@1", "songs@2"), ids(sections))
        assertEquals("Title 000 – Title 299", sections[0].mediaMetadata.title)
        assertEquals("100 songs", sections[2].mediaMetadata.subtitle)
        assertTrue(sections.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
        assertEquals(0, children("songs", page = 1, size = Int.MAX_VALUE)!!.size)

        val last = children("songs@2", page = 0, size = Int.MAX_VALUE)!!
        assertEquals(100, last.size)
        assertEquals("songs|700", last.first().mediaId)
        assertNoPaths(last + sections)
        assertEquals(listOf("songs|702", "songs|703"), ids(children("songs@2", page = 1, size = 2)))
        assertNull("past the last section", children("songs@3", 0, 10))
        assertNull("the root has no sections", children("root@0", 0, 10))
        assertEquals("Title 300 – Title 599", runBlocking { tree.item("songs@1") }!!.mediaMetadata.title)
        // A song in a section plays the whole list from that song.
        val play = runBlocking { tree.play(last[5].mediaId) }!!
        assertEquals(700, play.items.size)
        assertEquals(605, play.start)
        // Short lists stay flat.
        assertEquals(listOf("album/1|1"), ids(children("album/1", 0, Int.MAX_VALUE)).take(1))
        assertEquals(BrowseIds.splitGroup("artist/A%40B"), "artist/A%40B" to null)
        assertEquals("songs" to 12, BrowseIds.splitGroup(BrowseIds.group("songs", 12)))
        assertEquals("a song id is never a section", "songs|5@1" to null, BrowseIds.splitGroup("songs|5@1"))
    }
}
