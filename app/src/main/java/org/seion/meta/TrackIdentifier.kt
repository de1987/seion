package org.seion.meta

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import org.seion.SeionApp
import org.seion.art.LookupCancelled
import org.seion.library.TrackDao
import org.seion.library.TrackEntity

/**
 * Identifies untagged tracks online, once each: junk names and files without a derivable artist are
 * settled locally with no request; otherwise MusicBrainz (with the duration), and a miss there is recorded as not found.
 * Only the missing fields are filled, in Seion's DB; the audio files are never written. [allowed] is the online gate, re-checked
 * before every request.
 */
class TrackIdentifier(
    private val dao: TrackDao,
    private val source: RecordingSource,
    private val allowed: suspend () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    /** Before every request (drives the one-time "looked up online" notice). */
    private val onRequest: () -> Unit = {},
    /** After a match was stored, with the updated row: the cover search with the new names, the queue refresh. */
    private val onIdentified: suspend (TrackEntity) -> Unit = {}
) {
    enum class Outcome { FOUND, NOT_FOUND, LOCAL, STOPPED }

    /** Processes eligible rows oldest first until none is left or the gate closes; returns how many were settled. */
    suspend fun runPending(): Int {
        var settled = 0
        val skipped = HashSet<Long>()
        while (true) {
            val batch = dao.pendingLookups(BATCH).filter { it.id !in skipped && IdentifyPolicy.eligible(it) }
            if (batch.isEmpty()) return settled
            for (t in batch) {
                when (identify(t)) {
                    Outcome.STOPPED -> return settled
                    else -> settled++
                }
                skipped += t.id
            }
        }
    }

    suspend fun identify(t: TrackEntity): Outcome {
        val p = IdentifyPolicy.parse(t)
        if (p.junk || p.artist == null || t.durationMs <= 0) {
            dao.upsert(listOf(IdentifyPolicy.apply(t, null, LOCAL, now())))
            Log.i(TAG, "settled locally (${if (p.junk) "junk name" else "no artist"})")
            return Outcome.LOCAL
        }
        if (!allowed()) return Outcome.STOPPED
        onRequest()
        val candidates =
            try {
                source.search(p.artist, p.title, t.durationMs)
            } catch (_: LookupCancelled) {
                Log.i(TAG, "${source.name}: cancelled (online switched off); the track stays pending")
                return Outcome.STOPPED
            } catch (e: IOException) {
                Log.w(TAG, "${source.name}: failed (${e.message}); the track stays pending")
                return Outcome.STOPPED
            } catch (_: kotlinx.serialization.SerializationException) {
                Log.w(TAG, "${source.name}: unexpected response; the track stays pending")
                return Outcome.STOPPED
            }
        val best = MetadataMatch.best(p, t.durationMs, candidates)
        if (best != null) {
            val updated = IdentifyPolicy.apply(t, best.candidate, source.name, now())
            dao.upsert(listOf(updated))
            Log.i(TAG, "${source.name}: identified (${best.signals} signals, Δ ${best.gapMs} ms)")
            onIdentified(updated)
            return Outcome.FOUND
        }
        Log.i(TAG, "${source.name}: no recording passed")
        dao.upsert(listOf(IdentifyPolicy.apply(t, null, source.name, now())))
        return Outcome.NOT_FOUND
    }

    companion object {
        const val LOCAL = "local"
        private const val TAG = "SeionMeta"
        private const val BATCH = 20
    }
}

/** Runs [TrackIdentifier] in the background (unique work `identify-tracks`, KEEP), behind the online switch. */
class IdentifyWorker internal constructor(context: Context, params: WorkerParameters, private val deps: Deps?) :
    CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, null)

    /** What a run needs: the app container in the app, fakes in tests. */
    internal class Deps(
        /** The online switch. */
        val onlineOn: suspend () -> Boolean,
        /** The online gate (switch, "Wi-Fi only", network). */
        val onlineAllowed: suspend () -> Boolean,
        /** One pass over the pending rows; returns how many were settled. */
        val runPending: suspend () -> Int,
        val pendingCount: suspend () -> Int
    )

    private fun containerDeps(): Deps {
        val c = (applicationContext as SeionApp).container
        return Deps({
            c.settings.current().onlineCovers
        }, c::onlineAllowed, { c.identifier.runPending() }, { c.db.tracks().pendingLookupCount() })
    }

    override suspend fun doWork(): Result {
        val d = deps ?: containerDeps()
        if (!d.onlineOn()) return Result.success()
        val n = d.runPending()
        Log.i("SeionMeta", "identify run: $n settled")
        // Stopped early on a transient failure with rows still pending: try again later (WorkManager's back-off), at most
        // MAX_RUNS runs in all. The rows stay pending; the next scan, the online switch or "Identify again" queue a new run.
        val retry = runAttemptCount + 1 < MAX_RUNS && d.onlineAllowed() && d.pendingCount() > 0
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        const val NAME = "identify-tracks"
        private const val MAX_RUNS = 3

        fun enqueue(context: Context, wifiOnly: Boolean) {
            val constraints =
                Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
            val request = OneTimeWorkRequestBuilder<IdentifyWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
