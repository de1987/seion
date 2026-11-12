package org.seion.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the playback service ends itself: after a swipe from Recents, and after it has been idle for a while.
 */
class ServiceLifecycleTest {
    private val pwr = true
    private val n = 3

    @Test fun `engaged is Media3's user-engaged rule`() {
        assertTrue(ServiceLifecycle.engaged(true, Player.STATE_READY))
        assertTrue(ServiceLifecycle.engaged(true, Player.STATE_BUFFERING))
        assertFalse(ServiceLifecycle.engaged(true, Player.STATE_IDLE))
        assertFalse(ServiceLifecycle.engaged(true, Player.STATE_ENDED))
        assertFalse(ServiceLifecycle.engaged(false, Player.STATE_READY))
        assertFalse(ServiceLifecycle.engaged(false, Player.STATE_BUFFERING))
    }

    @Test fun `never counts down while Seion's UI is connected`() {
        for (state in listOf(Player.STATE_IDLE, Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_ENDED)) {
            for (p in listOf(true, false)) {
                assertNull(ServiceLifecycle.idleGraceMs(p, state, n, uiConnected = true))
                assertNull(ServiceLifecycle.idleGraceMs(p, state, 0, uiConnected = true))
            }
        }
    }

    @Test fun `never counts down while playing, buffering to play, or in a call's transient focus loss`() {
        // A transient loss keeps playWhenReady = true in READY (ExoPlayer only suppresses playback).
        assertNull(ServiceLifecycle.idleGraceMs(pwr, Player.STATE_READY, n, uiConnected = false))
        assertNull(ServiceLifecycle.idleGraceMs(pwr, Player.STATE_BUFFERING, n, uiConnected = false))
    }

    @Test fun `Stop, the end of the queue and an empty player end it after the short grace`() {
        assertEquals(ServiceLifecycle.STOPPED_GRACE_MS, ServiceLifecycle.idleGraceMs(pwr, Player.STATE_IDLE, n, false))
        assertEquals(ServiceLifecycle.STOPPED_GRACE_MS, ServiceLifecycle.idleGraceMs(pwr, Player.STATE_ENDED, n, false))
        assertEquals(ServiceLifecycle.STOPPED_GRACE_MS, ServiceLifecycle.idleGraceMs(false, Player.STATE_IDLE, n, false))
        assertEquals(ServiceLifecycle.STOPPED_GRACE_MS, ServiceLifecycle.idleGraceMs(pwr, Player.STATE_READY, 0, false))
    }

    @Test fun `a pause ends it after the paused grace`() {
        assertEquals(ServiceLifecycle.PAUSED_GRACE_MS, ServiceLifecycle.idleGraceMs(false, Player.STATE_READY, n, false))
        assertEquals(ServiceLifecycle.PAUSED_GRACE_MS, ServiceLifecycle.idleGraceMs(false, Player.STATE_BUFFERING, n, false))
    }

    @Test fun `the graces stay within their limits`() {
        // Changing either is a deliberate product choice: this test has to change with it, on purpose.
        assertTrue(ServiceLifecycle.STOPPED_GRACE_MS in 1..10_000)
        assertTrue(ServiceLifecycle.PAUSED_GRACE_MS in 1..60_000)
    }

    @Test fun `a swipe after Stop ends the service although playWhenReady is still true`() {
        // player.stop() leaves playWhenReady = true and the state IDLE: reading that as "playing" would keep the service.
        assertTrue(ServiceLifecycle.stopOnTaskRemoved(playWhenReady = true, Player.STATE_IDLE, itemCount = 3))
    }

    @Test fun `a swipe after the queue ended ends the service`() {
        assertTrue(ServiceLifecycle.stopOnTaskRemoved(playWhenReady = true, Player.STATE_ENDED, itemCount = 3))
    }

    @Test fun `a swipe while playing or buffering to play keeps the service`() {
        assertFalse(ServiceLifecycle.stopOnTaskRemoved(playWhenReady = true, Player.STATE_READY, itemCount = 3))
        assertFalse(ServiceLifecycle.stopOnTaskRemoved(playWhenReady = true, Player.STATE_BUFFERING, itemCount = 3))
    }

    @Test fun `a swipe while paused or empty ends the service`() {
        assertTrue(ServiceLifecycle.stopOnTaskRemoved(playWhenReady = false, Player.STATE_READY, itemCount = 3))
        assertTrue(ServiceLifecycle.stopOnTaskRemoved(playWhenReady = true, Player.STATE_READY, itemCount = 0))
    }
}
