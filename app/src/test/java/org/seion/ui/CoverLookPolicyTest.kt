package org.seion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.seion.ui.ambient.AmbientParams
import org.seion.ui.ambient.CoverLook
import org.seion.ui.ambient.CoverLookPolicy

/** Each cover frame keeps its own dimming, even after the ambient switched to the next track. */
class CoverLookPolicyTest {
    private val lookA = CoverLook(0.6f, feather = true, featherWidth = 0.16f)
    private val ambientB = AmbientParams.DEFAULT.copy(coverBrightness = 0.9f, feather = 0.09f)

    @Test fun `the outgoing frame keeps its own dimming when the ambient belongs to the next track`() {
        val look = CoverLookPolicy.lookFor("/m/a.flac", lookA, "/m/b.flac#0", ambientB, ambientStyle = true)
        assertEquals(0.6f, look.brightness, 1e-6f)
        assertEquals(0.16f, look.featherWidth, 1e-6f)
    }

    @Test fun `the frame's look wins even while the ambient still belongs to the frame`() {
        val look = CoverLookPolicy.lookFor("/m/a.flac", lookA, "/m/a.flac#0", ambientB, ambientStyle = true)
        assertEquals(0.6f, look.brightness, 1e-6f)
    }

    @Test fun `classic style draws every cover plain`() {
        val look = CoverLookPolicy.lookFor("/m/a.flac", lookA, "/m/a.flac#0", ambientB, ambientStyle = false)
        assertEquals(1f, look.brightness, 1e-6f)
        assertFalse(look.feather)
    }
}
