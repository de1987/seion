package org.seion.playback.viz

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumModelTest {
    @Test fun bandEdges() {
        assertEquals(14, SpectrumModel.BANDS)
        val m = SpectrumModel(44_100)
        assertEquals(SpectrumModel.BANDS + 1, m.edges.size)
        // The full range, 40 Hz to 16 kHz, redistributed over the 14 bands...
        assertEquals(40.0, m.edges.first(), 1e-9)
        assertEquals(16_000.0, m.edges.last(), 1e-6)
        // ...with the same log spacing: every band spans the same ratio, 400^(1/14) ≈ 1.534 (0.61 octave).
        val ratio = Math.pow(16_000.0 / 40.0, 1.0 / SpectrumModel.BANDS)
        for (b in 0 until SpectrumModel.BANDS) assertEquals(ratio, m.edges[b + 1] / m.edges[b], 1e-9)
        for (b in 0 until SpectrumModel.BANDS) assertTrue("band $b has a bin", m.lastBin[b] >= m.firstBin[b])
        assertEquals(2048, m.fftSize)
        assertEquals(4096, SpectrumModel(96_000).fftSize)
    }

    private fun sine(m: SpectrumModel, hz: Double, amp: Double) =
        FloatArray(m.fftSize) { (amp * sin(2 * PI * hz * it / m.sampleRate)).toFloat() }

    @Test fun sineLandsInItsBand() {
        val m = SpectrumModel(44_100)
        m.analyze(sine(m, 1000.0, 0.5)) // −6 dBFS
        // 1 kHz is in bar 7 of 14 (800–1227 Hz), the bar right of the centre line.
        assertEquals(800.0, m.edges[7], 1e-6)
        assertTrue(m.edges[8] > 1000.0)
        val max = m.target.indices.maxBy { m.target[it] }
        assertEquals(7, max)
        assertEquals((-6.0 + 60) / 60, m.target[7].toDouble(), 0.05)
        // The neighbours read clearly lower (the sine doesn't smear over the wider bands).
        assertTrue("left ${m.target[6]}", m.target[6] < m.target[7] - 0.1f)
        assertTrue("right ${m.target[8]}", m.target[8] < m.target[7] - 0.1f)
    }

    @Test fun `bass and treble keep their bars at the edges`() {
        val m = SpectrumModel(44_100)
        m.analyze(sine(m, 45.0, 0.5))
        assertEquals(0, m.target.indices.maxBy { m.target[it] })
        m.analyze(sine(m, 14_000.0, 0.5))
        assertEquals(SpectrumModel.BANDS - 1, m.target.indices.maxBy { m.target[it] })
    }

    @Test fun ballistics() {
        val m = SpectrumModel(48_000)
        m.target.fill(1f)
        repeat(15) { m.advance(0.001f) }
        assertTrue("attack: ${m.levels[0]}", m.levels[0] >= 0.63f)
        repeat(1000) { m.advance(0.001f) }
        m.silence()
        repeat(180) { m.advance(0.001f) }
        assertEquals(0.368, m.levels[0].toDouble(), 0.02)
    }

    @Test fun peakHoldAndFall() {
        val m = SpectrumModel(48_000)
        val frame = 1f / 60
        m.target.fill(1f)
        repeat(120) { m.advance(frame) }
        assertEquals(1.0, m.caps[0].toDouble(), 0.01)
        // Drop the bar at once: the cap holds for 500 ms...
        m.levels.fill(0f)
        m.silence()
        var t = 0f
        while (t < 0.5f - frame) {
            m.advance(frame)
            t += frame
            assertTrue("held at $t s", m.caps[0] >= 0.99f)
        }
        // ...then falls to the floor within 0.7 s ± one frame.
        var fall = 0f
        while (m.caps[0] > 0f && fall < 2f) {
            m.advance(frame)
            fall += frame
        }
        assertEquals(0.7, fall.toDouble(), frame + 0.05)
    }

    @Test fun `silence for 1_2 s lets the band close`() {
        val m = SpectrumModel(48_000)
        m.target.fill(1f)
        repeat(60) { m.advance(1f / 60) }
        m.silence()
        var t = 0f
        while (!m.fellQuiet && t < 10f) {
            m.advance(1f / 60)
            t += 1f / 60
        }
        assertTrue("closed after $t s", m.fellQuiet && t < 4f)
    }

    // The bars must not jump from nothing to full height in one frame when the band opens.
    @Test fun `bars grow in over 300 ms when the signal first arrives`() {
        val m = SpectrumModel(48_000)
        val frame = 1f / 30
        m.target.fill(1f)
        m.advance(frame)
        assertTrue("first frame: ${m.shown[0]}", m.shown[0] <= 0.2f)
        repeat(4) { m.advance(frame) }
        assertTrue("mid-way: ${m.shown[0]}", m.shown[0] in 0.3f..0.8f)
        repeat(6) { m.advance(frame) }
        assertEquals(1.0, m.shown[0].toDouble(), 0.02)
        // The raw ballistics are unchanged (attack 15 ms).
        assertTrue(m.levels[0] > 0.99f)
    }

    @Test fun `after a reset the bars grow in again`() {
        val m = SpectrumModel(48_000)
        m.target.fill(1f)
        repeat(30) { m.advance(1f / 30) }
        m.reset()
        m.target.fill(1f)
        m.advance(1f / 30)
        assertTrue(m.shown[0] <= 0.2f)
    }

    @Test fun `the reveal waits while it is held (the band still opening)`() {
        val m = SpectrumModel(48_000)
        m.target.fill(1f)
        repeat(15) {
            m.holdReveal()
            m.advance(1f / 30)
        }
        assertEquals(0.0, m.shown[0].toDouble(), 1e-6)
        assertTrue(m.levels[0] > 0.99f)
        m.advance(1f / 30)
        assertTrue("grows from the release: ${m.shown[0]}", m.shown[0] in 0.01f..0.2f)
    }
}
