package org.seion.playback

import android.os.SystemClock
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * When the playback service ends itself (pure, unit-tested). "Engaged" is Media3's own foreground rule
 * (`MediaNotificationManager.isAnySessionUserEngaged`): wherever Seion keeps running, Media3 keeps the foreground
 * service, so the two never disagree in a way that stops music.
 */
object ServiceLifecycle {
    /** After Stop, the end of the queue or an empty player, with Seion's UI gone: just enough for the last save. */
    const val STOPPED_GRACE_MS = 3_000L

    /** Paused with Seion's UI gone: how long the paused card stays on the lock screen and in the shade. */
    const val PAUSED_GRACE_MS = 60_000L

    /** Playing, buffering to play, or held only by a transient focus loss (a call keeps playWhenReady): never stop. */
    fun engaged(playWhenReady: Boolean, playbackState: Int): Boolean =
        playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

    /** Swiping Seion from Recents ends the service unless it is engaged. */
    fun stopOnTaskRemoved(playWhenReady: Boolean, playbackState: Int, itemCount: Int): Boolean =
        itemCount == 0 || !engaged(playWhenReady, playbackState)

    /** null = keep running; else end the service after this many ms. Only counts while no Seion UI is connected. */
    fun idleGraceMs(playWhenReady: Boolean, playbackState: Int, itemCount: Int, uiConnected: Boolean): Long? = when {
        uiConnected -> null
        itemCount > 0 && engaged(playWhenReady, playbackState) -> null
        itemCount == 0 || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED -> STOPPED_GRACE_MS
        else -> PAUSED_GRACE_MS
    }
}

/**
 * A wake-up for a frozen process, so a pending stop still happens while Seion is frozen: with another app in
 * front, Android's cached-app freezer freezes the paused process ~15 s after its foreground service ends, and no timer
 * of its own runs until something thaws it. An alarm's broadcast does. [arm] replaces a pending one.
 */
interface IdleAlarm {
    fun arm(delayMs: Long)

    fun cancel()

    object None : IdleAlarm {
        override fun arm(delayMs: Long) {}

        override fun cancel() {}
    }
}

/**
 * The service's idle countdown (pure enough for virtual-time tests). [update] is called with the current grace (null = engaged or UI connected) on every
 * player event and UI connect/disconnect; when a countdown runs out and [currentGrace] still says idle, [onExpire] gets
 * the grace that ran out.
 *
 * A countdown of at least [ALARM_MIN_GRACE_MS] also arms [alarm] (the paused grace; the 3 s one ends long before the
 * freezer acts), cancelled with the countdown. A countdown that fires more than [OVERDUE_MS] late ([clock], elapsed
 * realtime) was frozen and has just been thawed, possibly by a command queued behind it (a Play on the stale card): it
 * waits [SETTLE_MS] so that command runs first, then checks again.
 */
class IdleCountdown(
    private val scope: CoroutineScope,
    private val currentGrace: () -> Long?,
    private val alarm: IdleAlarm = IdleAlarm.None,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val onExpire: (Long) -> Unit
) {
    private var job: Job? = null
    private var armed: Long? = null
    private var armedAt = 0L
    private var alarmArmed = false

    /**
     * Keeps a running countdown when the grace is unchanged: a metadata-only playlist replace (an online cover or an
     * identification for a queued track) or a READY/BUFFERING flicker while paused is not a new pause. Restarts only
     * when the kind of idleness changes (paused <-> stopped/empty), and cancels when engaged or the UI is back.
     */
    fun update(grace: Long?) {
        if (grace != null && grace == armed && job?.isActive == true) return
        cancel()
        if (grace == null) return
        armed = grace
        armedAt = clock()
        if (grace >= ALARM_MIN_GRACE_MS) {
            alarm.arm(grace)
            alarmArmed = true
        }
        job =
            scope.launch {
                delay(grace)
                if (clock() - armedAt - grace > OVERDUE_MS) delay(SETTLE_MS)
                // Re-check at fire time; a Play handled before this point (same main thread) has cancelled it anyway.
                expireIfIdle(grace)
            }
    }

    /**
     * The alarm went off (its broadcast thawed the process, or it came while Seion runs normally): a countdown that is
     * due ends after [SETTLE_MS] if Seion is still idle; one that is not due yet arms the alarm again for the rest;
     * otherwise nothing (the countdown was cancelled).
     */
    fun onAlarm(): Boolean {
        val grace = armed ?: return false
        alarmArmed = false
        if (job?.isActive != true) return false
        val left = grace - (clock() - armedAt)
        if (left > 0) {
            // Early (an inexact alarm may come anywhere in its window): wait again for the rest.
            alarm.arm(left)
            alarmArmed = true
            return false
        }
        scope.launch {
            delay(SETTLE_MS)
            if (armed == grace) expireIfIdle(grace)
        }
        return true
    }

    private fun expireIfIdle(grace: Long) {
        if (armed != grace || currentGrace() == null) return
        armed = null
        if (alarmArmed) {
            alarm.cancel()
            alarmArmed = false
        }
        onExpire(grace)
    }

    /**
     * A seek while paused (from the lock-screen card, the shade or a controller) shows that someone is still there: the
     * paused countdown starts again from the full grace (still 60 s). Only the paused grace restarts; after Stop, the end
     * of the queue or with Seion engaged, a seek changes nothing.
     */
    fun restartOnUserSeek(grace: Long?) {
        if (grace != ServiceLifecycle.PAUSED_GRACE_MS) return
        cancel()
        update(grace)
    }

    fun cancel() {
        job?.cancel()
        job = null
        armed = null
        if (alarmArmed) {
            alarm.cancel()
            alarmArmed = false
        }
    }

    companion object {
        /** Only countdowns this long arm the alarm (the freezer acts ~10–15 s after the foreground service ends). */
        const val ALARM_MIN_GRACE_MS = 10_000L

        /** A countdown later than this was frozen. */
        const val OVERDUE_MS = 2_000L

        /** How long a thawed countdown lets queued commands run before it ends the service. */
        const val SETTLE_MS = 500L
    }
}

/**
 * Writes the saved queue: in the background while the service runs, and synchronously when the service closes or Stop
 * is pressed (a swipe from Recents can kill the process right after, and the service's scope is cancelled in
 * onDestroy, which would drop a pending background write). Background writes run one at a time, in order (a position
 * saved before a full save never lands after it).
 */
class QueuePersister(
    private val store: QueueStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
) {
    /** The paths last written to (or restored from) queue.json; read and written on [io] only, and by [saveNow]. */
    @Volatile private var writtenPaths: List<String>? = null

    /**
     * A routine save (the queue changed, pause): off the main thread. The paths are rewritten only when they changed
     * since the last write; otherwise (a cover found online, a chosen cover, a new shuffle order: the timeline changes,
     * the paths don't) only the position.
     */
    fun save(queue: SavedQueue) {
        scope.launch(io) {
            if (queue.paths == writtenPaths) {
                QueueSplit.position(queue)?.let(store::savePosition)
            } else if (store.save(queue)) {
                writtenPaths = queue.paths
            }
        }
    }

    /** The queue was restored from these saved paths unchanged: the file already holds them. */
    fun restored(paths: List<String>) {
        scope.launch(io) { writtenPaths = paths }
    }

    /** The position only (every few seconds while playing, a transition, a seek while paused): off the main thread. */
    fun savePosition(position: SavedPosition) {
        scope.launch(io) { store.savePosition(position) }
    }

    /** Writes now, on the calling thread (Stop, shutdown, onDestroy); the time it took in ms, or null if it failed. */
    fun saveNow(queue: SavedQueue): Long? {
        val start = SystemClock.elapsedRealtime()
        if (!store.save(queue)) return null
        writtenPaths = queue.paths
        return SystemClock.elapsedRealtime() - start
    }
}
