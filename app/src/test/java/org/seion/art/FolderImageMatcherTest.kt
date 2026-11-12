package org.seion.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderImageMatcherTest {
    @Test fun `cover wins over every other name, case-insensitively`() {
        val names = listOf("folder.jpg", "Front.png", "COVER.JPG", "album.webp", "AlbumArtLarge.jpg", "01 song.flac")
        assertEquals("COVER.JPG", FolderImageMatcher.pick(names))
    }

    @Test fun `order is cover, folder, front, album, albumart`() {
        assertEquals("Folder.jpeg", FolderImageMatcher.pick(listOf("front.jpg", "Folder.jpeg", "album.png")))
        assertEquals("front.webp", FolderImageMatcher.pick(listOf("album.png", "front.webp")))
        assertEquals("album.png", FolderImageMatcher.pick(listOf("albumart.jpg", "album.png")))
        assertEquals("AlbumArtSmall.jpg", FolderImageMatcher.pick(listOf("AlbumArtSmall.jpg", "booklet.jpg")))
    }

    @Test fun `albumart prefers the large variant`() {
        assertEquals(
            "AlbumArt_{X}_Large.jpg",
            FolderImageMatcher.pick(listOf("AlbumArt_{X}_Small.jpg", "AlbumArt_{X}_Large.jpg"))
        )
    }

    @Test fun `all four image types match`() {
        for (ext in listOf("jpg", "jpeg", "png", "webp", "JPG", "WebP")) {
            assertEquals("cover.$ext", FolderImageMatcher.pick(listOf("cover.$ext")))
        }
    }

    @Test fun `a single unnamed image is used, several are not`() {
        assertEquals("scan001.jpg", FolderImageMatcher.pick(listOf("scan001.jpg", "track.mp3")))
        assertNull(FolderImageMatcher.pick(listOf("scan001.jpg", "scan002.jpg")))
    }

    @Test fun `non-images, hidden files and look-alikes are ignored`() {
        assertNull(FolderImageMatcher.pick(listOf("cover.txt", "cover.gif", ".cover.jpg", "notes.pdf")))
        assertNull(FolderImageMatcher.pick(emptyList()))
        assertEquals("covers.jpg", FolderImageMatcher.pick(listOf("covers.jpg"))) // only via "single image"
        assertNull(FolderImageMatcher.pick(listOf("covers.jpg", "discover.png")))
    }

    @Test
    fun `an image named after the folder comes after albumart and before the single-image rule`() {
        assertEquals("Ember Theory.jpg", FolderImageMatcher.pick(listOf("Ember Theory.jpg", "scan.png"), "Ember Theory"))
        assertEquals("AlbumArtSmall.jpg", FolderImageMatcher.pick(listOf("ember theory.png", "AlbumArtSmall.jpg"), "Ember Theory"))
    }

    @Test
    fun `the single-image rule skips back covers, discs and booklets`() {
        assertNull(FolderImageMatcher.pick(listOf("back.jpg")))
        assertNull(FolderImageMatcher.pick(listOf("CD1.png")))
        assertNull(FolderImageMatcher.pick(listOf("Booklet page 1.jpg")))
        assertEquals("art.jpg", FolderImageMatcher.pick(listOf("art.jpg")))
    }
}
