package org.seion.art

import java.io.IOException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.seion.BuildConfig

class OnlineSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private fun source(limiter: RateLimiter = RateLimiter(0)) = MusicBrainzCoverSource(
        limiter = limiter,
        musicBrainzBase = server.url("/").toString().trimEnd('/'),
        coverArtBase = server.url("/caa").toString()
    )

    private fun rg(id: String, title: String, artist: String, score: Int, type: String? = null) =
        """{"id":"$id","score":$score,"title":"$title"""" + (type?.let { ""","primary-type":"$it"""" } ?: "") +
            ""","artist-credit":[{"name":"$artist","joinphrase":""}]}"""

    private fun json(body: String) = MockResponse.Builder().code(200).body(body).build()

    @Test fun `searches by artist and album, then fetches the front cover`() = runTest {
        server.enqueue(
            json(
                """{"release-groups":[${rg(
                    "rg-1",
                    "Kind of Blue",
                    "Miles Davis",
                    100,
                    "Album"
                )},${rg("rg-2", "Kind of Blue Live", "Miles Davis", 40)}]}"""
            )
        )
        server.enqueue(MockResponse.Builder().code(200).body("IMG").build())
        val bytes = source().frontCover("Miles Davis", "Kind of Blue")
        assertArrayEquals("IMG".toByteArray(), bytes)
        val search = server.takeRequest()
        val url = search.url
        assertEquals("/ws/2/release-group/", url.encodedPath)
        assertEquals("releasegroup:\"Kind of Blue\" AND artist:\"Miles Davis\"", url.queryParameter("query"))
        assertEquals("json", url.queryParameter("fmt"))
        assertEquals(setOf("query", "fmt", "limit"), url.queryParameterNames)
        val cover = server.takeRequest()
        assertEquals("/caa/release-group/rg-1/front-500", cover.url.encodedPath)
    }

    @Test fun `user agent names the app and its version, never an e-mail`() = runTest {
        server.enqueue(json("""{"release-groups":[]}"""))
        server.enqueue(json("""{"artists":[]}"""))
        source().frontCover("A", "B")
        val ua = server.takeRequest().headers["User-Agent"]
        assertEquals(OnlineHttp.USER_AGENT, ua)
        assertTrue(Regex("""Seion/\d+\.\d+\.\d+ \( https://\S+ \)""").matches(ua!!))
        assertTrue(ua.contains(BuildConfig.PROJECT_URL))
        assertFalse(ua.contains("@"))
    }

    @Test fun `user agent always carries the project URL in the MusicBrainz format`() {
        assertEquals("Seion/1.2.3 ( https://example.org/seion )", OnlineHttp.userAgent("1.2.3-debug", "https://example.org/seion"))
        assertEquals("Seion/1.2.3 ( https://example.org/seion )", OnlineHttp.userAgent("1.2.3", "https://example.org/seion"))
    }

    @Test fun `the build has a project URL`() {
        assertTrue(BuildConfig.PROJECT_URL, Regex("""https://\S+""").matches(BuildConfig.PROJECT_URL))
    }

    @Test fun `tries the next candidate when a release has no cover, low scores are ignored`() = runTest {
        server.enqueue(
            json(
                """{"release-groups":[${rg(
                    "a",
                    "Album Y",
                    "Artist X",
                    100
                )},${rg("b", "Album Y", "Artist X", 95)},${rg("c", "Other", "Someone", 10)}]}"""
            )
        )
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(200).body("B").build())
        assertArrayEquals("B".toByteArray(), source().frontCover("Artist X", "Album Y"))
        assertEquals(3, server.requestCount)
    }

    @Test fun `no matching release is a miss (null), not an error`() = runTest {
        server.enqueue(json("""{"release-groups":[${rg("a", "Something Else", "Nobody", 20)}]}"""))
        server.enqueue(json("""{"artists":[{"id":"low","score":40}]}"""))
        assertNull(source().frontCover("x", "y"))
        assertEquals(2, server.requestCount) // strict search, then the alias lookup (score too low: no group search)
    }

    @Test fun `the query uses the base names, never the raw decorated tags`() = runTest {
        server.enqueue(json("""{"release-groups":[]}"""))
        server.enqueue(json("""{"artists":[]}"""))
        source().frontCover("Fleetwood Mac feat. Someone", "Rumours (Remastered)")
        assertEquals("releasegroup:\"Rumours\" AND artist:\"Fleetwood Mac\"", server.takeRequest().url.queryParameter("query"))
    }

    @Test fun `a score-100 group whose names fail the match is not fetched`() = runTest {
        server.enqueue(json("""{"release-groups":[${rg("box", "Cowboy Bebop LP-Box", "Seatbelts", 100, "Album")}]}"""))
        server.enqueue(json("""{"artists":[]}"""))
        assertNull(source().frontCover("Seatbelts", "Cowboy Bebop"))
        assertEquals(2, server.requestCount)
        assertEquals("/ws/2/release-group/", server.takeRequest().url.encodedPath)
        assertEquals("/ws/2/artist/", server.takeRequest().url.encodedPath)
    }

    @Test fun `the alias route runs only after a strict miss`() = runTest {
        server.enqueue(json("""{"release-groups":[${rg("x", "Another Album", "Sheena Ringo", 100)}]}"""))
        server.enqueue(json("""{"artists":[{"id":"art-1","score":100}]}"""))
        server.enqueue(json("""{"release-groups":[${rg("muzai", "無罪モラトリアム", "椎名林檎", 100, "Album")}]}"""))
        server.enqueue(MockResponse.Builder().code(200).body("COVER").build())
        // A romanised title whose MusicBrainz group carries the original one: the pseudo-release matches "release:".
        val bytes = source().frontCover("Sheena Ringo", "無罪モラトリアム")
        assertArrayEquals("COVER".toByteArray(), bytes)
        server.takeRequest()
        val artist = server.takeRequest().url
        assertEquals("/ws/2/artist/", artist.encodedPath)
        assertEquals("artist:\"Sheena Ringo\" OR alias:\"Sheena Ringo\" OR sortname:\"Sheena Ringo\"", artist.queryParameter("query"))
        val groups = server.takeRequest().url
        assertEquals("arid:art-1 AND (releasegroup:\"無罪モラトリアム\" OR release:\"無罪モラトリアム\")", groups.queryParameter("query"))
        assertEquals("/caa/release-group/muzai/front-500", server.takeRequest().url.encodedPath)
    }

    @Test fun `the alias route accepts a group whose pseudo-release carries the romanised title`() = runTest {
        server.enqueue(json("""{"release-groups":[]}"""))
        server.enqueue(json("""{"artists":[{"id":"ringo","score":100}]}"""))
        server.enqueue(
            json(
                """{"release-groups":[{"id":"muzai","score":100,"title":"無罪モラトリアム","primary-type":"Album",
                "artist-credit":[{"name":"椎名林檎","joinphrase":""}],
                "releases":[{"title":"Muzai Moratorium","status":"Pseudo-Release"},{"title":"無罪モラトリアム","status":"Official"}]}]}"""
            )
        )
        server.enqueue(MockResponse.Builder().code(200).body("MUZAI").build())
        assertArrayEquals("MUZAI".toByteArray(), source().frontCover("Sheena Ringo", "Muzai Moratorium"))
        assertEquals(4, server.requestCount)
    }

    @Test fun `the alias route still rejects a group none of whose titles match`() = runTest {
        server.enqueue(json("""{"release-groups":[]}"""))
        server.enqueue(json("""{"artists":[{"id":"ringo","score":100}]}"""))
        server.enqueue(
            json(
                """{"release-groups":[{"id":"other","score":90,"title":"勝訴ストリップ","artist-credit":[{"name":"椎名林檎"}],
                "releases":[{"title":"Shouso Strip"}]}]}"""
            )
        )
        assertNull(source().frontCover("Sheena Ringo", "Muzai Moratorium"))
        assertEquals(3, server.requestCount)
    }

    @Test fun `a strict hit never asks the alias route`() = runTest {
        server.enqueue(json("""{"release-groups":[${rg("ok", "Rumours", "Fleetwood Mac", 100)}]}"""))
        server.enqueue(MockResponse.Builder().code(404).build())
        assertNull(source().frontCover("Fleetwood Mac", "Rumours"))
        assertEquals(2, server.requestCount)
    }

    @Test fun `an old empty miss reads as unknown, a current one as a miss`() {
        val dir = tmp.newFolder()
        val cache = OnlineCoverCache(dir)
        java.io.File(dir, OnlineCoverCache.key("A", "B") + ".miss").writeText("")
        assertEquals(CachedCover.Unknown, cache.get("A", "B"))
        cache.putMiss("A", "B")
        assertEquals("3", java.io.File(dir, OnlineCoverCache.key("A", "B") + ".miss").readText())
        assertEquals(CachedCover.Miss, cache.get("A", "B"))
    }

    @Test fun `at most two covers are fetched for passing groups`() = runTest {
        val groups = (1..4).joinToString(",") { rg("g$it", "Rumours", "Fleetwood Mac", 100, "Album") }
        server.enqueue(json("""{"release-groups":[$groups]}"""))
        repeat(2) { server.enqueue(MockResponse.Builder().code(404).build()) }
        assertNull(source().frontCover("Fleetwood Mac", "Rumours"))
        assertEquals(3, server.requestCount)
    }

    @Test(expected = IOException::class)
    fun `a 503 (rate limited) is a transient failure`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())
        source().frontCover("x", "y")
    }

    @Test fun `query phrases are escaped`() {
        assertEquals(
            "releasegroup:\"C\\\\D\" AND artist:\"A \\\"B\\\"\"",
            MusicBrainzCoverSource.luceneQuery("A \"B\"", "C\\D")
        )
    }

    @Test fun `rate limiter spaces requests at least one second apart`() = runTest {
        var now = 10_000L
        val sleeps = mutableListOf<Long>()
        val limiter =
            RateLimiter(1_000, clock = { now }, sleep = { ms ->
                sleeps += ms
                now += ms
            })
        val starts = mutableListOf<Long>()
        repeat(3) {
            limiter.run { starts += now }
            now += 100 // each request takes 100 ms
        }
        assertEquals(listOf(10_000L, 11_000L, 12_000L), starts)
        assertEquals(listOf(900L, 900L), sleeps)
    }

    @Test fun `cache keeps hits, expires misses after the TTL, and clears`() {
        var now = 1_000_000_000L
        val cache = OnlineCoverCache(tmp.newFolder(), missTtlMs = 1_000, clock = { now })
        assertEquals(CachedCover.Unknown, cache.get("A", "B"))
        cache.putMiss("A", "B")
        assertEquals(CachedCover.Miss, cache.get("a", "b"))
        now += 2_000
        assertEquals(CachedCover.Unknown, cache.get("A", "B"))
        cache.putHit("A", "B", byteArrayOf(1))
        assertTrue(cache.get("A", "B") is CachedCover.Hit)
        assertTrue(cache.clear() >= 1)
        assertEquals(CachedCover.Unknown, cache.get("A", "B"))
    }

    @Test fun `cache keys never contain the names`() {
        val k = OnlineCoverCache.key("Miles Davis", "Kind of Blue")
        assertFalse(k.contains("miles", ignoreCase = true))
        assertEquals(40, k.length)
    }

    @Test fun `gate decisions`() {
        val wifi = NetworkState(connected = true, unmetered = true)
        val metered = NetworkState(connected = true, unmetered = false)
        assertEquals(OnlineDecision.DISABLED, OnlineGate.decide(false, false, wifi))
        assertEquals(OnlineDecision.OFFLINE, OnlineGate.decide(true, false, NetworkState(connected = false, unmetered = false)))
        assertEquals(OnlineDecision.METERED, OnlineGate.decide(true, true, metered))
        assertEquals(OnlineDecision.ALLOWED, OnlineGate.decide(true, false, metered))
        assertEquals(OnlineDecision.ALLOWED, OnlineGate.decide(true, true, wifi))
    }

    @Test fun `albums are preferred over singles`() {
        val ids =
            MusicBrainzCoverSource.parseReleaseGroups(
                """{"release-groups":[${rg("single", "T", "A", 100, "Single")},${rg("album", "T", "A", 95, "Album")}]}"""
            ).map { it.id }
        assertEquals(listOf("album", "single"), ids)
    }
}
