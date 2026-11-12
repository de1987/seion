package org.seion.art

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Turning the online switch off ends the lookups in flight and closes the idle connections, and a
 * lookup waiting in a rate limiter at that moment sends nothing more ([OnlineHttp.gate]).
 */
class OnlineSwitchOffTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @Volatile private var online = true

    // A short read timeout: a call that is NOT cancelled fails the test in seconds instead of hanging.
    private val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private val cache by lazy { OnlineCoverCache(tmp.newFolder("online-covers")) }

    private fun resolver(client: OkHttpClient = this.client, limiter: RateLimiter = RateLimiter(0)): AlbumArtResolver {
        val base = server.url("/").toString().trimEnd('/')
        val chain = ChainCoverSource(listOf(NamedSource("musicbrainz", MusicBrainzCoverSource(client, limiter, base, "$base/caa"))))
        return AlbumArtResolver(
            embedded = { null },
            listNames = { emptyList() },
            policy = { ArtPolicy(useFolderImages = true, onlineEnabled = online, wifiOnly = false) },
            network = { NetworkState(connected = true, unmetered = true) },
            onlineSource = chain,
            onlineCache = cache
        )
    }

    private val ref = ArtRef("/m/Rumours/02 Dreams.flac", "/m/Rumours", "Fleetwood Mac", "Rumours")

    private fun json(body: String) = MockResponse.Builder().code(200).body(body).build()

    @Test fun `switching off cancels an in-flight cover lookup and caches no miss`() = runBlocking {
        val resolver = resolver()
        // The release-group search hangs: the switch goes off while it is in flight.
        server.enqueue(MockResponse.Builder().code(200).body("""{"release-groups":[]}""").headersDelay(5, TimeUnit.SECONDS).build())
        val lookup = async(Dispatchers.IO) { resolver.resolveOnline(ref) }
        assertNotNull("the search reached the server", server.takeRequest(5, TimeUnit.SECONDS))

        online = false
        val off = System.nanoTime()
        OnlineHttp.stopAll(client)

        assertNull(withTimeout(10_000) { lookup.await() })
        assertTrue("ended by the cancellation, not by the read timeout", System.nanoTime() - off < 1_500_000_000L)
        assertEquals("nothing more is asked after the switch went off", 1, server.requestCount)
        assertEquals("a cancelled lookup is no answer: no miss is cached", CachedCover.Unknown, cache.get("Fleetwood Mac", "Rumours"))
        assertEquals("idle connections are closed", 0, client.connectionPool.connectionCount())

        // Back on: the next lookup goes out at once (a cancellation is not a failure, so nothing backs off).
        online = true
        server.enqueue(
            json(
                """{"release-groups":[{"id":"rg-1","score":100,"title":"Rumours","primary-type":"Album",
                "artist-credit":[{"name":"Fleetwood Mac","joinphrase":""}]}]}"""
            )
        )
        server.enqueue(MockResponse.Builder().code(200).body("IMG").build())
        val found = withTimeout(10_000) { async(Dispatchers.IO) { resolver.resolveOnline(ref) }.await() }
        assertTrue("found: $found", found is ResolvedArt.FileImage)
        assertTrue(cache.get("Fleetwood Mac", "Rumours") is CachedCover.Hit)
    }

    /**
     * A limiter whose next wait suspends until [release]: the lookup sits between two requests, in no HTTP call, like a
     * run waiting between MusicBrainz's strict search and its alias route. [waiting] completes when it got there.
     */
    private class HeldLimiter {
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        // The first request never waits; the next one does.
        val limiter = RateLimiter(1_000, clock = { 0L }, sleep = {
            waiting.complete(Unit)
            release.await()
        })
    }

    @Test fun `switching off while a lookup waits in the rate limiter sends no further request and caches no miss`() = runBlocking {
        val gated = OnlineHttp.defaultClient(allowed = { online })
        val held = HeldLimiter()
        val resolver = resolver(gated, held.limiter)
        // The strict search answers "none", then the alias route's artist search waits for its turn.
        server.enqueue(json("""{"release-groups":[]}"""))
        server.enqueue(json("""{"artists":[]}"""))
        val lookup = async(Dispatchers.IO) { resolver.resolveOnline(ref) }
        withTimeout(10_000) { held.waiting.await() }
        assertEquals("the strict search went out before the switch went off", 1, server.requestCount)

        online = false
        OnlineHttp.stopAll(gated)
        held.release.complete(Unit)

        assertNull(withTimeout(10_000) { lookup.await() })
        assertEquals("no request goes out after the switch went off", 1, server.requestCount)
        assertEquals("a cancelled lookup is no answer: no miss is cached", CachedCover.Unknown, cache.get("Fleetwood Mac", "Rumours"))
    }

    @Test fun `the gate lets requests through while the switch is on`() = runBlocking {
        val gated = OnlineHttp.defaultClient(allowed = { online })
        server.enqueue(json("""{"release-groups":[]}"""))
        server.enqueue(json("""{"artists":[]}"""))
        assertNull(withTimeout(10_000) { async(Dispatchers.IO) { resolver(gated).resolveOnline(ref) }.await() })
        assertEquals(2, server.requestCount)
        assertEquals("MusicBrainz answered: a real miss", CachedCover.Miss, cache.get("Fleetwood Mac", "Rumours"))
    }
}
