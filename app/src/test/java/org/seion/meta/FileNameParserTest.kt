package org.seion.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameParserTest {
    @Test fun `number, artist and title`() {
        val p = FileNameParser.parse("/storage/emulated/0/Music/03 - Fleetwood Mac - Dreams.flac")
        assertEquals("Fleetwood Mac", p.artist)
        assertEquals("Dreams", p.title)
        assertFalse(p.junk)
    }

    @Test fun `artist and album folders`() {
        val p = FileNameParser.parse("/storage/emulated/0/Music/Radiohead/OK Computer/07 Airbag.mp3")
        assertEquals("Radiohead", p.artist)
        assertEquals("OK Computer", p.album)
        assertEquals("Airbag", p.title)
    }

    @Test fun `title only gives no artist`() {
        val p = FileNameParser.parse("/storage/emulated/0/Music/Creep.mp3")
        assertNull(p.artist)
        assertEquals("Creep", p.title)
        assertNull(FileNameParser.parse("/sdcard/Download/Hallelujah.mp3").artist)
    }

    @Test fun `the artist tag wins over the folders`() {
        val p = FileNameParser.parse("/m/Some Folder/Other/05 Song.mp3", artistTag = "X")
        assertEquals("X", p.artist)
    }

    @Test fun `junk names`() {
        assertTrue(FileNameParser.parse("/m/WhatsApp Audio/AUD-20240101-WA0001.mp3").junk)
        assertTrue(FileNameParser.parse("/m/Download/Track 03.mp3").junk)
        assertTrue(FileNameParser.parse("/m/Download/faixa 1.mp3").junk)
        assertTrue(FileNameParser.parse("/m/Recordings/audio_2023-05-14_18-22-01.ogg").junk)
        assertTrue(FileNameParser.parse("/m/Music/Unknown Artist/Unknown Album/01 Track 1.mp3").junk)
        assertTrue(FileNameParser.parse("/m/Music/0042.mp3").junk)
    }

    @Test fun `generic folders give nothing`() {
        assertTrue(FileNameParser.isGenericFolder("CD1"))
        assertTrue(FileNameParser.isGenericFolder("Disc 1"))
        assertTrue(FileNameParser.isGenericFolder("2019"))
        assertTrue(FileNameParser.isGenericFolder("Various Artists"))
        assertTrue(FileNameParser.isGenericFolder("Downloads"))
        assertFalse(FileNameParser.isGenericFolder("Radiohead"))
        assertNull(FileNameParser.parse("/m/Music/CD1/02 Song Name.mp3").artist)
    }
}
