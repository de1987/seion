package org.seion.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.ui.theme.CoverMotion
import org.seion.ui.theme.SeionMotion

/** The Next/Previous cover swap must read as a slide, not a cross-fade. */
class CoverMotionTest {
    @Test fun `the cover spec moves at least 0_15 of the width while visible`() {
        val visible = CoverMotion.visibleMotion()
        assertTrue("visible motion $visible of 0.25", visible >= 0.15)
    }

    @Test fun `a decelerating slide with a short fade moves too little of the width while visible`() {
        // EmphasizedDecelerate slide, 200 ms FastOutSlowIn fade: only 0.065 of the width moves while visible.
        val slow = CoverMotion.visibleMotion(SeionMotion.EmphasizedDecelerate, 400, FastOutSlowInEasing, 200)
        assertEquals(0.065, slow, 0.005)
    }
}
