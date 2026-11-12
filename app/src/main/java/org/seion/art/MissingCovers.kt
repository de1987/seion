package org.seion.art

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import org.seion.SeionApp
import org.seion.library.TrackDao
import org.seion.library.TrackEntity

/**
 * Online covers for tracks that have none, outside of a screen asking for them: once after a track is identified
 * (its new names make a new query) and on "Find missing covers again". Only tracks without embedded art or a folder
 * image are searched; a cover found online earlier is never asked again. Every request goes through the resolver
 * (the online switch, "Wi-Fi only", the per-source limiters and back-off).
 */
class MissingCovers(
    private val dao: TrackDao,
    private val art: ArtRepository,
    private val cache: OnlineCoverCache,
    /** The "Look up missing covers and track info online" switch. */
    private val onlineOn: suspend () -> Boolean
) {
    /**
     * What one "Find missing covers again" run did, counted per album (artist + album). [stopped]: the online switch
     * went off during the run, which ended it early.
     */
    data class Summary(val found: Int, val missing: Int, val pending: Int, val stopped: Boolean = false)

    /** A run's progress: [processed] of the [total] albums still without a cover at its start, [found] of them found. */
    data class Progress(val processed: Int, val total: Int, val found: Int)

    /** After an identification hit: one search with the row's new names, when the track has no cover. */
    suspend fun afterIdentify(t: TrackEntity): CoverSearch {
        val artist = artistOf(t) ?: return CoverSearch.NONE
        val album = t.album?.takeIf { it.isNotBlank() } ?: return CoverSearch.NONE
        return art.findOnline(ArtRef(t.path, t.folder, artist, album)).also { Log.i(TAG, "cover after identification: $it") }
    }

    private class Album(val artist: String, val album: String, val bare: List<TrackEntity>)

    /**
     * "Find missing covers again": misses written before [since] are forgotten (on disk and in memory), then every
     * album with a track that has no embedded art, no folder image and no cover found online is asked once.
     * [Summary.pending] counts albums left unanswered (offline, a source backing off): the worker retries those.
     * [onProgress] hears the total first (the albums still without a cover once the misses are cleared), then once per
     * album handled. A cancelled coroutine (the run stopped) ends between albums, and a lookup in flight is cancelled
     * with its HTTP call ([OnlineHttp.fetch]): that album caches no miss, the covers found before stay.
     */
    suspend fun findAgain(since: Long, onProgress: suspend (Progress) -> Unit = {}): Summary {
        if (!onlineOn()) return Summary(0, 0, 0)
        val cleared = cache.clearMisses(writtenBefore = since)
        art.forgetOnlineMisses()
        val (albums, todo) = albumsWithoutCover()
        val s = search(todo, onProgress)
        Log.i(
            TAG,
            "find missing covers: $cleared misses cleared, $albums albums, ${todo.size} without a cover, " +
                "found=${s.found} missing=${s.missing} pending=${s.pending} stopped=${s.stopped}"
        )
        return s
    }

    /** What one background prefetch did: as [Summary], plus the albums left for the next run ([remaining]). */
    data class PrefetchSummary(val summary: Summary, val remaining: Int)

    /**
     * The prefetch after a scan ([CoverPrefetchWorker]): like [findAgain], but only for the albums of tracks the scan added
     * (row ids above [afterId]), nothing is forgotten (a "no cover" answer stays for its 30 days, so only albums never asked
     * are searched), the most recently added first, and at most [limit] albums per run.
     */
    suspend fun prefetch(afterId: Long, limit: Int): PrefetchSummary {
        if (!onlineOn()) return PrefetchSummary(Summary(0, 0, 0), 0)
        val (albums, candidates) = albumsWithoutCover()
        // Only the albums of the files the scan added; newest first within a long list.
        val unasked =
            candidates
                .filter { a -> a.bare.any { it.id > afterId } && cache.get(a.artist, a.album) == CachedCover.Unknown }
                .sortedByDescending { a -> a.bare.maxOf { it.dateAdded } }
        val s = search(unasked.take(limit))
        val remaining = (unasked.size - limit).coerceAtLeast(0)
        Log.i(
            TAG,
            "prefetch covers: $albums albums, ${unasked.size} never asked, found=${s.found} missing=${s.missing} " +
                "pending=${s.pending} stopped=${s.stopped} remaining=$remaining"
        )
        return PrefetchSummary(s, remaining)
    }

    /**
     * Every album (artist + album) with a track that has no embedded art, no folder image and no cover found online, and
     * the number of albums looked at.
     */
    private suspend fun albumsWithoutCover(): Pair<Int, List<Album>> {
        val albums =
            dao.coverCandidates().mapNotNull { t ->
                artistOf(t)?.let { a -> t.album?.takeIf { it.isNotBlank() }?.let { b -> Triple(a, b, t) } }
            }
                .groupBy { (a, b) -> OnlineCoverCache.key(a, b) }
        val folderArt = HashMap<String, Boolean>()
        val todo =
            albums.values.mapNotNull { rows ->
                val (artist, album) = rows.first()
                // A cover found online earlier is never asked again (nor replaced).
                if (cache.get(artist, album) is CachedCover.Hit) return@mapNotNull null
                val bare = rows.map {
                    it.third
                }.filter { t -> !folderArt.getOrPut(t.folder) { art.hasLocal(ArtRef(null, t.folder, artist, album)) } }
                bare.takeIf { it.isNotEmpty() }?.let { Album(artist, album, it) }
            }
        return albums.size to todo
    }

    /** Asks once per album in [todo], in order; stops between albums when cancelled or switched off. */
    private suspend fun search(todo: List<Album>, onProgress: suspend (Progress) -> Unit = {}): Summary {
        var found = 0
        var missing = 0
        var pending = 0
        var stopped = false
        onProgress(Progress(0, todo.size, 0))
        for ((i, a) in todo.withIndex()) {
            currentCoroutineContext().ensureActive() // stopped: no further album
            if (!onlineOn()) { // switched off during the run: stop asking
                stopped = true
                break
            }
            val sample = a.bare.first()
            when (art.findOnline(ArtRef(sample.path, sample.folder, a.artist, a.album), a.bare.map { it.path })) {
                CoverSearch.FOUND -> found++
                CoverSearch.LOCAL -> Unit
                CoverSearch.NONE -> if (cache.get(a.artist, a.album) == CachedCover.Miss) missing++ else pending++
            }
            onProgress(Progress(i + 1, todo.size, found))
        }
        return Summary(found, missing, pending, stopped)
    }

    private fun artistOf(t: TrackEntity): String? = (t.albumArtist ?: t.artist)?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "SeionArt"
    }
}

/** "Find missing covers again" in the background (unique work [NAME], KEEP: repeated taps never stack). */
class MissingCoversWorker internal constructor(context: Context, params: WorkerParameters, private val deps: Deps?) :
    CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, null)

    /** What a run needs: the app container in the app, fakes in tests. */
    internal class Deps(
        /** The online switch. */
        val onlineOn: suspend () -> Boolean,
        /** The online gate (switch, "Wi-Fi only", network). */
        val onlineAllowed: suspend () -> Boolean,
        val findAgain: suspend (since: Long, onProgress: suspend (MissingCovers.Progress) -> Unit) -> MissingCovers.Summary
    )

    private fun containerDeps(): Deps {
        val c = (applicationContext as SeionApp).container
        return Deps({ c.settings.current().onlineCovers }, c::onlineAllowed, c.missingCovers::findAgain)
    }

    override suspend fun doWork(): Result {
        val d = deps ?: containerDeps()
        val since = inputData.getLong(KEY_SINCE, System.currentTimeMillis())
        val store = FindCoversStore(applicationContext)
        // The run's counts so far (keyed by its tap time): they carry across WorkManager's retries and system stops.
        val start = store.begin(since)
        val foundBefore = start.found
        var last = start
        // The online switch off: no request at all, nothing cleared; the run ends as stopped with what it found.
        if (!d.onlineOn()) return stopped(store, last)
        try {
            val s =
                d.findAgain(since) { p ->
                    // A retry only sees the albums the attempts before it did not find: it continues their count.
                    val total = if (last.total >= 0) last.total else p.total + foundBefore
                    last = last.copy(processed = minOf(total, foundBefore + p.processed), total = total, found = foundBefore + p.found)
                    store.save(last)
                    setProgress(workDataOf(KEY_PROCESSED to last.processed, KEY_TOTAL to last.total, KEY_FOUND to last.found))
                }
            last = last.copy(found = foundBefore + s.found)
            store.save(last)
            if (s.stopped || !d.onlineOn()) return stopped(store, last)
            // Albums left unanswered by a transient failure: try again later (WorkManager's back-off); the misses this run
            // wrote stay, because a retry only clears misses older than the tap. The covers found so far are kept in the
            // store: the retry skips them (they are cached hits now), so its own count alone would read "none found".
            if (s.pending > 0 && runAttemptCount < MAX_ATTEMPTS && d.onlineAllowed()) return Result.retry()
            val missing = s.missing + s.pending
            store.save(last.copy(phase = FindCoversRecord.Phase.DONE, missing = missing))
            return Result.success(workDataOf(KEY_FOUND to last.found, KEY_MISSING to missing))
        } catch (e: CancellationException) {
            // Stopped (a tap on the row, or the system pausing the work, which then runs it again): the final status keeps
            // the counts; the covers found stay. A paused run shows as running again while WorkManager holds it.
            store.save(last.stoppedNow())
            throw e
        }
    }

    private fun stopped(store: FindCoversStore, last: FindCoversRecord): Result {
        val r = last.stoppedNow()
        store.save(r)
        return Result.success(workDataOf(KEY_FOUND to r.found, KEY_MISSING to r.missing, KEY_STOPPED to true))
    }

    companion object {
        const val NAME = "find-missing-covers"
        const val KEY_SINCE = "since"
        const val KEY_FOUND = "found"
        const val KEY_MISSING = "missing"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_STOPPED = "stopped"
        private const val MAX_ATTEMPTS = 3

        /**
         * Queues one run; while one is queued or running, further taps are ignored (KEEP). Called only when no run is
         * active (a tap on a running row stops it): the last run's summary goes and the counts start from zero.
         */
        fun enqueue(context: Context, wifiOnly: Boolean, now: Long = System.currentTimeMillis()) {
            FindCoversStore(context).save(FindCoversRecord.start(now))
            val constraints =
                Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
            val request =
                OneTimeWorkRequestBuilder<MissingCoversWorker>()
                    .setConstraints(constraints)
                    .setInputData(workDataOf(KEY_SINCE to now))
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** A tap on the running row: the worker stops between albums and cancels the lookup in flight. */
        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }

        fun store(context: Context) = FindCoversStore(context)

        /** The row's state: the unique work (live progress) and the run's persisted record (counts, final status). */
        fun status(context: Context): Flow<FindCoversStatus> =
            combine(WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NAME), store(context).records()) { infos, record ->
                FindCoversStatus.from(infos, record)
            }
    }
}

/** The Settings row's subtitle state, from the unique work's infos and the run's record (pure, unit-tested). */
sealed interface FindCoversStatus {
    data object Idle : FindCoversStatus

    /** [total] < 0: not known yet (indeterminate). */
    data class Running(val processed: Int = 0, val total: Int = -1, val found: Int = 0) : FindCoversStatus

    data class Done(val found: Int, val missing: Int) : FindCoversStatus

    /** [missing] < 0: stopped before the albums were counted. */
    data class Stopped(val found: Int, val missing: Int) : FindCoversStatus

    companion object {
        fun from(infos: List<WorkInfo>, record: FindCoversRecord?): FindCoversStatus {
            val info = infos.lastOrNull()
            if (info != null && !info.state.isFinished) {
                val p = info.progress
                val total = p.getInt(MissingCoversWorker.KEY_TOTAL, -1)
                if (total >= 0) {
                    return Running(p.getInt(MissingCoversWorker.KEY_PROCESSED, 0), total, p.getInt(MissingCoversWorker.KEY_FOUND, 0))
                }
                // No live progress (queued, between two attempts): the counts the record keeps (the tap resets it).
                return record?.takeIf { it.total >= 0 }?.let { Running(it.processed, it.total, it.found) } ?: Running()
            }
            // Finished, cancelled, or pruned by WorkManager: the persisted final status until the next run starts.
            if (record != null) {
                return when (record.phase) {
                    FindCoversRecord.Phase.DONE -> Done(record.found, record.missing)
                    FindCoversRecord.Phase.STOPPED -> Stopped(record.found, record.missing)
                    // The process died before the record was closed.
                    FindCoversRecord.Phase.RUNNING -> record.stoppedNow().let { Stopped(it.found, it.missing) }
                }
            }
            // A run finished by a version without the record.
            if (info?.state == WorkInfo.State.SUCCEEDED) {
                return Done(
                    info.outputData.getInt(MissingCoversWorker.KEY_FOUND, 0),
                    info.outputData.getInt(MissingCoversWorker.KEY_MISSING, 0)
                )
            }
            return Idle
        }
    }
}

/**
 * One "Find missing covers again" run, keyed by its tap time [since]: [processed] of [total] albums (total < 0: not
 * counted yet), [found], and once it ends its [phase] and the albums still [missing] a cover.
 */
data class FindCoversRecord(val since: Long, val phase: Phase, val processed: Int, val total: Int, val found: Int, val missing: Int) {
    enum class Phase { RUNNING, STOPPED, DONE }

    /** Ended early: every album not found is still without a cover. */
    fun stoppedNow() = copy(phase = Phase.STOPPED, missing = if (total >= 0) maxOf(0, total - found) else -1)

    companion object {
        fun start(since: Long) = FindCoversRecord(since, Phase.RUNNING, processed = 0, total = -1, found = 0, missing = -1)
    }
}

/**
 * The run's record in the `find_missing_covers` SharedPreferences (a per-run store): written by the
 * worker as it goes, so the counts survive WorkManager's retries and the final status survives leaving Seion and a
 * restart, until the next tap starts a new run.
 */
class FindCoversStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(): FindCoversRecord? {
        if (!prefs.contains(SINCE)) return null
        val phase = FindCoversRecord.Phase.entries.firstOrNull { it.name == prefs.getString(PHASE, null) } ?: FindCoversRecord.Phase.RUNNING
        return FindCoversRecord(
            since = prefs.getLong(SINCE, 0),
            phase = phase,
            processed = prefs.getInt(PROCESSED, 0),
            total = prefs.getInt(TOTAL, -1),
            found = prefs.getInt(FOUND, 0),
            missing = prefs.getInt(MISSING, -1)
        )
    }

    /** The record of the run tapped at [since], marked running: a new run (another since) starts from zero. */
    fun begin(since: Long): FindCoversRecord {
        val r = record()?.takeIf { it.since == since }?.copy(phase = FindCoversRecord.Phase.RUNNING, missing = -1)
            ?: FindCoversRecord.start(since)
        save(r)
        return r
    }

    fun save(r: FindCoversRecord) = prefs.edit(commit = true) {
        putLong(SINCE, r.since)
        putString(PHASE, r.phase.name)
        putInt(PROCESSED, r.processed)
        putInt(TOTAL, r.total)
        putInt(FOUND, r.found)
        putInt(MISSING, r.missing)
    }

    /** The record now and after every change. */
    fun records(): Flow<FindCoversRecord?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(record()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(record())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()

    private companion object {
        const val PREFS = "find_missing_covers"
        const val SINCE = "since"
        const val FOUND = "found"
        const val PHASE = "phase"
        const val PROCESSED = "processed"
        const val TOTAL = "total"
        const val MISSING = "missing"
    }
}
