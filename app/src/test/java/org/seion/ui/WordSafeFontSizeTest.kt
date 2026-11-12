package org.seion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** At 2.0× font scale "Recently added" must not break as "Recentl / y add…" inside its home tile. */
class WordSafeFontSizeTest {
    // A tile label's content width on the 360 dp reference (107 dp tile − 18 dp padding) at 2 px/dp.
    private val tilePx = 89f * 2

    /** Width = chars × 0.55 em × size × font scale × density. */
    private fun measurer(fontScale: Float) = TextWidth { text, sp -> text.length * 0.55f * sp * fontScale * 2f }

    private fun wordsFit(label: String, sp: Float, w: TextWidth) = label.split(' ').all { w.widthPx(it, sp) <= tilePx }

    @Test fun `Recently fits on one line of the tile at 2_0x`() {
        val w = measurer(2f)
        val sp = WordSafe.fontSize("Recently added", tilePx, 2, w)
        assertTrue("size $sp", wordsFit("Recently added", sp, w))
        assertTrue(sp >= WordSafe.FLOOR_SP)
    }

    @Test fun `pt-BR Recentes and Musicas fit at 2_0x`() {
        val w = measurer(2f)
        for (label in listOf("Recentes", "Todas as músicas", "Gêneros")) {
            val sp = WordSafe.fontSize(label, tilePx, 2, w)
            assertTrue("$label size $sp", wordsFit(label, sp, w))
        }
    }

    @Test fun `at normal scale the label keeps its design size`() {
        assertEquals(WordSafe.START_SP, WordSafe.fontSize("Recently added", tilePx, 2, measurer(1f)), 0f)
    }

    @Test fun `the text also fits in max lines and the size never goes below the floor`() {
        val w = measurer(2f)
        val sp = WordSafe.fontSize("Very long words everywhere", tilePx, 2, w)
        assertTrue(sp >= WordSafe.FLOOR_SP)
        assertEquals(WordSafe.FLOOR_SP, WordSafe.fontSize("Supercalifragilistic", 40f, 2, w), 0f)
    }
}
