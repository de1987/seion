package org.seion.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.seion.art.CoverMatch.Script

/** Title and artist normalisation for cover matching, against fixed reference vectors. */
class CoverMatchTest {
    private fun eq(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-9)

    @Test fun `album similarity vectors`() {
        eq(1.0, CoverMatch.albumSim("ＴＨＥ ＢＯＯＫ", "THE BOOK"))
        eq(1.0, CoverMatch.albumSim("VARIETY", "VARIETY (30th Anniversary Edition)"))
        eq(1.0, CoverMatch.albumSim("Teatro d'ira: Vol. I", "Teatro d'ira - Vol. I"))
        eq(1.0, CoverMatch.albumSim("Crêuza de mä", "Creuza de ma"))
        eq(1.0, CoverMatch.albumSim("THE ALBUM - 1st FULL ALBUM", "THE ALBUM"))
        assertTrue(CoverMatch.albumSim("Get Up", "NewJeans 2nd EP 'Get Up'") < 0.85)
    }

    @Test fun `artist similarity vectors`() {
        eq(1.0, CoverMatch.artistSim("Kendrick Lamar feat. Rihanna", "Kendrick Lamar"))
        assertTrue(CoverMatch.artistSim("Elis Regina & Tom Jobim", "Elis Regina") >= 0.80)
        assertTrue(CoverMatch.artistSim("Jorge & Mateus", "Jorge Ben") < 0.80)
    }

    @Test fun `acceptance vectors`() {
        assertTrue(CoverMatch.accepts("방탄소년단", "MAP OF THE SOUL : 7", "BTS", "MAP OF THE SOUL : 7"))
        assertFalse(CoverMatch.accepts("坂本冬美", "夜桜お七", "歌っちゃ王", "夜桜お七(ガイド無しカラオケ)[原曲歌手:坂本冬美]"))
        assertTrue(CoverMatch.accepts("Indochine", "3", "Indochine", "3 (Remastered)"))
        assertFalse(CoverMatch.accepts("X", "3", "椎名林檎", "3"))
        assertFalse(CoverMatch.accepts("山下達郎", "FOR YOU", "Selena Gomez", "For You"))
        assertFalse(CoverMatch.accepts("宇多田ヒカル", "First Love", "ICCHAN", "First Love (カバー)"))
        assertTrue(CoverMatch.accepts("Fleetwood Mac", "Rumours (Remastered)", "Fleetwood Mac", "Rumours"))
        assertFalse(CoverMatch.accepts("Fleetwood Mac", "Rumours", "Fleetwood Mac", "Rumours (Karaoke Version)"))
        assertTrue(CoverMatch.accepts("Karaoke Kings", "Karaoke Hits", "Karaoke Kings", "Karaoke Hits"))
    }

    @Test fun `kana and han are one script group`() {
        assertFalse(CoverMatch.crossScript("坂本冬美", "歌っちゃ王"))
        assertTrue(CoverMatch.crossScript("方탄", "BTS"))
        assertFalse(CoverMatch.crossScript("123", "BTS"))
    }

    @Test fun `script detection`() {
        assertEquals(Script.KANA, CoverMatch.scriptOf("竹内まりや"))
        assertEquals(Script.HANGUL, CoverMatch.scriptOf("아이유"))
        assertEquals(Script.HAN, CoverMatch.scriptOf("椎名林檎"))
        assertEquals(Script.LATIN, CoverMatch.scriptOf("Anri"))
    }

    @Test fun `base title, base artist and parts`() {
        assertEquals("Rumours", CoverMatch.baseTitle("Rumours (Remastered)"))
        assertEquals("OK Computer", CoverMatch.baseTitle("OK Computer [Deluxe Edition]"))
        assertEquals("Transa", CoverMatch.baseTitle("Transa (Remasterizado)"))
        assertEquals("Acústico", CoverMatch.baseTitle("Acústico - Ao Vivo"))
        assertEquals("(Untitled)", CoverMatch.baseTitle("(Untitled)"))
        assertEquals("Kendrick Lamar", CoverMatch.baseArtist("Kendrick Lamar feat. Rihanna"))
        assertEquals(listOf("Elis Regina", "Tom Jobim"), CoverMatch.artistParts("Elis Regina & Tom Jobim"))
        assertEquals(listOf("Jorge", "Mateus"), CoverMatch.artistParts("Jorge e Mateus"))
    }

    @Test fun `fold keeps kana dakuten and drops Latin accents`() {
        assertEquals("が", CoverMatch.fold("が"))
        assertEquals("creuza de ma", CoverMatch.fold("Crêuza de mä"))
        assertEquals("rock and roll", CoverMatch.fold("Rock & Roll"))
        assertEquals("oclock", CoverMatch.fold("O'Clock"))
        assertEquals("a b c", CoverMatch.fold("a・b · c"))
    }

    @Test fun `ratio matches difflib`() {
        // difflib.SequenceMatcher(None, "abcd", "bcde").ratio() == 0.75; ("kitten", "sitting") == 0.6153846...
        eq(0.75, CoverMatch.ratio("abcd", "bcde"))
        eq(8.0 / 13.0, CoverMatch.ratio("kitten", "sitting"))
    }

    @Test fun `ranking prefers the best score, then albums over singles, then source order`() {
        val cands = listOf("Rumours - Single", "Rumours", "Rumours (Live)")
        val ranked = CoverMatch.ranked("Fleetwood Mac", "Rumours", cands, { "Fleetwood Mac" }, { it })
        assertEquals("Rumours", ranked.first())
    }
}
