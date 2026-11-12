package org.seion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.ui.ambient.AmbientContrast
import org.seion.ui.ambient.ArtStats
import org.seion.ui.ambient.ClassicGradient
import org.seion.ui.ambient.Rgb
import org.seion.ui.ambient.Wcag

class AmbientContrastTest {
    private fun solid(argb: Int, n: Int = 16) = ArtStats.of(IntArray(n * n) { argb }, n, n)

    private val covers =
        mapOf(
            "black" to solid(0xFF000000.toInt()),
            "white" to solid(0xFFFFFFFF.toInt()),
            "pastel" to solid(0xFFEEE4DC.toInt()),
            "yellow" to solid(0xFFF0D020.toInt()),
            "ember" to solid(0xFF3A0E04.toInt()),
            "cyan" to solid(0xFF22BCBC.toInt())
        )

    @Test fun `grey secondary text always reaches 4_5 to 1 and white title more`() {
        for ((name, s) in covers) {
            val p = AmbientContrast.compute(s)
            assertTrue("$name grey ${p.greyContrast}", p.greyContrast >= 4.5)
            assertTrue("$name title ${p.titleContrast}", p.titleContrast >= p.greyContrast)
        }
    }

    @Test fun `dark covers keep the lightest scrim and full cover brightness`() {
        val p = AmbientContrast.compute(covers.getValue("ember"))
        assertEquals(0.55f, p.scrim, 0.001f)
        assertEquals(1f, p.coverBrightness, 0.001f)
        assertEquals(0.88f, p.farBrightness, 0.02f)
    }

    @Test fun `bright covers dim the cover and its halo, never below 0_55`() {
        val p = AmbientContrast.compute(covers.getValue("white"))
        assertTrue(p.coverBrightness < 1f)
        assertTrue(p.coverBrightness >= 0.55f)
        assertTrue(p.farBrightness <= 0.58f + 1e-6f)
        assertTrue(p.scrim > 0.55f)
    }

    @Test fun `far layer brightness stays in range`() {
        for (s in covers.values) {
            val p = AmbientContrast.compute(s)
            assertTrue(p.farBrightness in 0.4f..0.88f)
            assertTrue(p.scrim in 0.55f..1f)
            assertTrue(p.statusScrim in 0f..0.8f)
        }
    }

    @Test fun `stats measure luminance, lower half and edge rows`() {
        val n = 4
        // Top half white, bottom half black.
        val px = IntArray(n * n) { i -> if (i / n < n / 2) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val s = ArtStats.of(px, n, n)
        assertEquals(0.5, s.meanLuminance, 1e-9)
        assertEquals(0.0, s.lowerHalf.r, 1e-9)
        assertEquals(255.0, s.topRow.g, 1e-9)
        assertEquals(0.0, s.bottomRow.b, 1e-9)
    }

    @Test fun `wcag contrast reference values`() {
        assertEquals(21.0, Wcag.contrast(Rgb.WHITE, Rgb.BLACK), 0.01)
        assertEquals(1.0, Wcag.contrast(Rgb.GREY, Rgb.GREY), 0.001)
        assertEquals(9.57, Wcag.contrast(Rgb.GREY, Rgb.BLACK), 0.01) // #AFAFAF on black
    }

    @Test fun `classic base colour keeps hue and clamps lightness to 0_22 to 0_38`() {
        for (c in listOf(Rgb(250.0, 240.0, 120.0), Rgb(10.0, 20.0, 60.0), Rgb(40.0, 62.0, 134.0))) {
            val (h0, _, _) = ClassicGradient.toHsl(c)
            val (h1, _, l1) = ClassicGradient.toHsl(ClassicGradient.baseColour(c))
            assertTrue("l=$l1", l1 in 0.2199..0.3801)
            assertEquals(h0, h1, 0.01)
        }
        assertEquals(1.0, ClassicGradient.STOPS.first().second, 0.0)
        assertEquals(0.0, ClassicGradient.STOPS.last().second, 0.0)
    }
}
