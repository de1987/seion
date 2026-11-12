package org.seion.library

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.seion.SeionApp
import org.seion.art.CoverPrefetch
import org.seion.meta.IdentifyWorker

/** Background library scan (WorkManager, so it survives the UI going away). */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SeionApp
        val c = app.container
        // Without full file access every listing comes back empty: scanning would wipe the index, so skip.
        if (!Environment.isExternalStorageManager()) {
            Log.w("SeionScan", "scan skipped: no full file access")
            return Result.success()
        }
        val roots = c.storageRoots.effectiveFolders(c.settings.current().musicFolders)
        val lastIdBefore = c.db.tracks().maxId()
        val result = c.scanner.scan(roots)
        c.scanStamp.mark()
        // Rows left for online identification: queue the lookup when the switch allows it.
        val s = c.settings.current()
        if (s.onlineCovers && c.db.tracks().pendingLookupCount() > 0) IdentifyWorker.enqueue(applicationContext, s.onlineWifiOnly)
        // New files: fetch the covers their albums lack in the background, on Wi-Fi while charging.
        val newTracks = if (result.read > 0) c.db.tracks().maxId() > lastIdBefore else false
        if (CoverPrefetch.shouldSchedule(s.onlineCovers, newTracks)) CoverPrefetch.enqueue(applicationContext, afterId = lastIdBefore)
        return Result.success(workDataOf(KEY_FOUND to result.found, KEY_READ to result.read))
    }

    companion object {
        const val KEY_FOUND = "found"
        const val KEY_READ = "read"
    }
}

class ScanScheduler(private val context: Context) {
    private val wm get() = WorkManager.getInstance(context)

    /** [force] replaces a running scan (Rescan now, folder list changed); otherwise an existing one is kept. */
    fun scan(force: Boolean) {
        val request = OneTimeWorkRequestBuilder<ScanWorker>().build()
        wm.enqueueUniqueWork(NAME, if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    val isScanning: Flow<Boolean> =
        wm.getWorkInfosForUniqueWorkFlow(NAME).map { infos ->
            infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }

    companion object {
        private const val NAME = "library-scan"
    }
}
