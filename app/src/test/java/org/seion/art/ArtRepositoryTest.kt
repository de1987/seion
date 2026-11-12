package org.seion.art

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A read that failed because the file was briefly missing must not be cached as "no art". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArtRepositoryTest {
    private val png: ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(0xFF102030.toInt())
                }.compress(Bitmap.CompressFormat.PNG, 100, out)
            }.toByteArray()

    private val ref = ArtRef("/m/a/03 Pitch Black.flac", "/m/a", "Artist", "Album")

    @Test fun `a miss while the track file is missing is not cached`() = runTest {
        var present = false
        val repo =
            ArtRepository(resolveLocal = { if (present) ResolvedArt.Bytes(png, ArtOrigin.EMBEDDED) else null }, fileExists = { present })
        assertNull(repo.load(ref, 64)) // renamed away: the prefetch fails
        present = true // renamed back
        assertNotNull(repo.load(ref, 64))
    }

    @Test fun `a miss for a readable file is still cached`() = runTest {
        var calls = 0
        val repo = ArtRepository(resolveLocal = { calls++.let { null } }, fileExists = { true })
        assertNull(repo.load(ref, 64))
        assertNull(repo.load(ref, 64))
        assertEquals(1, calls)
    }

    @Test fun localPhaseIsNeverBlockedByOnline() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo =
            ArtRepository(
                resolveLocal = { null },
                fileExists = { true },
                resolveOnline = { awaitCancellation() },
                scope = backgroundScope,
                io = dispatcher
            )
        val flow = repo.state(ref, 64)
        advanceTimeBy(50)
        runCurrent()
        assertEquals(ArtState.Missing(onlinePending = true), flow.value)
    }

    @Test fun onlineHitUpdatesState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val online = CompletableDeferred<ResolvedArt?>()
        val repo =
            ArtRepository(
                resolveLocal = { null },
                fileExists = { true },
                resolveOnline = { OnlineLookup.of(online.await()) },
                scope = backgroundScope,
                io = dispatcher
            )
        val hits = mutableListOf<String>()
        backgroundScope.launch(dispatcher) { repo.onlineHits.collect { hits += it } }
        val flow = repo.state(ref, 64)
        runCurrent()
        assertEquals(ArtState.Missing(onlinePending = true), flow.value)
        online.complete(ResolvedArt.Bytes(png, ArtOrigin.ONLINE))
        runCurrent()
        val found = flow.value
        assertTrue("state: $found", found is ArtState.Found && found.origin == ArtOrigin.ONLINE)
        assertEquals(listOf(ref.cacheKey), hits)
    }

    @Test fun `local art is found without waiting for online`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo =
            ArtRepository(
                resolveLocal = { ResolvedArt.Bytes(png, ArtOrigin.EMBEDDED) },
                resolveOnline = { awaitCancellation() },
                scope = backgroundScope,
                io = dispatcher
            )
        val flow = repo.state(ref, 64)
        runCurrent()
        assertTrue(flow.value is ArtState.Found)
        // A second observer gets the cached art at once, without a Loading step.
        assertTrue(repo.state(ref.copy(), 64).value is ArtState.Found)
    }

    // Identification gives an untagged track an artist and album: the miss remembered for its old names (none) must not
    // block the lookup with the new ones; the track key (its path) stays the same.
    private val untagged = ArtRef("/m/Fleetwood Mac/Rumours/07 Dreams.wav", "/m/Fleetwood Mac/Rumours", null, null)
    private val identified = untagged.copy(artist = "Fleetwood Mac", album = "Rumours")

    @Test fun `a new query for the same track is not blocked by the old miss`() = runTest {
        val asked = mutableListOf<ArtRef>()
        val repo =
            ArtRepository(
                resolveLocal = { null },
                fileExists = { true },
                resolveOnline = { r ->
                    asked += r
                    OnlineLookup.of(if (r.album == "Rumours") ResolvedArt.Bytes(png, ArtOrigin.ONLINE) else null)
                }
            )
        assertNull(repo.load(untagged, 64))
        assertNull(repo.load(untagged, 64)) // the miss holds for the same names
        assertNotNull(repo.load(identified, 64))
        assertEquals(listOf(untagged, identified), asked)
    }

    @Test fun `an observed track follows its new names`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo =
            ArtRepository(
                resolveLocal = { null },
                fileExists = { true },
                resolveOnline = { r -> OnlineLookup.of(if (r.album == "Rumours") ResolvedArt.Bytes(png, ArtOrigin.ONLINE) else null) },
                scope = backgroundScope,
                io = dispatcher
            )
        val flow = repo.state(untagged, 64)
        runCurrent()
        assertEquals(ArtState.Missing(onlinePending = false), flow.value)
        // The queue item was rebuilt with the identified names: same track key, new query.
        val again = repo.state(identified, 64)
        runCurrent()
        assertTrue("state: ${again.value}", again.value is ArtState.Found)
    }

    @Test fun `findOnline never replaces local art`() = runTest {
        var online = 0
        val repo =
            ArtRepository(
                resolveLocal = { ResolvedArt.Bytes(png, ArtOrigin.FOLDER) },
                fileExists = { true },
                resolveOnline = { online++.let { OnlineLookup.Hit(ResolvedArt.Bytes(png, ArtOrigin.ONLINE)) } }
            )
        assertEquals(CoverSearch.LOCAL, repo.findOnline(identified))
        assertEquals(0, online)
    }

    @Test fun `findOnline publishes a hit to every track of the album`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo =
            ArtRepository(
                resolveLocal = { null },
                fileExists = { true },
                resolveOnline = { r -> OnlineLookup.of(if (r.album == "Rumours") ResolvedArt.Bytes(png, ArtOrigin.ONLINE) else null) },
                scope = backgroundScope,
                io = dispatcher
            )
        val other = ArtRef("/m/Fleetwood Mac/Rumours/08 Don't Stop.wav", "/m/Fleetwood Mac/Rumours", null, null)
        val shownA = repo.state(untagged, 64)
        val shownB = repo.state(other, 64)
        val hits = mutableListOf<String>()
        backgroundScope.launch(dispatcher) { repo.onlineHits.collect { hits += it } }
        runCurrent()
        assertEquals(CoverSearch.FOUND, repo.findOnline(identified, listOf(untagged.cacheKey, other.cacheKey)))
        runCurrent()
        assertEquals(listOf(untagged.cacheKey, other.cacheKey), hits)
        assertTrue("A: ${shownA.value}", shownA.value is ArtState.Found)
        assertTrue("B: ${shownB.value}", shownB.value is ArtState.Found)
    }

    @Test fun `forgetting online misses asks again, local misses stay`() = runTest {
        var local = 0
        var online = 0
        val repo =
            ArtRepository(resolveLocal = {
                local++.let { null }
            }, fileExists = { true }, resolveOnline = { online++.let { OnlineLookup.Miss } })
        assertNull(repo.load(identified, 64))
        repo.forgetOnlineMisses()
        assertNull(repo.load(identified, 64))
        assertEquals(1, local)
        assertEquals(2, online)
    }

    @Test fun missingRefIsMissing() {
        val repo = ArtRepository(resolveLocal = { null })
        assertEquals(ArtState.Missing(false), repo.state(null, 64).value)
    }
}
