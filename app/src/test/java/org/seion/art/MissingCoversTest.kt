package org.seion.art

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.util.concurrent.Futures
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.seion.library.SeionDatabase
import org.seion.library.TagSource
import org.seion.library.TrackEntity

/** "Find missing covers again" (MissingCovers.findAgain and its unique work) against MockWebServer and Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MissingCoversTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var db: SeionDatabase
    private var online = true
    private val folders = mutableMapOf<String, List<String>>()
    private var now = 10_000_000L

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

    private val coverDir by lazy { tmp.newFolder("online-covers") }

    private fun cache() = OnlineCoverCache(coverDir, clock = { now })

    private fun art(
        io: kotlinx.coroutines.CoroutineDispatcher,
        scope: kotlinx.coroutines.CoroutineScope,
        client: OkHttpClient = OnlineHttp.defaultClient()
    ): ArtRepository {
        val base = server.url("/").toString().trimEnd('/')
        val resolver = AlbumArtResolver(
            embedded = { null },
            listNames = { folders[it].orEmpty() },
            policy = { ArtPolicy(useFolderImages = true, onlineEnabled = online, wifiOnly = false) },
            network = { NetworkState(connected = true, unmetered = true) },
            onlineSource = ChainCoverSource(
                listOf(
                    NamedSource(
                        "musicbrainz",
                        MusicBrainzCoverSource(client, RateLimiter(0), musicBrainzBase = base, coverArtBase = "$base/caa")
                    )
                )
            ),
            onlineCache = cache()
        )
        return ArtRepository(resolver::resolveLocal, { true }, resolver::lookupOnline, scope, io)
    }

    private fun row(folder: String, name: String, artist: String, album: String, embedded: Boolean = false) = TrackEntity(
        path = "$folder/$name", folder = folder, fileName = name, title = name, artist = artist, albumArtist = null, album = album,
        albumKey = album.lowercase(), genre = null, year = null, trackNumber = null, discNumber = null, durationMs = 200_000,
        lastModified = 1, sizeBytes = 1, dateAdded = 1, hasEmbeddedArt = embedded, titleSource = TagSource.TAG
    )

    private val png: ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF405060.toInt()) }
                    .compress(Bitmap.CompressFormat.PNG, 100, out)
            }.toByteArray()

    private fun group(id: String, title: String, artist: String) = MockResponse.Builder().code(200).body(
        """{"release-groups":[{"id":"$id","score":100,"title":"$title","primary-type":"Album","artist-credit":[{"name":"$artist","joinphrase":""}]}]}"""
    ).build()

    @Test fun `find again clears misses and retries only the albums without a cover`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val art = art(io, backgroundScope)
        db.tracks().upsert(
            listOf(
                // Missed earlier (a cached v3 miss): searched again.
                row("/m/Rumours", "01 Second Hand News.flac", "Fleetwood Mac", "Rumours"),
                row("/m/Rumours", "02 Dreams.flac", "Fleetwood Mac", "Rumours"),
                // Embedded art, a folder image, a cover found online earlier: never searched.
                row("/m/Kind of Blue", "01 So What.flac", "Miles Davis", "Kind of Blue", embedded = true),
                row("/m/Blue Train", "01 Blue Train.flac", "John Coltrane", "Blue Train"),
                row("/m/Mezzanine", "01 Angel.flac", "Massive Attack", "Mezzanine")
            )
        )
        folders["/m/Blue Train"] = listOf("01 Blue Train.flac", "folder.jpg")
        cache().putMiss("Fleetwood Mac", "Rumours")
        cache().putHit("Massive Attack", "Mezzanine", png)
        // Seion remembers the miss in memory too (the grid showed the album without a cover).
        val gridRef = ArtRef("/m/Rumours/01 Second Hand News.flac", "/m/Rumours", "Fleetwood Mac", "Rumours")
        val loaded = backgroundScope.async(io) { art.load(gridRef, 64) }
        runCurrent()
        assertEquals(null, loaded.await())
        val grid = art.state(gridRef, 64)
        runCurrent()
        assertEquals(ArtState.Missing(onlinePending = false), grid.value)
        val hits = mutableListOf<String>()
        backgroundScope.launch(io) { art.onlineHits.collect { hits += it } }
        runCurrent()
        server.enqueue(group("rg-r", "Rumours", "Fleetwood Mac"))
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(png)).build())
        now += 60_000

        val summary = MissingCovers(db.tracks(), art, cache(), onlineOn = { online }).findAgain(since = now)
        runCurrent()

        assertEquals(MissingCovers.Summary(found = 1, missing = 0, pending = 0), summary)
        assertEquals(2, server.requestCount)
        assertEquals("releasegroup:\"Rumours\" AND artist:\"Fleetwood Mac\"", server.takeRequest().url.queryParameter("query"))
        assertTrue(cache().get("Fleetwood Mac", "Rumours") is CachedCover.Hit)
        assertEquals(setOf("/m/Rumours/01 Second Hand News.flac", "/m/Rumours/02 Dreams.flac"), hits.toSet())
        assertTrue("grid: ${grid.value}", grid.value is ArtState.Found)
    }

    @Test fun `an album that is still missing is counted and its miss cached again`() = runTest {
        val art = art(StandardTestDispatcher(testScheduler), backgroundScope)
        db.tracks().upsert(listOf(row("/m/Obscure", "01 a.flac", "Nobody", "Obscure")))
        cache().putMiss("Nobody", "Obscure")
        server.enqueue(MockResponse.Builder().code(200).body("""{"release-groups":[]}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"artists":[]}""").build())
        now += 60_000

        val summary = MissingCovers(db.tracks(), art, cache(), onlineOn = { online }).findAgain(since = now)

        assertEquals(MissingCovers.Summary(found = 0, missing = 1, pending = 0), summary)
        assertEquals(2, server.requestCount)
        assertEquals(CachedCover.Miss, cache().get("Nobody", "Obscure"))
    }

    @Test fun `online off makes zero requests and keeps the misses`() = runTest {
        online = false
        val art = art(StandardTestDispatcher(testScheduler), backgroundScope)
        db.tracks().upsert(listOf(row("/m/Rumours", "02 Dreams.flac", "Fleetwood Mac", "Rumours")))
        cache().putMiss("Fleetwood Mac", "Rumours")
        now += 60_000

        val summary = MissingCovers(db.tracks(), art, cache(), onlineOn = { online }).findAgain(since = now)

        assertEquals(MissingCovers.Summary(0, 0, 0), summary)
        assertEquals(0, server.requestCount)
        assertEquals(CachedCover.Miss, cache().get("Fleetwood Mac", "Rumours"))
    }

    @Test fun `repeated taps do not stack`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        // The network constraint is never met in the test WorkManager: the first request waits, the others are dropped.
        repeat(3) { MissingCoversWorker.enqueue(context, wifiOnly = true, now = 1_000L + it) }
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(MissingCoversWorker.NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    /** The worker as WorkManager runs attempt [attempt] of the run tapped at [since], with [findAgain] answering [summary]. */
    private fun worker(attempt: Int, since: Long, summary: MissingCovers.Summary): MissingCoversWorker =
        worker(attempt, since, MissingCoversWorker.Deps(onlineOn = { true }, onlineAllowed = { true }, findAgain = { _, _ -> summary }))

    private val progress = mutableListOf<Data>()

    private fun worker(attempt: Int, since: Long, deps: MissingCoversWorker.Deps): MissingCoversWorker =
        TestListenableWorkerBuilder<MissingCoversWorker>(ApplicationProvider.getApplicationContext())
            .setRunAttemptCount(attempt)
            .setInputData(workDataOf(MissingCoversWorker.KEY_SINCE to since))
            .setProgressUpdater { _, _, data ->
                progress += data
                Futures.immediateVoidFuture()
            }.setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        MissingCoversWorker(appContext, workerParameters, deps)
                }
            ).build()

    private fun done(found: Int, missing: Int) =
        ListenableWorker.Result.success(workDataOf(MissingCoversWorker.KEY_FOUND to found, MissingCoversWorker.KEY_MISSING to missing))

    @Test fun `covers found before a retry still count in the run's summary`() = runTest {
        // Attempt 1 found 3 and retried for the albums left pending; the retry found none.
        assertEquals(ListenableWorker.Result.retry(), worker(0, since = 5_000, MissingCovers.Summary(3, 10, 5)).doWork())
        assertEquals(done(found = 3, missing = 39), worker(1, since = 5_000, MissingCovers.Summary(0, 39, 0)).doWork())
        // The next tap starts from zero, also after a run whose retry never came.
        assertEquals(ListenableWorker.Result.retry(), worker(0, since = 9_000, MissingCovers.Summary(2, 0, 1)).doWork())
        assertEquals(done(found = 1, missing = 38), worker(0, since = 12_000, MissingCovers.Summary(1, 38, 0)).doWork())
    }

    private fun info(state: WorkInfo.State, out: Data = Data.EMPTY, progress: Data = Data.EMPTY) =
        WorkInfo(UUID.randomUUID(), state, emptySet(), outputData = out, progress = progress)

    private fun progressOf(processed: Int, total: Int, found: Int) = workDataOf(
        MissingCoversWorker.KEY_PROCESSED to processed,
        MissingCoversWorker.KEY_TOTAL to total,
        MissingCoversWorker.KEY_FOUND to found
    )

    private fun record(phase: FindCoversRecord.Phase, processed: Int, total: Int, found: Int, missing: Int = -1) =
        FindCoversRecord(since = 5_000, phase = phase, processed = processed, total = total, found = found, missing = missing)

    @Test fun `the row's status follows the work and the run's record`() {
        val running = FindCoversRecord.Phase.RUNNING
        val stopped = FindCoversRecord.Phase.STOPPED
        val done = FindCoversRecord.Phase.DONE
        assertEquals(FindCoversStatus.Idle, FindCoversStatus.from(emptyList(), null))
        // Queued or started, the total not known yet: indeterminate.
        assertEquals(FindCoversStatus.Running(), FindCoversStatus.from(listOf(info(WorkInfo.State.ENQUEUED)), null))
        assertEquals(FindCoversStatus.Running(), FindCoversStatus.from(listOf(info(WorkInfo.State.RUNNING)), record(running, 0, -1, 0)))
        // Live progress.
        assertEquals(
            FindCoversStatus.Running(12, 46, 3),
            FindCoversStatus.from(listOf(info(WorkInfo.State.RUNNING, progress = progressOf(12, 46, 3))), null)
        )
        // Between two attempts (no live progress): the run's record; a stale "stopped" (the system paused it) is ignored.
        assertEquals(
            FindCoversStatus.Running(12, 46, 3),
            FindCoversStatus.from(listOf(info(WorkInfo.State.ENQUEUED)), record(running, 12, 46, 3))
        )
        assertEquals(
            FindCoversStatus.Running(12, 46, 3),
            FindCoversStatus.from(listOf(info(WorkInfo.State.ENQUEUED)), record(stopped, 12, 46, 3, missing = 43))
        )
        // Finished: the persisted final status, also once WorkManager has pruned the work.
        assertEquals(
            FindCoversStatus.Done(found = 7, missing = 39),
            FindCoversStatus.from(listOf(info(WorkInfo.State.SUCCEEDED)), record(done, 46, 46, 7, missing = 39))
        )
        assertEquals(FindCoversStatus.Done(7, 39), FindCoversStatus.from(emptyList(), record(done, 46, 46, 7, missing = 39)))
        assertEquals(
            FindCoversStatus.Stopped(found = 3, missing = 31),
            FindCoversStatus.from(listOf(info(WorkInfo.State.CANCELLED)), record(stopped, 12, 34, 3, missing = 31))
        )
        assertEquals(FindCoversStatus.Stopped(3, 31), FindCoversStatus.from(emptyList(), record(stopped, 12, 34, 3, missing = 31)))
        // Cancelled before the record was closed (the process died): stopped, with what the record counted.
        assertEquals(
            FindCoversStatus.Stopped(3, 31),
            FindCoversStatus.from(listOf(info(WorkInfo.State.CANCELLED)), record(running, 12, 34, 3))
        )
        // Cancelled while still queued: nothing searched, the number still without a cover is unknown.
        assertEquals(
            FindCoversStatus.Stopped(0, -1),
            FindCoversStatus.from(listOf(info(WorkInfo.State.CANCELLED)), record(running, 0, -1, 0))
        )
        // A run finished by an older version (no record): its output.
        val out = Data.Builder().putInt(MissingCoversWorker.KEY_FOUND, 3).putInt(MissingCoversWorker.KEY_MISSING, 2).build()
        assertEquals(
            FindCoversStatus.Done(found = 3, missing = 2),
            FindCoversStatus.from(listOf(info(WorkInfo.State.SUCCEEDED, out)), null)
        )
        assertEquals(FindCoversStatus.Idle, FindCoversStatus.from(listOf(info(WorkInfo.State.CANCELLED)), null))
        assertEquals(FindCoversStatus.Idle, FindCoversStatus.from(listOf(info(WorkInfo.State.FAILED)), null))
    }

    @Test fun `progress counts the albums still without a cover and the ones handled`() = runTest {
        val art = art(StandardTestDispatcher(testScheduler), backgroundScope)
        db.tracks().upsert(
            listOf(
                row("/m/Obscure", "01 a.flac", "Nobody", "Obscure"),
                row("/m/Rumours", "02 Dreams.flac", "Fleetwood Mac", "Rumours"),
                // A folder image and a cover found earlier: not counted.
                row("/m/Blue Train", "01 Blue Train.flac", "John Coltrane", "Blue Train"),
                row("/m/Mezzanine", "01 Angel.flac", "Massive Attack", "Mezzanine")
            )
        )
        folders["/m/Blue Train"] = listOf("01 Blue Train.flac", "folder.jpg")
        cache().putMiss("Fleetwood Mac", "Rumours")
        cache().putHit("Massive Attack", "Mezzanine", png)
        server.enqueue(MockResponse.Builder().code(200).body("""{"release-groups":[]}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"artists":[]}""").build())
        server.enqueue(group("rg-r", "Rumours", "Fleetwood Mac"))
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(png)).build())
        now += 60_000
        val seen = mutableListOf<MissingCovers.Progress>()

        val summary = MissingCovers(db.tracks(), art, cache(), onlineOn = { online }).findAgain(since = now) { seen += it }

        assertEquals(MissingCovers.Summary(found = 1, missing = 1, pending = 0), summary)
        assertEquals(
            listOf(MissingCovers.Progress(0, 2, 0), MissingCovers.Progress(1, 2, 0), MissingCovers.Progress(2, 2, 1)),
            seen
        )
    }

    @Test fun `switching off between albums stops the run`() = runTest {
        val art = art(StandardTestDispatcher(testScheduler), backgroundScope)
        db.tracks().upsert(
            listOf(row("/m/Obscure", "01 a.flac", "Nobody", "Obscure"), row("/m/Rumours", "02 Dreams.flac", "Fleetwood Mac", "Rumours"))
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"release-groups":[]}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"artists":[]}""").build())
        now += 60_000

        val summary = MissingCovers(db.tracks(), art, cache(), onlineOn = { online }).findAgain(since = now) {
            if (it.processed == 1) online = false
        }

        assertEquals(MissingCovers.Summary(found = 0, missing = 1, pending = 0, stopped = true), summary)
        assertEquals(2, server.requestCount)
    }

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun deps(
        onlineOn: () -> Boolean = {
            true
        },
        find: suspend (suspend (MissingCovers.Progress) -> Unit) -> MissingCovers.Summary
    ) = MissingCoversWorker.Deps(onlineOn = { onlineOn() }, onlineAllowed = { onlineOn() }, findAgain = { _, p -> find(p) })

    @Test fun `progress and the final status carry across a retry and persist`() = runTest {
        val store = MissingCoversWorker.store(context)
        // Attempt 1: 46 albums without a cover; 10 handled, 3 found, then a source backs off.
        val first = deps { p ->
            p(MissingCovers.Progress(0, 46, 0))
            p(MissingCovers.Progress(10, 46, 3))
            MissingCovers.Summary(found = 3, missing = 38, pending = 5)
        }
        assertEquals(ListenableWorker.Result.retry(), worker(0, since = 5_000, first).doWork())
        assertEquals(progressOf(10, 46, 3), progress.last())
        // Waiting for the retry: the counts stay on screen.
        assertEquals(FindCoversStatus.Running(10, 46, 3), FindCoversStatus.from(listOf(info(WorkInfo.State.ENQUEUED)), store.record()))
        // Attempt 2: the 3 found are cached hits now, so 43 are left; it finds 2 more.
        val second = deps { p ->
            p(MissingCovers.Progress(0, 43, 0))
            p(MissingCovers.Progress(43, 43, 2))
            MissingCovers.Summary(found = 2, missing = 41, pending = 0)
        }
        assertEquals(done(found = 5, missing = 41), worker(1, since = 5_000, second).doWork())
        assertEquals(listOf(progressOf(3, 46, 3), progressOf(46, 46, 5)), progress.takeLast(2))
        assertEquals(FindCoversStatus.Done(5, 41), FindCoversStatus.from(listOf(info(WorkInfo.State.SUCCEEDED)), store.record()))
        // Leaving and coming back, or a restart after WorkManager pruned the work: the final status is still there.
        assertEquals(FindCoversStatus.Done(5, 41), FindCoversStatus.from(emptyList(), MissingCoversWorker.store(context).record()))
    }

    @Test fun `switching off mid-run ends it as stopped with its counts`() = runTest {
        var on = true
        val d = deps({ on }) { p ->
            p(MissingCovers.Progress(0, 10, 0))
            p(MissingCovers.Progress(4, 10, 2))
            on = false
            MissingCovers.Summary(found = 2, missing = 1, pending = 1)
        }
        assertEquals(ListenableWorker.Result.Success::class, worker(0, since = 5_000, d).doWork()::class)
        assertEquals(
            FindCoversStatus.Stopped(found = 2, missing = 8),
            FindCoversStatus.from(listOf(info(WorkInfo.State.SUCCEEDED)), MissingCoversWorker.store(context).record())
        )
    }

    @Test fun `a new run resets the counts`() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val store = MissingCoversWorker.store(context)
        worker(
            0,
            since = 5_000,
            deps { p ->
                p(MissingCovers.Progress(0, 46, 0))
                p(MissingCovers.Progress(46, 46, 7))
                MissingCovers.Summary(found = 7, missing = 39, pending = 0)
            }
        ).doWork()
        assertEquals(FindCoversStatus.Done(7, 39), FindCoversStatus.from(emptyList(), store.record()))

        // A tap starts a new run: the last summary goes and the counters start from zero.
        MissingCoversWorker.enqueue(context, wifiOnly = true, now = 9_000)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(MissingCoversWorker.NAME).get()
        assertEquals(FindCoversStatus.Running(), FindCoversStatus.from(infos, store.record()))
        worker(
            0,
            since = 9_000,
            deps { p ->
                p(MissingCovers.Progress(0, 39, 0))
                p(MissingCovers.Progress(39, 39, 1))
                MissingCovers.Summary(found = 1, missing = 38, pending = 0)
            }
        ).doWork()
        assertEquals(listOf(progressOf(0, 39, 0), progressOf(39, 39, 1)), progress.takeLast(2))
        assertEquals(FindCoversStatus.Done(1, 38), FindCoversStatus.from(emptyList(), store.record()))
    }

    @Test fun `stopping mid-run cancels the lookup in flight, caches no miss and keeps the covers found`() = runBlocking {
        // A read timeout far above the bound: only a cancelled call ends in time.
        val client = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val art = art(Dispatchers.IO, scope, client)
        db.tracks().upsert(
            listOf(
                row("/m/A", "01 a.flac", "Alpha", "Aaa"),
                row("/m/B", "01 b.flac", "Beta", "Bbb"),
                row("/m/C", "01 c.flac", "Gamma", "Ccc")
            )
        )
        server.enqueue(group("rg-a", "Aaa", "Alpha"))
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(png)).build())
        // Album B's search hangs: the stop comes while it is in flight.
        server.enqueue(MockResponse.Builder().code(200).body("""{"release-groups":[]}""").headersDelay(15, TimeUnit.SECONDS).build())
        now += 60_000
        val covers = MissingCovers(db.tracks(), art, cache(), onlineOn = { true })
        val w = worker(0, since = now, MissingCoversWorker.Deps({ true }, { true }, covers::findAgain))
        val run = launch(Dispatchers.IO) { w.doWork() }
        repeat(3) { assertNotNull("request ${it + 1} reached the server", server.takeRequest(10, TimeUnit.SECONDS)) }

        // What cancelUniqueWork does to a running CoroutineWorker: its coroutine is cancelled.
        val stop = System.nanoTime()
        run.cancel()
        withTimeout(20_000) { run.join() }
        val tookMs = (System.nanoTime() - stop) / 1_000_000
        assertTrue("the run ended $tookMs ms after the stop", tookMs < 1_500)
        // The call itself is cancelled, not just abandoned: it leaves OkHttp's running calls within the same budget.
        val deadline = stop + 1_500_000_000L
        while (client.dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("the HTTP call itself is cancelled", 0, client.dispatcher.runningCallsCount())
        Thread.sleep(300)
        assertEquals("no album is asked after the stop", 3, server.requestCount)
        assertEquals("the interrupted album caches no miss", CachedCover.Unknown, cache().get("Beta", "Bbb"))
        assertEquals(CachedCover.Unknown, cache().get("Gamma", "Ccc"))
        assertTrue("the cover found before the stop stays", cache().get("Alpha", "Aaa") is CachedCover.Hit)
        assertEquals(progressOf(1, 3, 1), progress.last())
        assertEquals(
            FindCoversStatus.Stopped(found = 1, missing = 2),
            FindCoversStatus.from(listOf(info(WorkInfo.State.CANCELLED)), MissingCoversWorker.store(context).record())
        )
        scope.cancel()
    }
}
