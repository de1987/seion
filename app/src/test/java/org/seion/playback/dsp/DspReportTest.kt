package org.seion.playback.dsp

import org.junit.Assert.assertTrue
import org.junit.Test

/** The dumpsys measurement report: the engine lines in order, and RenovaPure with its state, cutoff and codec. */
class DspReportTest {
    @Test fun `the report names Seion RenovaPure, with its state, cutoff and codec`() {
        val status = DspStatus().apply {
            sampleRate = 44_100
            channels = 2
            renovaPureAmount = 0.5f
            renovaPureCutoff = 16_012.0
        }
        val lines = DspReport.lines(DspStats({ 0L }), status, DspParams(crystalX = false, renovaPure = true))
        val text = lines.joinToString("\n")
        assertTrue(text, lines.first() == "Seion DSP")
        assertTrue(text, lines.any { it.startsWith("engine load: ") })
        assertTrue(text, lines.any { it.startsWith("Seion RenovaPure: active, cutoff 16012 Hz, codec ") })
        assertTrue(text, lines.any { it.startsWith("switches: CrystalX+ off, Seion RenovaPure on; voicing ") })
    }

    @Test fun `an idle RenovaPure reads inactive`() {
        val lines = DspReport.lines(DspStats({ 0L }), DspStatus())
        assertTrue(lines.joinToString("\n"), lines.any { it.startsWith("Seion RenovaPure: inactive, cutoff 0 Hz") })
    }
}
