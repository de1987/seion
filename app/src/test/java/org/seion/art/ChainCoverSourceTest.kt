package org.seion.art

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChainCoverSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Fake(val answer: suspend () -> ByteArray?) : OnlineCoverSource {
        var calls = 0

        override suspend fun frontCover(artist: String, album: String): ByteArray? {
            calls++
            return answer()
        }
    }

    private fun blocked() = Backoff(clock = { 0L }).apply { failure() }

    @Test fun firstPassingWins() = runTest {
        val mb = Fake { null } // its candidate failed the match rules
        val second = Fake { "2".toByteArray() }
        val third = Fake { "3".toByteArray() }
        val chain = ChainCoverSource(listOf(NamedSource("mb", mb), NamedSource("second", second), NamedSource("third", third)))
        assertArrayEquals("2".toByteArray(), chain.frontCover("A", "B"))
        assertEquals(0, third.calls)
    }

    @Test fun allDefinitiveNoneFound() = runTest {
        val chain =
            ChainCoverSource(
                listOf(
                    NamedSource(
                        "mb",
                        Fake {
                            null
                        }
                    ),
                    NamedSource("second", Fake { null })
                )
            )
        assertNull(chain.frontCover("A", "B"))
    }

    @Test(expected = IOException::class)
    fun transientSourceMeansNoMiss() = runTest {
        val chain =
            ChainCoverSource(
                listOf(
                    NamedSource(
                        "mb",
                        Fake {
                            null
                        },
                        blocked()
                    ),
                    NamedSource("second", Fake { null })
                )
            )
        chain.frontCover("A", "B")
    }

    @Test fun `a failing source backs off and the chain still finds a cover`() = runTest {
        val mb = NamedSource("mb", Fake { throw IOException("503") })
        val chain = ChainCoverSource(listOf(mb, NamedSource("second", Fake { "2".toByteArray() })))
        assertArrayEquals("2".toByteArray(), chain.frontCover("A", "B"))
        assertEquals(30_000L, mb.backoff.currentDelayMs)
    }

    @Test fun sourceInBackoffSkipped() = runTest {
        val mb = Fake { "MB".toByteArray() }
        val chain = ChainCoverSource(listOf(NamedSource("mb", mb, blocked()), NamedSource("second", Fake { "2".toByteArray() })))
        assertArrayEquals("2".toByteArray(), chain.frontCover("A", "B"))
        assertEquals(0, mb.calls)
    }

    // The chain behind the resolver and its disk cache: misses are keyed by the query (album artist + album, chain
    // version "3"), so new names after an identification are a new query, and "Find missing covers again" forgets the
    // misses older than its start while found covers stay.
    private class Recording(val found: Set<Pair<String, String>>) : OnlineCoverSource {
        val asked = mutableListOf<Pair<String, String>>()

        override suspend fun frontCover(artist: String, album: String): ByteArray? {
            asked += artist to album
            return if (artist to album in found) "IMG".toByteArray() else null
        }
    }

    private fun resolver(source: OnlineCoverSource, cache: OnlineCoverCache) = AlbumArtResolver(
        embedded = { null },
        listNames = { emptyList() },
        policy = { ArtPolicy(useFolderImages = true, onlineEnabled = true, wifiOnly = false) },
        network = { NetworkState(connected = true, unmetered = true) },
        onlineSource = ChainCoverSource(listOf(NamedSource("mb", source))),
        onlineCache = cache
    )

    @Test fun `a miss for the old names does not block the chain for the new names`() = runTest {
        val src = Recording(found = setOf("Fleetwood Mac" to "Rumours"))
        val cache = OnlineCoverCache(tmp.newFolder())
        val r = resolver(src, cache)
        assertNull(r.resolveOnline(ArtRef("/m/x/07.wav", "/m/x", "Fleetwood Mac", "Unknown Album")))
        assertEquals(CachedCover.Miss, cache.get("Fleetwood Mac", "Unknown Album"))
        val art = r.resolveOnline(ArtRef("/m/x/07.wav", "/m/x", "Fleetwood Mac", "Rumours"))
        assertEquals(ArtOrigin.ONLINE, art?.origin)
        assertEquals(listOf("Fleetwood Mac" to "Unknown Album", "Fleetwood Mac" to "Rumours"), src.asked)
    }

    @Test fun `clearing misses asks the chain again, keeps found covers and the run's own misses`() = runTest {
        var now = 1_000_000L
        val src = Recording(found = setOf("A" to "Found"))
        val cache = OnlineCoverCache(tmp.newFolder(), clock = { now })
        val r = resolver(src, cache)
        r.resolveOnline(ArtRef(null, "/m/a", "A", "Found"))
        r.resolveOnline(ArtRef(null, "/m/b", "B", "Missing"))
        assertEquals(2, src.asked.size)
        val runStart = now + 1_000
        now += 2_000
        r.resolveOnline(ArtRef(null, "/m/c", "C", "Missed during the run"))

        assertEquals(1, cache.clearMisses(writtenBefore = runStart))

        assertTrue(cache.get("A", "Found") is CachedCover.Hit)
        assertEquals(CachedCover.Unknown, cache.get("B", "Missing"))
        assertEquals(CachedCover.Miss, cache.get("C", "Missed during the run"))
        r.resolveOnline(ArtRef(null, "/m/b", "B", "Missing"))
        assertEquals("B" to "Missing", src.asked.last())
        assertEquals(4, src.asked.size)
    }
}
