package org.seion.playlists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M3U/M3U8 import and export rules (pure). */
class M3uTest {
    @Test fun `extended M3U with titles, comments, blank lines and CRLF`() {
        val text = "#EXTM3U\r\n\r\n#EXTINF:215,Aurora Vale - Undertow\r\nAurora Vale/Glass Harbour/01 Undertow.flac\r\n" +
            "# a comment\r\n/storage/emulated/0/Music/x.mp3\r\n#EXTINF:-1,\r\nhttp://radio.example/stream\r\n"
        val e = M3u.parse(text)
        assertEquals(3, e.size)
        assertEquals(M3u.Entry("Aurora Vale/Glass Harbour/01 Undertow.flac", "Aurora Vale - Undertow", 215), e[0])
        assertEquals(M3u.Entry("/storage/emulated/0/Music/x.mp3", null, null), e[1])
        assertEquals(M3u.Entry("http://radio.example/stream", null, null), e[2])
    }

    @Test fun `EXTINF with attributes keeps the length and the title after the comma`() {
        val e = M3u.parse("#EXTINF:183 tvg-id=\"x\",Night Ferry - Deck Lights\nDeck Lights.opus")
        assertEquals(M3u.Entry("Deck Lights.opus", "Night Ferry - Deck Lights", 183), e.single())
    }

    @Test fun `UTF-8 with a BOM, and Windows-1252 when the bytes aren't UTF-8`() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "/m/ação.flac".toByteArray(Charsets.UTF_8)
        assertEquals("/m/ação.flac", M3u.decode(utf8))
        val cp1252 = "/m/ação.flac".toByteArray(charset("windows-1252"))
        assertEquals("/m/ação.flac", M3u.decode(cp1252))
    }

    @Test fun `locations resolve against the file's folder, file URLs decode, foreign paths don't resolve`() {
        val base = "/storage/emulated/0/Music/Playlists"
        assertEquals("/storage/emulated/0/Music/A/01.flac", M3u.resolve("../A/01.flac", base))
        assertEquals("/storage/emulated/0/Music/A/01.flac", M3u.resolve("..\\A\\01.flac", base))
        assertEquals("/storage/emulated/0/Music/Playlists/x.mp3", M3u.resolve("./x.mp3", base))
        assertEquals("/sdcard/Música/a b.mp3", M3u.resolve("file:///sdcard/M%C3%BAsica/a%20b.mp3", base))
        assertEquals("/m/a.mp3", M3u.resolve("/m//./a.mp3", null))
        assertNull(M3u.resolve("C:\\Users\\me\\Music\\a.mp3", base))
        assertNull(M3u.resolve("http://radio.example/stream", base))
        assertNull(M3u.resolve("A/01.flac", null))
        assertNull(M3u.resolve("../../../../../x.mp3", "/a"))
    }

    @Test fun `an unresolved location matches the library by file name and as many folders as agree`() {
        val index =
            M3u.indexByFileName(
                listOf(
                    "/storage/emulated/0/Music/Aurora Vale/Glass Harbour/01 Undertow.flac",
                    "/storage/emulated/0/Music/Other/Glass Harbour/01 Undertow.flac",
                    "/storage/0000-0000/Music/Kelp Choir/Low Light/01 Fog Signal.ogg"
                )
            )
        assertEquals(
            "/storage/emulated/0/Music/Aurora Vale/Glass Harbour/01 Undertow.flac",
            M3u.matchBySuffix("C:\\Users\\me\\Music\\aurora vale\\Glass Harbour\\01 Undertow.flac", index)
        )
        assertEquals("/storage/0000-0000/Music/Kelp Choir/Low Light/01 Fog Signal.ogg", M3u.matchBySuffix("..\\01 FOG SIGNAL.ogg", index))
        // Two files agree equally far: ambiguous, nothing is guessed.
        assertNull(M3u.matchBySuffix("Glass Harbour/01 Undertow.flac", index))
        assertNull(M3u.matchBySuffix("nothing.mp3", index))
    }

    @Test fun `export writes EXTINF and paths relative to the playlist's folder on the same volume`() {
        val volumeOf = { p: String -> listOf("/storage/emulated/0", "/storage/0000-0000").firstOrNull { p.startsWith("$it/") } }
        val text =
            M3u.write(
                listOf(
                    M3u.Item("/storage/emulated/0/Music/A/01 One.flac", "One", "Ana Lima", 214_600),
                    M3u.Item("/storage/0000-0000/Music/B/02 Two.mp3", "Two", null, 0)
                ),
                "/storage/emulated/0/Music/Playlists",
                volumeOf
            )
        assertEquals(
            "#EXTM3U\n#EXTINF:215,Ana Lima - One\n../A/01 One.flac\n#EXTINF:-1,Two\n/storage/0000-0000/Music/B/02 Two.mp3\n",
            text
        )
        // Unknown destination folder: absolute paths.
        val abs = M3u.write(listOf(M3u.Item("/storage/emulated/0/Music/A/01 One.flac", "One", null, 1000)), null, volumeOf)
        assertEquals("#EXTM3U\n#EXTINF:1,One\n/storage/emulated/0/Music/A/01 One.flac\n", abs)
    }

    @Test fun `what export writes, import reads back to the same files`() {
        val base = "/storage/emulated/0/Music/Playlists"
        val paths = listOf("/storage/emulated/0/Music/A/01 ação.flac", "/storage/emulated/0/Music/Playlists/x.mp3")
        val text = M3u.write(paths.map { M3u.Item(it, "t", "a", 1000) }, base) { "/storage/emulated/0" }
        val back = M3u.parse(M3u.decode(text.toByteArray(Charsets.UTF_8))).map { M3u.resolve(it.location, base) }
        assertEquals(paths, back)
    }
}
