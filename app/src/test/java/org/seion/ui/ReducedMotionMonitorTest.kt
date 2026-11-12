package org.seion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Remove animations" switched while Seion runs must take effect at once, not after a restart. */
class ReducedMotionMonitorTest {
    private var animationsOff = false
    private var listener: (() -> Unit)? = null

    private fun monitor() = ReducedMotionMonitor({ animationsOff }) { cb ->
        listener = cb
        ({ listener = null })
    }

    @Test fun `a change while running is picked up`() {
        val m = monitor()
        m.start()
        assertFalse(m.reduced.value)
        animationsOff = true
        listener!!.invoke()
        assertTrue(m.reduced.value)
        animationsOff = false
        listener!!.invoke()
        assertFalse(m.reduced.value)
    }

    @Test fun `stop unsubscribes and start re-reads`() {
        val m = monitor()
        m.start()
        m.stop()
        assertNull(listener)
        animationsOff = true
        m.start()
        assertTrue(m.reduced.value)
        assertEquals(true, listener != null)
    }
}
