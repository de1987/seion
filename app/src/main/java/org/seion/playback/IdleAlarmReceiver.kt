package org.seion.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * The paused countdown's alarm, so the stop works while Seion is frozen: an inexact, non-waking
 * `ELAPSED_REALTIME` alarm at the end of the grace. Its broadcast thaws a process the cached-app freezer has frozen, so
 * the service can end and the stale paused card goes. Inexact: Android may deliver it a little late, and in Doze only at
 * the next maintenance window (nothing runs then anyway); no exact-alarm permission is needed.
 */
class AlarmIdleAlarm(private val context: Context) : IdleAlarm {
    private val pending: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, IdleAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private val alarms: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    /**
     * Android gives an inexact `set()` a window of 0.75 × the delay after its trigger and, with nothing to batch it with,
     * delivers it at the window's end (measured on Android 16: a 60 s alarm came at +105 s). So the trigger
     * is 4/7 of the delay, which puts the window's end at the delay; an earlier delivery finds the countdown not due and
     * arms again for the rest ([IdleCountdown.onAlarm]).
     */
    override fun arm(delayMs: Long) {
        alarms?.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + triggerFor(delayMs), pending)
    }

    override fun cancel() {
        alarms?.cancel(pending)
    }

    companion object {
        /** The trigger whose inexact window (0.75 × itself) ends at [delayMs]; at least 1 s. */
        fun triggerFor(delayMs: Long): Long = (delayMs * 4 / 7).coerceAtLeast(1_000L)
    }
}

/**
 * Not exported; only Seion's own alarm reaches it. It asks the live service to end if its countdown is due and Seion is
 * still idle ([IdleCountdown.onAlarm]); with no service (the process was killed and restarted for this broadcast, or
 * the service already ended) it does nothing.
 */
class IdleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val handled = PlaybackService.onIdleAlarm()
        Log.i("SeionPlayback", "idle alarm: " + if (handled) "the paused countdown is due" else "nothing to do")
    }
}
