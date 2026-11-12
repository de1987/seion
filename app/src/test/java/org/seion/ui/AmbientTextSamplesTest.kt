package org.seion.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.ui.ambient.AmbientContrast
import org.seion.ui.ambient.ArtStats
import org.seion.ui.ambient.Rgb
import org.seion.ui.ambient.TextSample

/**
 * The scrim is solved against what is drawn behind the secondary text, not a model of it. The samples below are
 * composites measured on screenshots (regions at the artist and album rows: median and lightest 5 %), undone from
 * the scrim and far brightness they were drawn with (far layer at unit brightness; the near layer has no coverage
 * there).
 */
class AmbientTextSamplesTest {
    private fun row(p: Double, vararg far: Rgb) = far.map { TextSample(far = it, scrimProfile = p) }

    private val artist = 0.4560 // scrim profile at the artist row (411 dp of 640)
    private val album = 0.4894 // album row (442 dp)

    private val cases =
        mapOf(
            "gjpqy" to
                Pair(
                    ArtStats(0.8516, Rgb(236.5, 237.5, 230.6), Rgb(239.0, 240.0, 233.0), Rgb(239.0, 240.0, 233.0)),
                    row(artist, Rgb(237.3, 240.7, 230.6), Rgb(244.0, 247.3, 237.3)) +
                        row(album, Rgb(238.6, 238.6, 231.4), Rgb(242.1, 245.7, 235.0))
                ),
            "pastel sorbet" to
                Pair(
                    ArtStats(0.8806, Rgb(244.5, 231.7, 238.5), Rgb(253.0, 248.0, 238.0), Rgb(248.0, 220.0, 230.0)),
                    row(artist, Rgb(259.9, 228.2, 234.5), Rgb(256.7, 237.7, 247.2)) +
                        row(album, Rgb(226.2, 239.7, 253.2), Rgb(236.4, 246.5, 260.0))
                ),
            "pure white" to
                Pair(
                    ArtStats(1.0, Rgb.WHITE, Rgb.WHITE, Rgb.WHITE),
                    row(artist, Rgb(254.5, 254.5, 254.5), Rgb(261.6, 261.6, 261.6)) +
                        row(album, Rgb(256.1, 256.1, 256.1), Rgb(259.9, 259.9, 259.9))
                )
        )

    @Test fun `secondary text reaches 4_5 to 1 on the composites measured from screenshots`() {
        for ((name, case) in cases) {
            val (stats, samples) = case
            val p = AmbientContrast.compute(stats, samples)
            val grey = AmbientContrast.greyContrast(samples, p.farBrightness.toDouble(), p.scrim.toDouble())
            assertTrue("$name grey $grey", grey >= 4.5)
            assertTrue("$name reported ${p.greyContrast} vs drawn $grey", p.greyContrast <= grey + 1e-6)
        }
    }
}
