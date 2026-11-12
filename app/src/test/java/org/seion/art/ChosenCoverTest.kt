package org.seion.art

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pick or replace a cover: the override's key, its store, and that it wins everywhere the resolver is asked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChosenCoverTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun png(color: Int, px: Int = 8): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()

    private class Source : OnlineCoverSource {
        var calls = 0

        override suspend fun frontCover(artist: String, album: String): ByteArray? {
            calls++
            return byteArrayOf(1)
        }
    }

    private val wifi = NetworkState(connected = true, unmetered = true)

    // The same album as every screen sees it: a track row / queue item, and the album card.
    private val trackRef = ArtRef("/m/Kind of Blue/01 So What.flac", "/m/Kind of Blue", "Miles Davis", "Kind of Blue")
    private val albumRef = ArtRef("/m/Kind of Blue/02 Freddie.flac", "/m/Kind of Blue", "Miles Davis", "Kind of Blue")

    @Test fun `the key is the folder plus the album tag, stable across refs, case and a trailing slash`() {
        val k = CoverOverrides.key("/m/Kind of Blue", "Kind of Blue")
        assertEquals(k, CoverOverrides.keyOf(trackRef))
        assertEquals(k, CoverOverrides.keyOf(albumRef))
        assertEquals(k, CoverOverrides.key("/m/Kind of Blue/", " kind of blue "))
        // Pinned: a chosen cover must survive app updates, so the key never changes silently.
        assertEquals("03dcb636f6824ae97515bb306ce87631da8f28bd", CoverOverrides.key("/m/a", "Album"))
        assertEquals(40, k.length)
        assertTrue(k.all { it in '0'..'9' || it in 'a'..'f' }) // no names or paths on disk
        // Another folder (a different copy) or another album in the same folder is another cover.
        assertNotEquals(k, CoverOverrides.key("/m/Kind of Blue (2009)", "Kind of Blue"))
        assertNotEquals(k, CoverOverrides.key("/m/Kind of Blue", "Sketches of Spain"))
        // An untagged folder album: the folder alone, not the same as a tagged album in it.
        assertNotEquals(CoverOverrides.key("/m/x", null), CoverOverrides.key("/m/x", "X"))
        assertEquals(CoverOverrides.key("/m/x", null), CoverOverrides.keyOf(ArtRef("/m/x/a.mp3", "/m/x", null, null)))
        assertNull(CoverOverrides.keyOf(ArtRef("/m/x/a.mp3", null, null, null)))
    }

    @Test fun `the store keeps one file per folder, survives a restart, and removes it again`() {
        val dir = tmp.newFolder("covers")
        val store = CoverOverrides(dir)
        val folders = listOf("/m/Album/CD1", "/m/Album/CD2")
        assertEquals(2, store.set(folders, "Album", byteArrayOf(7)).size)
        assertTrue(store.file("/m/Album/CD2", "album")!!.isFile)
        // A new process (restart, after a rescan) reads the directory.
        val again = CoverOverrides(dir)
        assertTrue(again.has(listOf("/m/Album/CD1"), "Album"))
        assertEquals(
            setOf("${CoverOverrides.key("/m/Album/CD1", "Album")}.jpg", "${CoverOverrides.key("/m/Album/CD2", "Album")}.jpg"),
            dir.list()!!.toSet()
        )
        assertEquals(2, again.remove(folders, "Album").size)
        assertFalse(again.has(folders, "Album"))
        assertNull(again.file("/m/Album/CD1", "Album"))
        assertTrue(dir.list()!!.isEmpty())
    }

    private fun resolver(
        overrides: CoverOverrides,
        source: Source,
        embedded: ByteArray? = byteArrayOf(9),
        cache: OnlineCoverCache = OnlineCoverCache(tmp.newFolder()),
        online: Boolean = true,
        folderNames: List<String> = listOf("01 So What.flac", "cover.jpg")
    ) = AlbumArtResolver(
        embedded = { embedded },
        listNames = { folderNames },
        policy = { ArtPolicy(useFolderImages = true, onlineEnabled = online, wifiOnly = true) },
        network = { wifi },
        onlineSource = source,
        onlineCache = cache,
        chosen = overrides::file
    )

    @Test fun `a chosen cover wins over embedded art, folder images and online, and goes back on removal`() = runTest {
        val overrides = CoverOverrides(tmp.newFolder("covers"))
        val src = Source()
        val r = resolver(overrides, src)
        assertEquals(ArtOrigin.EMBEDDED, r.resolve(trackRef)?.origin)
        overrides.set(listOf("/m/Kind of Blue"), "Kind of Blue", png(0xFF00FF00.toInt()))
        for (ref in listOf(trackRef, albumRef, ArtRef(null, "/m/Kind of Blue", "Miles Davis", "Kind of Blue"))) {
            val art = r.resolve(ref)
            assertEquals(ArtOrigin.CHOSEN, art?.origin)
            assertEquals(overrides.file("/m/Kind of Blue", "Kind of Blue"), (art as ResolvedArt.FileImage).file)
        }
        // Over an online cover too, and with no network call: the local phase answers.
        val noLocal = resolver(overrides, src, embedded = null)
        assertEquals(ArtOrigin.CHOSEN, noLocal.resolve(trackRef)?.origin)
        assertEquals(0, src.calls)
        assertEquals(CoverSource(ArtOrigin.CHOSEN), r.describe(trackRef))
        overrides.remove(listOf("/m/Kind of Blue"), "Kind of Blue")
        assertEquals(ArtOrigin.EMBEDDED, r.resolve(trackRef)?.origin)
    }

    @Test fun `the repository shows the chosen cover on every screen after an invalidate`() = runTest {
        val overrides = CoverOverrides(tmp.newFolder("covers"))
        val r = resolver(overrides, Source(), embedded = png(0xFFFF0000.toInt()))
        val io = StandardTestDispatcher(testScheduler)
        val repo = ArtRepository(r::resolveLocal, { true }, r::lookupOnline, backgroundScope, io)
        val np = repo.state(trackRef, 720) // Now playing and the ambient observe this
        runCurrent()
        assertEquals(ArtOrigin.EMBEDDED, (np.value as ArtState.Found).origin)
        val red = repo.load(albumRef, 160)!!.getPixel(0, 0)
        overrides.set(listOf("/m/Kind of Blue"), "Kind of Blue", png(0xFF00FF00.toInt()))
        repo.invalidate() // AppContainer.setChosenCover
        runCurrent()
        val found = np.value as ArtState.Found
        assertEquals(ArtOrigin.CHOSEN, found.origin)
        assertEquals(0xFF00FF00.toInt(), found.bitmap.getPixel(0, 0))
        val green = repo.load(albumRef, 160)!!.getPixel(0, 0) // a row, the album card, the panel (loadLocal)
        assertEquals(0xFF00FF00.toInt(), green)
        assertNotEquals(red, green)
        assertEquals(0xFF00FF00.toInt(), repo.loadLocal(trackRef, 512)!!.getPixel(0, 0))
    }

    @Test fun `details say which kind of source supplied an online cover, and read no cache with online off`() = runTest {
        val cache = OnlineCoverCache(tmp.newFolder("online"))
        val overrides = CoverOverrides(tmp.newFolder("covers"))
        val src = Source()
        val r = resolver(overrides, src, embedded = null, cache = cache, folderNames = emptyList())
        val bare = ArtRef("/m/b/01.flac", "/m/b", "Artist", "Album")
        assertEquals(CoverSource(null), r.describe(bare))
        cache.putHit("Artist", "Album", byteArrayOf(1), "musicbrainz")
        assertEquals(CoverSource(ArtOrigin.ONLINE, OnlineKind.OPEN_DATABASE), r.describe(bare))
        cache.putHit("Artist", "Album", byteArrayOf(1), "elsewhere") // a source name the chain does not use still loads, as plain "Online"
        assertEquals(CoverSource(ArtOrigin.ONLINE, OnlineKind.UNKNOWN), r.describe(bare))
        cache.putHit("Artist", "Album", byteArrayOf(1)) // a cover downloaded before the user chose one
        assertEquals(CoverSource(ArtOrigin.ONLINE, OnlineKind.UNKNOWN), r.describe(bare))
        val off = resolver(overrides, src, embedded = null, cache = cache, online = false, folderNames = emptyList())
        assertEquals(CoverSource(null), off.describe(bare))
        assertEquals(0, src.calls)
    }

    @Test fun `the chain names the source that answered, and the cache keeps it beside the cover`() = runTest {
        val cache = OnlineCoverCache(tmp.newFolder("online"))
        val none = object : OnlineCoverSource {
            override suspend fun frontCover(artist: String, album: String): ByteArray? = null
        }
        val found = object : OnlineCoverSource {
            override suspend fun frontCover(artist: String, album: String): ByteArray? = byteArrayOf(5)
        }
        val chain = ChainCoverSource(listOf(NamedSource("first", none), NamedSource("musicbrainz", found)))
        assertEquals("musicbrainz", chain.sourcedCover("A", "B")!!.source)
        val r = AlbumArtResolver(
            embedded = { null },
            listNames = { emptyList() },
            policy = { ArtPolicy(useFolderImages = true, onlineEnabled = true, wifiOnly = true) },
            network = { wifi },
            onlineSource = chain,
            onlineCache = cache
        )
        assertTrue(r.lookupOnline(ArtRef("/m/c/1.flac", "/m/c", "A", "B")) is OnlineLookup.Hit)
        assertEquals("musicbrainz", cache.source("A", "B"))
        // The note is not a cache entry of its own.
        assertEquals(OnlineCoverCache.Stats(covers = 1, entries = 1, bytes = 1), cache.stats())
        assertEquals(1, cache.clear())
        assertNull(cache.source("A", "B"))
        assertEquals(OnlineKind.OPEN_DATABASE, OnlineKind.of("musicbrainz"))
        assertEquals(OnlineKind.UNKNOWN, OnlineKind.of("first"))
        assertEquals(OnlineKind.UNKNOWN, OnlineKind.of(null))
    }

    @Test fun `a picked picture is scaled to at most 1200 px and cut to its centred square`() {
        assertEquals(listOf(1200, 1600, 0, 200, 1200), CoverImport.squareCrop(3000, 4000).toList())
        assertEquals(listOf(800, 600, 100, 0, 600), CoverImport.squareCrop(800, 600).toList())
        assertEquals(listOf(1200, 1200, 0, 0, 1200), CoverImport.squareCrop(1200, 1200).toList())
    }
}
