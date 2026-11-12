package org.seion.meta

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.art.AlbumArtResolver
import org.seion.art.ArtOrigin
import org.seion.art.ArtPolicy
import org.seion.art.ArtRef
import org.seion.art.ArtRepository
import org.seion.art.ArtState
import org.seion.art.CachedCover
import org.seion.art.ChainCoverSource
import org.seion.art.CoverSearch
import org.seion.art.MissingCovers
import org.seion.art.MusicBrainzCoverSource
import org.seion.art.NamedSource
import org.seion.art.NetworkState
import org.seion.art.OnlineCoverCache
import org.seion.art.OnlineHttp
import org.seion.art.RateLimiter
import org.seion.library.LibraryScanner
import org.seion.library.LookupState
import org.seion.library.SeionDatabase
import org.seion.library.TagSource
import org.seion.library.Tags
import org.seion.library.TrackEntity

/** The identification run (the worker's body) against MockWebServer and an in-memory Room DB. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IdentifyWorkerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var db: SeionDatabase

    @Volatile private var online = true

    @Before fun setUp() {
        server.start()
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), SeionDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After fun tearDown() {
        db.close()
        server.close()
    }

    private fun identifier(
        client: OkHttpClient = OnlineHttp.defaultClient(),
        onIdentified: suspend (TrackEntity) -> Unit = {},
        musicBrainzLimiter: RateLimiter = RateLimiter(0)
    ): TrackIdentifier {
        val base = server.url("/").toString().trimEnd('/')
        return TrackIdentifier(
            db.tracks(),
            MusicBrainzRecordingSource(client, limiter = musicBrainzLimiter, base = base),
            allowed = { online },
            now = { 42L },
            onIdentified = onIdentified
        )
    }

    // The cover side, as AppContainer wires it: the resolver with the chain (MusicBrainz + Cover Art Archive on the mock
    // server), the disk cache, ArtRepository and MissingCovers; identification hands each match to afterIdentify.
    private val coverDir by lazy { tmp.newFolder("online-covers") }
    private var folderNames: List<String> = emptyList()

    private fun cache() = OnlineCoverCache(coverDir)

    private fun art(scope: CoroutineScope, io: kotlinx.coroutines.CoroutineDispatcher): ArtRepository {
        val base = server.url("/").toString().trimEnd('/')
        val chain = ChainCoverSource(
            listOf(
                NamedSource(
                    "musicbrainz",
                    MusicBrainzCoverSource(limiter = RateLimiter(0), musicBrainzBase = base, coverArtBase = "$base/caa")
                )
            )
        )
        val resolver = AlbumArtResolver(
            embedded = { null },
            listNames = { folderNames },
            policy = { ArtPolicy(useFolderImages = true, onlineEnabled = online, wifiOnly = false) },
            network = { NetworkState(connected = true, unmetered = true) },
            onlineSource = chain,
            onlineCache = cache()
        )
        return ArtRepository(
            resolveLocal = resolver::resolveLocal,
            fileExists = { true },
            resolveOnline = resolver::lookupOnline,
            scope = scope,
            io = io
        )
    }

    private val png: ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF203040.toInt()) }
                    .compress(Bitmap.CompressFormat.PNG, 100, out)
            }.toByteArray()

    private val dreamsHit =
        """{"recordings":[{"id":"rec-1","title":"Dreams","length":256800,"artist-credit":[{"name":"Fleetwood Mac","joinphrase":""}],
        "releases":[{"title":"Rumours","release-group":{"secondary-types":[]}}]}]}"""
    private val rumoursGroup =
        """{"release-groups":[{"id":"rg-1","score":100,"title":"Rumours","primary-type":"Album",
        "artist-credit":[{"name":"Fleetwood Mac","joinphrase":""}]}]}"""

    private fun image() = MockResponse.Builder().code(200).body(Buffer().write(png)).build()

    private val rumoursDir = "/m/Fleetwood Mac/Rumours"

    /** Identify one untagged row in [rumoursDir] with the cover search wired in; returns afterIdentify's outcomes. */
    private suspend fun TestScope.identifyWithCovers(art: ArtRepository): List<CoverSearch> {
        val covers = MissingCovers(db.tracks(), art, cache(), onlineOn = { online })
        val outcomes = mutableListOf<CoverSearch>()
        identifier(onIdentified = { t -> outcomes += covers.afterIdentify(t) }).runPending()
        runCurrent()
        return outcomes
    }

    @Test fun `an identify hit searches the cover with the new names, stores and publishes it`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val art = art(backgroundScope, io)
        db.tracks().upsert(listOf(row("07 Dreams.wav", folder = rumoursDir)))
        val path = "$rumoursDir/07 Dreams.wav"
        // Now playing shows the track as the queue knew it before identification: no artist, no album, no cover.
        val nowPlaying = art.state(ArtRef(path, rumoursDir, null, null), 64)
        runCurrent()
        assertEquals(ArtState.Missing(onlinePending = false), nowPlaying.value)
        val hits = mutableListOf<String>()
        backgroundScope.launch(io) { art.onlineHits.collect { hits += it } }
        runCurrent()
        server.enqueue(ok(dreamsHit))
        server.enqueue(ok(rumoursGroup))
        server.enqueue(image())

        assertEquals(listOf(CoverSearch.FOUND), identifyWithCovers(art))

        assertEquals(3, server.requestCount)
        assertEquals("/ws/2/recording/", server.takeRequest().url.encodedPath)
        val search = server.takeRequest().url
        assertEquals("/ws/2/release-group/", search.encodedPath)
        assertEquals("releasegroup:\"Rumours\" AND artist:\"Fleetwood Mac\"", search.queryParameter("query"))
        assertEquals("/caa/release-group/rg-1/front-500", server.takeRequest().url.encodedPath)
        assertTrue(cache().get("Fleetwood Mac", "Rumours") is CachedCover.Hit)
        assertEquals(listOf(path), hits)
        val shown = nowPlaying.value
        assertTrue("now playing: $shown", shown is ArtState.Found && shown.origin == ArtOrigin.ONLINE)
    }

    @Test fun `a track that already has a cover is not searched after identification`() = runTest {
        folderNames = listOf("07 Dreams.wav", "cover.jpg")
        val art = art(backgroundScope, StandardTestDispatcher(testScheduler))
        db.tracks().upsert(listOf(row("07 Dreams.wav", folder = rumoursDir)))
        server.enqueue(ok(dreamsHit))

        assertEquals(listOf(CoverSearch.LOCAL), identifyWithCovers(art))

        assertEquals(1, server.requestCount) // the identification only
        assertEquals(CachedCover.Unknown, cache().get("Fleetwood Mac", "Rumours"))
    }

    @Test fun `a rescan after identify and cover makes zero requests`() = runTest {
        val root = tmp.newFolder("Music")
        val dir = File(root, "Fleetwood Mac/Rumours").apply { mkdirs() }
        File(dir, "07 Dreams.wav").writeText("RIFF")
        val untagged = Tags(null, null, null, null, null, null, null, null, durationMs = 256_540, hasEmbeddedArt = false)
        val scanner = LibraryScanner(db.tracks(), { untagged }, isVolumeRoot = { false }, now = { 1L })
        scanner.scan(listOf(root.path))
        val io = StandardTestDispatcher(testScheduler)
        server.enqueue(ok(dreamsHit))
        server.enqueue(ok(rumoursGroup))
        server.enqueue(image())
        assertEquals(listOf(CoverSearch.FOUND), identifyWithCovers(art(backgroundScope, io)))
        assertEquals(3, server.requestCount)

        // Rescan (unchanged files), the identify run it queues, and the library asking for the cover after a restart.
        assertEquals(0, scanner.scan(listOf(root.path)).read)
        assertEquals(0, identifier().runPending())
        val t = db.tracks().byPaths(listOf(File(dir, "07 Dreams.wav").path)).single()
        assertEquals("Rumours", t.album)
        val fresh = art(backgroundScope, io)
        val loaded = backgroundScope.launch(io) { fresh.load(ArtRef(t.path, t.folder, t.albumArtist ?: t.artist, t.album), 64) }
        runCurrent()
        assertTrue(loaded.isCompleted)
        assertTrue(fresh.cached(ArtRef(t.path, t.folder, t.artist, t.album), 64) != null)
        assertEquals(3, server.requestCount)
    }

    private fun row(name: String, folder: String = "/m/Music") = TrackEntity(
        path = "$folder/$name", folder = folder, fileName = name, title = name.substringBeforeLast('.'), artist = null,
        albumArtist = null, album = null, albumKey = "folder:$folder", genre = null, year = null, trackNumber = null,
        discNumber = null, durationMs = 256_540, lastModified = 1, sizeBytes = 1, dateAdded = 1, hasEmbeddedArt = false,
        titleSource = TagSource.FALLBACK, artistSource = TagSource.MISSING, albumSource = TagSource.MISSING
    )

    private suspend fun stored(name: String, folder: String = "/m/Music") = db.tracks().byPaths(listOf("$folder/$name")).single()

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    @Test fun `an MB miss is recorded as not found, then a second run makes zero requests`() = runTest {
        db.tracks().upsert(listOf(row("03 - Fleetwood Mac - Dreams.mp3")))
        // The only recording is a live version: it does not pass, so nothing is identified.
        server.enqueue(
            ok(
                """{"recordings":[{"id":"rec-live","title":"Dreams (Live)","length":257000,
                "artist-credit":[{"name":"Fleetwood Mac","joinphrase":""}],"releases":[{"title":"Live"}]}]}"""
            )
        )
        assertEquals(1, identifier().runPending())
        assertEquals(1, server.requestCount)
        val mb = server.takeRequest().url
        assertEquals("/ws/2/recording/", mb.encodedPath)
        assertEquals("recording:\"Dreams\" AND artist:\"Fleetwood Mac\" AND dur:[253540 TO 259540]", mb.queryParameter("query"))
        val t = stored("03 - Fleetwood Mac - Dreams.mp3")
        assertEquals(LookupState.NOT_FOUND, t.lookupState)
        assertEquals("musicbrainz", t.lookupSource)
        assertEquals("03 - Fleetwood Mac - Dreams", t.title) // nothing written
        assertEquals(0, identifier().runPending())
        assertEquals(1, server.requestCount)
    }

    @Test fun `an MB hit fills only the missing fields`() = runTest {
        db.tracks().upsert(listOf(row("07 Dreams.mp3", folder = "/m/Fleetwood Mac/Rumours")))
        server.enqueue(
            ok(
                """{"recordings":[{"id":"rec-1","title":"Dreams","length":256800,"artist-credit":[{"name":"Fleetwood Mac","joinphrase":""}],
                "releases":[{"title":"Rumours","release-group":{"secondary-types":[]}}]}]}"""
            )
        )
        identifier().runPending()
        assertEquals(1, server.requestCount)
        val t = stored("07 Dreams.mp3", folder = "/m/Fleetwood Mac/Rumours")
        assertEquals(LookupState.FOUND, t.lookupState)
        assertEquals("Dreams", t.title)
        assertEquals("Fleetwood Mac", t.artist)
        assertEquals("Rumours", t.album)
        assertEquals(TagSource.ONLINE, t.artistSource)
        assertEquals("rec-1", t.lookupRef)
        assertEquals(null, t.albumArtist)
    }

    @Test fun `online off makes zero requests and leaves the row pending`() = runTest {
        online = false
        db.tracks().upsert(listOf(row("03 - Fleetwood Mac - Dreams.mp3")))
        identifier().runPending()
        assertEquals(0, server.requestCount)
        assertEquals(LookupState.NONE, stored("03 - Fleetwood Mac - Dreams.mp3").lookupState)
    }

    @Test fun `junk and title-only names are settled locally with zero requests`() = runTest {
        db.tracks().upsert(listOf(row("AUD-20240101-WA0001.mp3"), row("Creep.mp3")))
        assertEquals(2, identifier().runPending())
        assertEquals(0, server.requestCount)
        listOf("AUD-20240101-WA0001.mp3", "Creep.mp3").forEach {
            val t = stored(it)
            assertEquals(LookupState.NOT_FOUND, t.lookupState)
            assertEquals("local", t.lookupSource)
        }
    }

    @Test fun `a transient failure leaves the row pending`() = runTest {
        db.tracks().upsert(listOf(row("03 - Fleetwood Mac - Dreams.mp3")))
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(0, identifier().runPending())
        assertEquals(LookupState.NONE, stored("03 - Fleetwood Mac - Dreams.mp3").lookupState)
    }

    @Test fun `switching off cancels an in-flight identification and leaves the row pending`() = runBlocking {
        // A short read timeout, so a call that is NOT cancelled fails in seconds instead of hanging.
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        db.tracks().upsert(listOf(row("03 - Fleetwood Mac - Dreams.mp3")))
        server.enqueue(ok("""{"recordings":[]}""").newBuilder().headersDelay(5, TimeUnit.SECONDS).build())
        val run = async(Dispatchers.IO) { identifier(client).runPending() }
        assertNotNull("the search reached the server", server.takeRequest(5, TimeUnit.SECONDS))

        online = false
        val off = System.nanoTime()
        OnlineHttp.stopAll(client)

        assertEquals(0, withTimeout(10_000) { run.await() })
        assertTrue("ended by the cancellation, not by the read timeout", System.nanoTime() - off < 1_500_000_000L)
        assertEquals("nothing more is sent after the switch went off", 1, server.requestCount)
        assertEquals("no not-found is recorded", LookupState.NONE, stored("03 - Fleetwood Mac - Dreams.mp3").lookupState)
        assertEquals(0, client.connectionPool.connectionCount())
    }

    @Test fun `switching off while the search waits in the rate limiter sends nothing and leaves the row pending`() = runBlocking {
        // The MusicBrainz limiter is shared with the cover chain, so a search can wait its turn for a while.
        val gated = OnlineHttp.defaultClient(allowed = { online })
        val waiting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val limiter = RateLimiter(1_000, clock = { 0L }, sleep = {
            waiting.complete(Unit)
            release.await()
        })
        limiter.run {} // a cover request just took the turn: the search waits
        db.tracks().upsert(listOf(row("07 Dreams.mp3", folder = "/m/Fleetwood Mac/Rumours")))
        server.enqueue(ok(dreamsHit))
        val run = async(Dispatchers.IO) { identifier(gated, musicBrainzLimiter = limiter).runPending() }
        withTimeout(10_000) { waiting.await() }

        online = false
        OnlineHttp.stopAll(gated)
        release.complete(Unit)

        assertEquals(0, withTimeout(10_000) { run.await() })
        assertEquals("no request goes out after the switch went off", 0, server.requestCount)
        val t = stored("07 Dreams.mp3", folder = "/m/Fleetwood Mac/Rumours")
        assertEquals("nothing is recorded: the row stays pending", LookupState.NONE, t.lookupState)
        assertEquals(null, t.artist)
    }

    @Test fun `fully tagged rows are never looked up`() = runTest {
        db.tracks().upsert(listOf(row("Dreams.mp3").copy(title = "Dreams", artist = "Fleetwood Mac", titleSource = TagSource.TAG)))
        assertEquals(0, identifier().runPending())
        assertEquals(0, server.requestCount)
    }

    @Test fun `identify again resets only not-found rows`() = runTest {
        val found = row("a.mp3").copy(lookupState = LookupState.FOUND, lookupSource = "musicbrainz")
        val notFound = row("b.mp3").copy(lookupState = LookupState.NOT_FOUND, lookupSource = "musicbrainz")
        val local = row("c.mp3").copy(lookupState = LookupState.NOT_FOUND, lookupSource = "local")
        val pending = row("d.mp3")
        db.tracks().upsert(listOf(found, notFound, local, pending))
        assertEquals(2, db.tracks().resetNotFound())
        assertEquals(LookupState.FOUND, stored("a.mp3").lookupState)
        assertEquals(LookupState.NONE, stored("b.mp3").lookupState)
        assertEquals(LookupState.NONE, stored("c.mp3").lookupState)
        assertEquals(LookupState.NONE, stored("d.mp3").lookupState)
    }

    /** The worker as WorkManager runs attempt [attempt], with rows still pending and the gate open after the pass. */
    private fun worker(attempt: Int, pending: Int = 5, allowed: Boolean = true): IdentifyWorker {
        val deps = IdentifyWorker.Deps(onlineOn = { true }, onlineAllowed = { allowed }, runPending = { 0 }, pendingCount = { pending })
        return TestListenableWorkerBuilder<IdentifyWorker>(ApplicationProvider.getApplicationContext())
            .setRunAttemptCount(attempt)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        IdentifyWorker(appContext, workerParameters, deps)
                }
            ).build()
    }

    @Test fun `a transient failure retries, at most three runs in all`() = runTest {
        // An unreachable source must not make the job come back every few hours for ever; the rows stay pending and
        // the next scan, the online switch or "Identify again" queue a new run.
        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 0).doWork())
        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 1).doWork())
        assertEquals(ListenableWorker.Result.success(), worker(attempt = 2).doWork())
        assertEquals(ListenableWorker.Result.success(), worker(attempt = 3).doWork())
    }

    @Test fun `nothing pending or the gate closed ends the run`() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker(attempt = 0, pending = 0).doWork())
        assertEquals(ListenableWorker.Result.success(), worker(attempt = 0, allowed = false).doWork())
    }
}
