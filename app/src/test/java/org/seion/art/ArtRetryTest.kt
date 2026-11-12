package org.seion.art

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Retrying cover lookups after a transient failure: a lookup that was never answered
 * (offline, metered with "Wi-Fi only", backing off, a network error or a MusicBrainz 503) must not be remembered as
 * "no cover online" for the rest of the process. Only a real answer ("looked up, nothing found") is a miss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArtRetryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val png: ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(0xFF203040.toInt())
                }.compress(Bitmap.CompressFormat.PNG, 100, out)
            }.toByteArray()

    private class Source(var result: () -> ByteArray?) : OnlineCoverSource {
        var calls = 0

        override suspend fun frontCover(artist: String, album: String): ByteArray? {
            calls++
            return result()
        }
    }

    private val wifi = NetworkState(connected = true, unmetered = true)
    private val metered = NetworkState(connected = true, unmetered = false)
    private val offline = NetworkState(connected = false, unmetered = false)
    private val ref = ArtRef("/m/a/01 Song.flac", "/m/a", "Artist", "Album")

    private fun repo(source: Source, network: () -> NetworkState, backoff: Backoff = Backoff()): ArtRepository = ArtRepository(
        AlbumArtResolver(
            embedded = { null },
            listNames = { emptyList() },
            policy = { ArtPolicy(useFolderImages = true, onlineEnabled = true, wifiOnly = true) },
            network = { network() },
            onlineSource = source,
            onlineCache = OnlineCoverCache(tmp.newFolder()),
            backoff = backoff
        ),
        fileExists = { true }
    )

    @Test fun `a lookup skipped while offline is asked again once the network is back`() = runTest {
        var net = offline
        val src = Source { png }
        val art = repo(src, { net })
        assertNull(art.load(ref, 64))
        assertEquals(0, src.calls)
        net = wifi
        assertNotNull("the offline skip was remembered as a miss", art.load(ref, 64))
        assertEquals(1, src.calls)
    }

    @Test fun `a lookup skipped on a metered network with Wi-Fi only is asked again on Wi-Fi`() = runTest {
        var net = metered
        val src = Source { png }
        val art = repo(src, { net })
        assertNull(art.load(ref, 64))
        net = wifi
        assertNotNull(art.load(ref, 64))
    }

    @Test fun `a transient failure (503) is asked again after the back-off`() = runTest {
        var now = 0L
        var fail = true
        val src = Source { if (fail) throw IOException("HTTP 503") else png }
        val art = repo(src, { wifi }, Backoff(baseMs = 30_000, clock = { now }))
        assertNull(art.load(ref, 64))
        assertNull(art.load(ref, 64)) // backing off: not asked
        assertEquals(1, src.calls)
        now = 31_000
        fail = false
        assertNotNull("the failure was remembered as a miss", art.load(ref, 64))
        assertEquals(2, src.calls)
    }

    @Test fun `a real answer with no cover is remembered`() = runTest {
        val src = Source { null }
        val art = repo(src, { wifi })
        assertNull(art.load(ref, 64))
        assertNull(art.load(ref, 64))
        assertEquals(1, src.calls)
    }

    // --- What is on screen comes back by itself ---

    private fun observedRepo(
        lookups: ArrayDeque<OnlineLookup>,
        scope: kotlinx.coroutines.CoroutineScope,
        io: kotlinx.coroutines.CoroutineDispatcher,
        asked: IntArray
    ) = ArtRepository(
        resolveLocal = { null },
        fileExists = { true },
        resolveOnline = {
            asked[0]++
            lookups.removeFirst()
        },
        scope = scope,
        io = io
    )

    private fun hit() = OnlineLookup.Hit(ResolvedArt.Bytes(png, ArtOrigin.ONLINE))

    @Test fun `a cover skipped offline arrives on screen once the network is back`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val asked = IntArray(1)
        val art = observedRepo(ArrayDeque(listOf(OnlineLookup.Unanswered(retryInMs = null), hit())), backgroundScope, io, asked)
        val shown = art.state(ref, 64)
        runCurrent()
        assertEquals(ArtState.Missing(onlinePending = false), shown.value)
        advanceTimeBy(600_000) // offline: no timed retry
        runCurrent()
        assertEquals(1, asked[0])
        art.retryUnanswered() // the network allows lookups again
        runCurrent()
        assertTrue("state: ${shown.value}", shown.value is ArtState.Found)
        assertEquals(2, asked[0])
    }

    @Test fun `after a 503 the cover on screen is asked again when the back-off ends`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val asked = IntArray(1)
        val art = observedRepo(ArrayDeque(listOf(OnlineLookup.Unanswered(retryInMs = 30_000), hit())), backgroundScope, io, asked)
        val shown = art.state(ref, 64)
        runCurrent()
        assertEquals(ArtState.Missing(onlinePending = false), shown.value)
        advanceTimeBy(29_000)
        runCurrent()
        assertEquals(1, asked[0])
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue("state: ${shown.value}", shown.value is ArtState.Found)
        assertEquals(2, asked[0])
    }

    @Test fun `a real miss on screen is not asked again`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val asked = IntArray(1)
        val art = observedRepo(ArrayDeque(listOf(OnlineLookup.Miss)), backgroundScope, io, asked)
        val shown = art.state(ref, 64)
        runCurrent()
        art.retryUnanswered()
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(ArtState.Missing(onlinePending = false), shown.value)
        assertEquals(1, asked[0])
    }

    @Test fun `the media panel's artwork request is asked again too`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val asked = IntArray(1)
        val art = observedRepo(ArrayDeque(listOf(OnlineLookup.Unanswered(retryInMs = null), hit())), backgroundScope, io, asked)
        val hits = mutableListOf<String>()
        backgroundScope.launch(io) { art.onlineHits.collect { hits += it } }
        art.requestOnline(ref, 512)
        runCurrent()
        art.retryUnanswered()
        runCurrent()
        assertEquals(listOf(ref.cacheKey), hits) // the service swaps in the new artwork on this
    }

    @Test fun `with online off a retry makes no network call`() = runTest {
        val src = Source { png }
        val art =
            ArtRepository(
                AlbumArtResolver(
                    embedded = { null },
                    listNames = { emptyList() },
                    policy = { ArtPolicy(useFolderImages = true, onlineEnabled = false, wifiOnly = false) },
                    network = { wifi },
                    onlineSource = src,
                    onlineCache = OnlineCoverCache(tmp.newFolder())
                ),
                fileExists = { true }
            )
        assertNull(art.load(ref, 64))
        art.retryUnanswered()
        assertNull(art.load(ref, 64))
        assertEquals(0, src.calls)
    }

    @Test fun `the gate's openings`() = runTest {
        assertEquals(1, flowOf(false, false, true, true).openings().toList().size)
        assertEquals(0, flowOf(true, true).openings().toList().size) // online from the start: nothing was skipped
        assertEquals(1, flowOf(true, false, true).openings().toList().size)
        assertEquals(2, flowOf(false, true, false, true).openings().toList().size)
    }

    @Test fun `the resolver tells a miss from an unanswered lookup`() = runTest {
        var net = offline
        var now = 0L
        var mode = "none"
        val src = Source { if (mode == "fail") throw IOException("HTTP 503") else null }
        val r =
            AlbumArtResolver(
                embedded = { null },
                listNames = { emptyList() },
                policy = { ArtPolicy(useFolderImages = true, onlineEnabled = true, wifiOnly = true) },
                network = { net },
                onlineSource = src,
                onlineCache = OnlineCoverCache(tmp.newFolder()),
                backoff = Backoff(baseMs = 30_000, clock = { now })
            )
        assertEquals(OnlineLookup.Unanswered(null), r.lookupOnline(ref)) // offline: waits for the network
        net = metered
        assertEquals(OnlineLookup.Unanswered(null), r.lookupOnline(ref)) // Wi-Fi only
        net = wifi
        mode = "fail"
        assertEquals(OnlineLookup.Unanswered(30_000), r.lookupOnline(ref)) // a 503: when the back-off ends
        now = 10_000
        assertEquals(OnlineLookup.Unanswered(20_000), r.lookupOnline(ref)) // backing off: the rest of it
        now = 31_000
        mode = "none"
        assertEquals(OnlineLookup.Miss, r.lookupOnline(ref)) // an answer
        assertEquals(OnlineLookup.Miss, r.lookupOnline(ref)) // and remembered on disk
        assertEquals(OnlineLookup.Miss, r.lookupOnline(ref.copy(artist = null))) // no names: nothing to ask, ever
    }
}
