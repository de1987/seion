package org.seion.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

/** The service's idle countdown (virtual time). */
class IdleCountdownTest {
    private val dispatcher = StandardTestDispatcher()
    private val scheduler = dispatcher.scheduler
    private var grace: Long? = null
    private val expired = ArrayList<Pair<Long, Long>>() // (virtual time, grace)
    private val countdown =
        IdleCountdown(CoroutineScope(SupervisorJob() + dispatcher), { grace }) { expired += scheduler.currentTime to it }

    private fun event(newGrace: Long?) {
        grace = newGrace
        countdown.update(newGrace)
        scheduler.runCurrent()
    }

    @Test fun `a playlist event with the same grace keeps counting from the pause`() {
        // Paused with the UI gone; 30 s later an online cover or an identification replaces a queued item (metadata
        // only: a timeline change with the same idleness). The card must still go 60 s after the pause, not 90 s.
        event(ServiceLifecycle.PAUSED_GRACE_MS)
        scheduler.advanceTimeBy(30_000)
        event(ServiceLifecycle.PAUSED_GRACE_MS)
        scheduler.advanceTimeBy(45_000)
        assertEquals(listOf(60_000L to ServiceLifecycle.PAUSED_GRACE_MS), expired)
    }

    @Test fun `a change in the kind of idleness restarts with the new grace`() {
        event(ServiceLifecycle.PAUSED_GRACE_MS)
        scheduler.advanceTimeBy(30_000)
        event(ServiceLifecycle.STOPPED_GRACE_MS) // Stop while paused
        scheduler.advanceTimeBy(10_000)
        assertEquals(listOf(33_000L to ServiceLifecycle.STOPPED_GRACE_MS), expired)
    }

    @Test fun `engaged or UI back cancels, and the next idleness starts a fresh countdown`() {
        event(ServiceLifecycle.PAUSED_GRACE_MS)
        scheduler.advanceTimeBy(50_000)
        event(null) // Play
        scheduler.advanceTimeBy(20_000)
        assertEquals(emptyList<Pair<Long, Long>>(), expired)
        event(ServiceLifecycle.PAUSED_GRACE_MS) // paused again at 70 s
        scheduler.advanceTimeBy(61_000)
        assertEquals(listOf(130_000L to ServiceLifecycle.PAUSED_GRACE_MS), expired)
    }

    @Test fun `no expiry when the fire-time check finds Seion engaged`() {
        event(ServiceLifecycle.PAUSED_GRACE_MS)
        grace = null // state changed without an event reaching update() yet
        scheduler.advanceTimeBy(61_000)
        assertEquals(emptyList<Pair<Long, Long>>(), expired)
    }

    private fun seek() {
        countdown.restartOnUserSeek(grace)
        scheduler.runCurrent()
    }

    @Test fun `a seek while paused restarts the paused countdown`() {
        // Paused with the UI gone; at 58 s the user seeks from the lock-screen card: still there, so a full 60 s again.
        event(ServiceLifecycle.PAUSED_GRACE_MS)
        scheduler.advanceTimeBy(58_000)
        seek()
        scheduler.advanceTimeBy(42_000) // 100 s: well past a 60 s countdown
        assertEquals(emptyList<Pair<Long, Long>>(), expired)
        scheduler.advanceTimeBy(18_001)
        assertEquals(listOf(118_000L to ServiceLifecycle.PAUSED_GRACE_MS), expired)
    }

    @Test fun `the grace stays 60 s after a seek`() {
        event(ServiceLifecycle.PAUSED_GRACE_MS)
        scheduler.advanceTimeBy(10_000)
        seek()
        scheduler.advanceTimeBy(59_999)
        assertEquals(emptyList<Pair<Long, Long>>(), expired)
        scheduler.advanceTimeBy(2)
        assertEquals(listOf(70_000L to ServiceLifecycle.PAUSED_GRACE_MS), expired)
    }

    @Test fun `a seek after Stop does not extend the stopped grace`() {
        event(ServiceLifecycle.STOPPED_GRACE_MS)
        scheduler.advanceTimeBy(2_000)
        seek()
        scheduler.advanceTimeBy(1_500)
        assertEquals(listOf(3_000L to ServiceLifecycle.STOPPED_GRACE_MS), expired)
    }

    @Test fun `a seek while engaged or with the UI connected starts no countdown`() {
        event(null)
        seek()
        scheduler.advanceTimeBy(200_000)
        assertEquals(emptyList<Pair<Long, Long>>(), expired)
    }

    // --- The frozen-process stop: a stop that works while Seion is frozen ---

    private val alarmLog = ArrayList<String>()
    private val fakeAlarm =
        object : IdleAlarm {
            override fun arm(delayMs: Long) {
                alarmLog += "arm $delayMs"
            }

            override fun cancel() {
                alarmLog += "cancel"
            }
        }

    /** How far the elapsed clock runs ahead of the countdown's own timer: the time the process spent frozen. */
    private var frozenMs = 0L
    private val frozenAware =
        IdleCountdown(CoroutineScope(SupervisorJob() + dispatcher), { grace }, fakeAlarm, { scheduler.currentTime + frozenMs }) {
            expired += scheduler.currentTime to it
        }

    private fun frozenEvent(newGrace: Long?) {
        grace = newGrace
        frozenAware.update(newGrace)
        scheduler.runCurrent()
    }

    @Test fun `the paused countdown arms the alarm, a Play or the end cancels it, the 3 s grace never arms it`() {
        frozenEvent(ServiceLifecycle.PAUSED_GRACE_MS)
        assertEquals(listOf("arm 60000"), alarmLog)
        frozenEvent(ServiceLifecycle.PAUSED_GRACE_MS) // same idleness: nothing new
        assertEquals(listOf("arm 60000"), alarmLog)
        frozenEvent(null) // Play
        assertEquals(listOf("arm 60000", "cancel"), alarmLog)
        frozenEvent(ServiceLifecycle.STOPPED_GRACE_MS)
        scheduler.advanceTimeBy(4_000)
        assertEquals(listOf("arm 60000", "cancel"), alarmLog)
        assertEquals(listOf(3_000L to ServiceLifecycle.STOPPED_GRACE_MS), expired)
        frozenEvent(ServiceLifecycle.PAUSED_GRACE_MS)
        scheduler.advanceTimeBy(61_000) // ends on time: the alarm is cancelled with it
        assertEquals(listOf("arm 60000", "cancel", "arm 60000", "cancel"), alarmLog)
    }

    @Test fun `a Play that thaws a frozen countdown is not lost to the overdue stop`() {
        // SE2: paused, frozen ~16 s later; at +90 s a Play on the stale card thaws the process. The overdue countdown
        // runs first (its message is due), but waits so the queued Play is handled before it.
        frozenEvent(ServiceLifecycle.PAUSED_GRACE_MS)
        frozenMs = 30_000 // the timer fires at its 60 s, but the clock says 90 s
        scheduler.advanceTimeBy(60_001)
        assertEquals(emptyList<Pair<Long, Long>>(), expired)
        scheduler.advanceTimeBy(100)
        frozenEvent(null) // the Play arrives behind the timer
        scheduler.advanceTimeBy(5_000)
        assertEquals(emptyList<Pair<Long, Long>>(), expired)
    }

    @Test fun `without a Play the thawed countdown ends after the settle time`() {
        frozenEvent(ServiceLifecycle.PAUSED_GRACE_MS)
        frozenMs = 30_000
        scheduler.advanceTimeBy(60_000 + IdleCountdown.SETTLE_MS + 1)
        assertEquals(listOf(60_000L + IdleCountdown.SETTLE_MS to ServiceLifecycle.PAUSED_GRACE_MS), expired)
    }

    @Test fun `the alarm ends a due countdown only while Seion is still idle`() {
        frozenEvent(ServiceLifecycle.PAUSED_GRACE_MS)
        scheduler.advanceTimeBy(30_000)
        assertEquals("not due yet", false, frozenAware.onAlarm())
        assertEquals("armed again for the rest", listOf("arm 60000", "arm 30000"), alarmLog)
        assertEquals(34_285L, AlarmIdleAlarm.triggerFor(60_000))
        // Frozen: the countdown's own timer has not run, but the elapsed clock is past the grace when the alarm thaws it.
        frozenMs = 35_000
        assertEquals(true, frozenAware.onAlarm())
        scheduler.advanceTimeBy(IdleCountdown.SETTLE_MS + 1)
        assertEquals(listOf(30_000L + IdleCountdown.SETTLE_MS to ServiceLifecycle.PAUSED_GRACE_MS), expired)
        // Played before the alarm: nothing to do.
        frozenMs = 0
        frozenEvent(ServiceLifecycle.PAUSED_GRACE_MS)
        frozenEvent(null)
        assertEquals(false, frozenAware.onAlarm())
    }

    @Test fun `a Play at 59 s cancels the countdown and the alarm finds nothing to do`() {
        frozenEvent(ServiceLifecycle.PAUSED_GRACE_MS)
        scheduler.advanceTimeBy(59_000)
        frozenEvent(null)
        scheduler.advanceTimeBy(2_000)
        assertEquals(false, frozenAware.onAlarm())
        assertEquals(emptyList<Pair<Long, Long>>(), expired)
        assertEquals(listOf("arm 60000", "cancel"), alarmLog)
    }
}
