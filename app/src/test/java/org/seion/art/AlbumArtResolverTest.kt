package org.seion.art

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlbumArtResolverTest {
    @get:Rule val tmp = TemporaryFolder()

    private class CountingSource(var result: ByteArray? = byteArrayOf(1, 2, 3), var fail: Boolean = false) : OnlineCoverSource {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun frontCover(artist: String, album: String): ByteArray? {
            calls += artist to album
            if (fail) throw IOException("HTTP 503")
            return result
        }
    }

    private val wifi = NetworkState(connected = true, unmetered = true)
    private val metered = NetworkState(connected = true, unmetered = false)
    private val offline = NetworkState(connected = false, unmetered = false)

    private fun resolver(
        source: OnlineCoverSource,
        embedded: ByteArray? = null,
        folderNames: List<String> = emptyList(),
        policy: ArtPolicy = ArtPolicy(useFolderImages = true, onlineEnabled = true, wifiOnly = true),
        network: NetworkState = wifi,
        cache: OnlineCoverCache = OnlineCoverCache(tmp.newFolder()),
        backoff: Backoff = Backoff()
    ) = AlbumArtResolver(
        embedded = { embedded },
        listNames = { folderNames },
        policy = { policy },
        network = { network },
        onlineSource = source,
        onlineCache = cache,
        backoff = backoff
    )

    private val ref = ArtRef("/m/a/01.flac", "/m/a", "Artist", "Album")

    @Test fun `embedded art comes first, before folder images and online`() = runTest {
        val src = CountingSource()
        val art = resolver(src, embedded = byteArrayOf(9), folderNames = listOf("cover.jpg")).resolve(ref)
        assertEquals(ArtOrigin.EMBEDDED, art?.origin)
        assertTrue(src.calls.isEmpty())
    }

    @Test fun `folder image is second and skips online`() = runTest {
        val src = CountingSource()
        val art = resolver(src, folderNames = listOf("01.flac", "Folder.jpg")).resolve(ref)
        assertEquals(ArtOrigin.FOLDER, art?.origin)
        assertEquals("/m/a/Folder.jpg", (art as ResolvedArt.FileImage).file.path)
        assertTrue(src.calls.isEmpty())
    }

    @Test fun `folder images can be turned off`() = runTest {
        val src = CountingSource()
        val policy = ArtPolicy(useFolderImages = false, onlineEnabled = true, wifiOnly = true)
        val art = resolver(src, folderNames = listOf("cover.jpg"), policy = policy).resolve(ref)
        assertEquals(ArtOrigin.ONLINE, art?.origin)
        assertEquals(listOf("Artist" to "Album"), src.calls)
    }

    @Test fun `online off makes zero online calls`() = runTest {
        val src = CountingSource()
        val policy = ArtPolicy(useFolderImages = true, onlineEnabled = false, wifiOnly = false)
        assertNull(resolver(src, policy = policy).resolve(ref))
        assertTrue(src.calls.isEmpty())
    }

    @Test fun `wifi-only on a metered network makes zero online calls`() = runTest {
        val src = CountingSource()
        assertNull(resolver(src, network = metered).resolve(ref))
        assertTrue(src.calls.isEmpty())
    }

    @Test fun `metered is fine when wifi-only is off`() = runTest {
        val src = CountingSource()
        val policy = ArtPolicy(useFolderImages = true, onlineEnabled = true, wifiOnly = false)
        assertEquals(ArtOrigin.ONLINE, resolver(src, policy = policy, network = metered).resolve(ref)?.origin)
        assertEquals(1, src.calls.size)
    }

    @Test fun `offline makes no call but still shows a cached cover`() = runTest {
        val cache = OnlineCoverCache(tmp.newFolder())
        cache.putHit("Artist", "Album", byteArrayOf(7))
        val src = CountingSource()
        assertEquals(ArtOrigin.ONLINE, resolver(src, network = offline, cache = cache).resolve(ref)?.origin)
        assertTrue(src.calls.isEmpty())
    }

    @Test fun `online off never reads the online cache either`() = runTest {
        val cache = OnlineCoverCache(tmp.newFolder())
        cache.putHit("Artist", "Album", byteArrayOf(7))
        val policy = ArtPolicy(useFolderImages = true, onlineEnabled = false, wifiOnly = true)
        assertNull(resolver(CountingSource(), policy = policy, cache = cache).resolve(ref))
    }

    @Test fun `a miss is cached and not asked again`() = runTest {
        val src = CountingSource(result = null)
        val r = resolver(src)
        assertNull(r.resolve(ref))
        assertNull(r.resolve(ref))
        assertEquals(1, src.calls.size)
    }

    @Test fun `a hit is cached on disk`() = runTest {
        val src = CountingSource()
        val r = resolver(src)
        r.resolve(ref)
        val again = r.resolve(ref)
        assertEquals(ArtOrigin.ONLINE, again?.origin)
        assertEquals(1, src.calls.size)
    }

    @Test fun `only artist and album names are sent, and nothing without both`() = runTest {
        val src = CountingSource()
        val r = resolver(src)
        assertNull(r.resolve(ArtRef("/m/x.mp3", "/m", null, "Album")))
        assertNull(r.resolve(ArtRef("/m/x.mp3", "/m", "Artist", "  ")))
        assertTrue(src.calls.isEmpty())
        r.resolve(ref)
        assertEquals(listOf("Artist" to "Album"), src.calls)
    }

    @Test fun `a transient failure backs off and is not cached as a miss`() = runTest {
        var now = 0L
        val src = CountingSource(fail = true)
        val r = resolver(src, backoff = Backoff(baseMs = 30_000, clock = { now }))
        assertNull(r.resolve(ref))
        assertNull(r.resolve(ref)) // within the back-off window: no call
        assertEquals(1, src.calls.size)
        now = 31_000
        src.fail = false
        assertEquals(ArtOrigin.ONLINE, r.resolve(ref)?.origin)
        assertEquals(2, src.calls.size)
    }

    @Test fun `backoff doubles and caps`() {
        var now = 0L
        val b = Backoff(baseMs = 1_000, maxMs = 5_000, clock = { now })
        assertTrue(b.allowed())
        b.failure()
        assertEquals(1_000, b.currentDelayMs)
        b.failure()
        assertEquals(2_000, b.currentDelayMs)
        b.failure()
        b.failure()
        assertEquals(5_000, b.currentDelayMs)
        now = 4_999
        assertTrue(!b.allowed())
        b.success()
        assertTrue(b.allowed())
    }

    @Test fun `concurrent lookups of the same album make one online call`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val slow =
            object : OnlineCoverSource {
                override suspend fun frontCover(artist: String, album: String): ByteArray? {
                    calls++
                    gate.await()
                    return byteArrayOf(1)
                }
            }
        val r = resolver(slow)
        // Three surfaces (list, grid, now playing) ask for the same album at once.
        val jobs = List(3) { async { r.resolve(ref) } }
        yield()
        gate.complete(Unit)
        val results = jobs.map { it.await() }
        assertEquals(1, calls)
        assertTrue(results.all { it?.origin == ArtOrigin.ONLINE })
    }
}
