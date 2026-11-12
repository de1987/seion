package org.seion.art

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.seion.SeionApp

/**
 * Online covers fetched in the background after a scan, so new albums have their covers before anyone opens them
 * The rules are pure and unit-tested; the run itself is [MissingCovers.prefetch] behind the resolver (the online
 * switch, the per-host limiters of at most one request per second, the back-off).
 */
object CoverPrefetch {
    /** Unique work: a scan while a prefetch is queued or running adds nothing (KEEP). */
    const val NAME = "prefetch-covers"

    /** Albums asked per run; a longer list continues in a follow-up run (appended to the unique work). */
    const val ALBUMS_PER_RUN = 200

    /**
     * After a scan that added files ([newTracks]: rows with ids above the largest one before the scan), with online
     * lookups on. With the switch off nothing is queued at all (the worker would make no request anyway). Only the new
     * files' albums are fetched: the rest of the library is looked up when shown, or by "Find missing covers again".
     */
    fun shouldSchedule(onlineOn: Boolean, newTracks: Boolean): Boolean = onlineOn && newTracks

    const val KEY_AFTER_ID = "after_id"

    /**
     * Only on an unmetered network (Wi-Fi; stricter than either "Wi-Fi only" setting) and while charging with the battery
     * not low: a background download nobody is waiting for never uses mobile data or the battery.
     */
    fun constraints(): Constraints = Constraints
        .Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresCharging(true)
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * Queues a run for the albums of tracks with ids above [afterId]. A run already queued is kept: its own [afterId] is
     * smaller, so it covers these tracks too.
     */
    fun enqueue(context: Context, afterId: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val request =
            OneTimeWorkRequestBuilder<CoverPrefetchWorker>()
                .setConstraints(constraints())
                .setInputData(workDataOf(KEY_AFTER_ID to afterId))
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
    }
}

/** Runs [MissingCovers.prefetch] once the constraints hold ([CoverPrefetch.constraints]). */
class CoverPrefetchWorker internal constructor(context: Context, params: WorkerParameters, private val deps: Deps?) :
    CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, null)

    /** What a run needs: the app container in the app, fakes in tests. */
    internal class Deps(
        val onlineOn: suspend () -> Boolean,
        val onlineAllowed: suspend () -> Boolean,
        val prefetch: suspend (afterId: Long, limit: Int) -> MissingCovers.PrefetchSummary,
        /** Queues the follow-up run for the albums left ([CoverPrefetch.enqueue] with APPEND_OR_REPLACE). */
        val continueLater: (afterId: Long) -> Unit
    )

    private fun containerDeps(): Deps {
        val c = (applicationContext as SeionApp).container
        return Deps(
            { c.settings.current().onlineCovers },
            c::onlineAllowed,
            c.missingCovers::prefetch
        ) { afterId -> CoverPrefetch.enqueue(applicationContext, afterId, ExistingWorkPolicy.APPEND_OR_REPLACE) }
    }

    override suspend fun doWork(): Result {
        val d = deps ?: containerDeps()
        if (!d.onlineOn()) return Result.success()
        // A run queued without the id (none should be) fetches nothing rather than the whole library.
        val afterId = inputData.getLong(CoverPrefetch.KEY_AFTER_ID, Long.MAX_VALUE)
        val p = d.prefetch(afterId, CoverPrefetch.ALBUMS_PER_RUN)
        val s = p.summary
        if (s.stopped || !d.onlineOn()) return Result.success()
        // Albums left unanswered by a transient failure (a source backing off): WorkManager's back-off, at most 3 runs.
        if (s.pending > 0 && runAttemptCount < MAX_ATTEMPTS && d.onlineAllowed()) return Result.retry()
        if (p.remaining > 0) {
            Log.i(TAG, "prefetch covers: ${p.remaining} albums left for the next run")
            d.continueLater(afterId)
        }
        return Result.success(workDataOf(KEY_FOUND to s.found, KEY_REMAINING to p.remaining))
    }

    companion object {
        private const val TAG = "SeionArt"
        const val KEY_FOUND = "found"
        const val KEY_REMAINING = "remaining"
        private const val MAX_ATTEMPTS = 3
    }
}
