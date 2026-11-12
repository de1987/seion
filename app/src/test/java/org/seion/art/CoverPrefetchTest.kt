package org.seion.art

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.seion.library.SeionDatabase
import org.seion.library.TagSource
import org.seion.library.TrackEntity

/** Prefetch online covers after a scan: when it is queued, under which constraints, and what a run asks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CoverPrefetchTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: SeionDatabase
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SeionDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `queued only after a scan that added files, and never with online lookups off`() {
        assertTrue(CoverPrefetch.shouldSchedule(onlineOn = true, newTracks = true))
        assertFalse(CoverPrefetch.shouldSchedule(onlineOn = true, newTracks = false)) // nothing new (or only changed files): no run
        assertFalse(CoverPrefetch.shouldSchedule(onlineOn = false, newTracks = true))
    }

    @Test fun `rows a scan inserts get ids above the largest one before it, updated rows keep theirs`() = runTest {
        assertEquals(0L, db.tracks().maxId())
        db.tracks().upsert(listOf(row("/m/a1", "Artist", "Album 1")))
        val before = db.tracks().maxId()
        val old = db.tracks().albumTracks("album 1", 0).first().single()
        db.tracks().upsert(listOf(old.copy(title = "changed"), row("/m/a2", "Artist", "Album 2")))
        assertEquals(old.id, db.tracks().albumTracks("album 1", 0).first().single().id)
        assertTrue(db.tracks().albumTracks("album 2", 0).first().single().id > before)
    }

    @Test fun `Wi-Fi, charging and battery not low, and scans while it waits never stack`() {
        val c = CoverPrefetch.constraints()
        assertEquals(NetworkType.UNMETERED, c.requiredNetworkType)
        assertTrue(c.requiresCharging())
        assertTrue(c.requiresBatteryNotLow())
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        repeat(3) { CoverPrefetch.enqueue(context, afterId = 10L + it) }
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(CoverPrefetch.NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(c, infos.single().constraints)
        // The first run (the smallest id) is the one kept: it covers the later scans' new tracks too.
        // A follow-up for a long list is appended, not dropped.
        CoverPrefetch.enqueue(context, 10L, ExistingWorkPolicy.APPEND_OR_REPLACE)
        assertEquals(2, WorkManager.getInstance(context).getWorkInfosForUniqueWork(CoverPrefetch.NAME).get().size)
    }

    private fun worker(attempt: Int, deps: CoverPrefetchWorker.Deps) = TestListenableWorkerBuilder<CoverPrefetchWorker>(context)
        .setRunAttemptCount(attempt)
        .setInputData(androidx.work.workDataOf(CoverPrefetch.KEY_AFTER_ID to 42L))
        .setWorkerFactory(
            object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    CoverPrefetchWorker(appContext, workerParameters, deps)
            }
        ).build()

    @Test fun `a run retries what got no answer, continues a long list, and does nothing with online off`() = runTest {
        val continued = mutableListOf<Long>()
        var asked = 0
        fun deps(on: Boolean, s: MissingCovers.PrefetchSummary) = CoverPrefetchWorker.Deps(
            onlineOn = { on },
            onlineAllowed = { true },
            prefetch = { afterId, limit ->
                assertEquals(42L, afterId)
                assertEquals(CoverPrefetch.ALBUMS_PER_RUN, limit)
                asked++
                s
            },
            continueLater = { continued += it }
        )
        assertEquals(
            ListenableWorker.Result.retry(),
            worker(0, deps(true, MissingCovers.PrefetchSummary(MissingCovers.Summary(1, 0, 2), 0))).doWork()
        )
        val done = worker(0, deps(true, MissingCovers.PrefetchSummary(MissingCovers.Summary(4, 1, 0), 30))).doWork()
        assertTrue(done is ListenableWorker.Result.Success)
        assertEquals(listOf(42L), continued) // the follow-up keeps the scan's id
        asked = 0
        assertTrue(
            worker(
                0,
                deps(false, MissingCovers.PrefetchSummary(MissingCovers.Summary(0, 0, 0), 0))
            ).doWork() is ListenableWorker.Result.Success
        )
        assertEquals(0, asked)
    }

    private fun row(folder: String, artist: String, album: String, added: Long = 1) = TrackEntity(
        path = "$folder/01.flac", folder = folder, fileName = "01.flac", title = "t", artist = artist, albumArtist = null,
        album = album, albumKey = album.lowercase(), genre = null, year = null, trackNumber = null, discNumber = null,
        durationMs = 200_000, lastModified = 1, sizeBytes = 1, dateAdded = added, hasEmbeddedArt = false, titleSource = TagSource.TAG
    )

    @Test fun `a prefetch asks only the new tracks' albums never asked, newest first, at most the limit, keeping misses`() = runTest {
        val cache = OnlineCoverCache(tmp.newFolder("online"))
        val asked = mutableListOf<String>()
        val resolver = AlbumArtResolver(
            embedded = { null },
            listNames = { emptyList() },
            policy = { ArtPolicy(useFolderImages = true, onlineEnabled = true, wifiOnly = true) },
            network = { NetworkState(connected = true, unmetered = true) },
            onlineSource = object : OnlineCoverSource {
                override suspend fun frontCover(artist: String, album: String): ByteArray? {
                    asked += album
                    return null
                }
            },
            onlineCache = cache
        )
        val art = ArtRepository(resolver)
        db.tracks().upsert(listOf(row("/m/old", "Artist", "Old Album")))
        val before = db.tracks().maxId() // the scan starts
        // Album 5 was added last: it is asked first.
        db.tracks().upsert((1..5).map { row("/m/a$it", "Artist", "Album $it", added = it.toLong()) })
        cache.putMiss("Artist", "Album 1") // asked within 30 days: stays a miss
        val covers = MissingCovers(db.tracks(), art, cache, onlineOn = { true })

        val first = covers.prefetch(before, limit = 3)
        assertEquals(listOf("Album 5", "Album 4", "Album 3"), asked)
        assertFalse("Album 1" in asked)
        assertEquals(1, first.remaining)
        assertEquals(3, first.summary.missing)
        assertEquals(CachedCover.Miss, cache.get("Artist", "Album 1"))
        // The follow-up run takes the rest.
        assertEquals(0, covers.prefetch(before, limit = 3).remaining)
        assertEquals(4, asked.size)
        assertFalse("Old Album" in asked) // the rest of the library is not this scan's
        // Online off: nothing.
        assertEquals(0, MissingCovers(db.tracks(), art, cache, onlineOn = { false }).prefetch(before, 3).summary.found)
        assertEquals(4, asked.size)
    }
}
