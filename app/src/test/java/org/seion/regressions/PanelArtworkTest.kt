package org.seion.regressions

import android.graphics.Bitmap
import androidx.media3.common.MediaMetadata
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.seion.art.ArtRef
import org.seion.art.ArtUris
import org.seion.art.SeionBitmapLoader

/** The media panel artwork must come from Seion's resolver (front cover, per track), never the stream's first picture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class PanelArtworkTest {
    @Test fun `panel artwork uses the resolver even when the stream carries artwork data`() {
        val front = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val loader = SeionBitmapLoader(loadArt = { _: ArtRef, _: Int -> front })
        val meta =
            MediaMetadata
                .Builder()
                .setArtworkData(byteArrayOf(1, 2, 3, 4), MediaMetadata.PICTURE_TYPE_BACK_COVER)
                .setArtworkUri(ArtUris.toUri(ArtRef("/m/a/01.mp3", "/m/a", "Artist", "Album")))
                .build()
        val bmp = loader.loadBitmapFromMetadata(meta)!!.get(5, TimeUnit.SECONDS)
        assertSame(front, bmp)
    }

    @Test fun `a track without art gets the no-cover tile, not null`() {
        val tile = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val loader = SeionBitmapLoader({ _: ArtRef, _: Int -> null }, { tile })
        val uri = ArtUris.toUri(ArtRef("/m/a/02.mp3", "/m/a", null, null))
        assertSame(tile, loader.loadBitmap(uri).get(5, TimeUnit.SECONDS))
    }

    @Test fun onlineHitRefreshesSessionArtwork() {
        val ref = ArtRef("/m/a/03.mp3", "/m/a", "Artist", "Album")
        val item = org.seion.playback.MediaItems.fromFile(java.io.File("/m/a/03.mp3"))
        val refreshed = org.seion.playback.ArtworkRefresh.refreshed(item, ref.cacheKey, 2)!!
        assertEquals("v=2", refreshed.mediaMetadata.artworkUri!!.fragment)
        assertEquals(ref.cacheKey, ArtUris.fromUri(refreshed.mediaMetadata.artworkUri)!!.cacheKey)
        assertEquals(item.mediaId, refreshed.mediaId)
        assertEquals(item.localConfiguration!!.uri, refreshed.localConfiguration!!.uri)
        assertNull(org.seion.playback.ArtworkRefresh.refreshed(item, "/m/other.mp3", 2))
    }

    @Test fun `a chosen cover reloads the panel artwork of that album's queue items only`() {
        val item = org.seion.playback.MediaItems.fromSnapshot("/m/a/04.flac", "Four", "Artist", "Album", null, 1_000)
        val key = org.seion.art.CoverOverrides.key("/m/a", "Album")
        val refreshed = org.seion.playback.ArtworkRefresh.refreshedForChosen(item, setOf(key), 3)!!
        assertEquals("v=3", refreshed.mediaMetadata.artworkUri!!.fragment)
        assertEquals(item.localConfiguration!!.uri, refreshed.localConfiguration!!.uri)
        assertNull(
            org.seion.playback.ArtworkRefresh.refreshedForChosen(item, setOf(org.seion.art.CoverOverrides.key("/m/b", "Album")), 3)
        )
    }

    @Test fun `an identified queue item gets its new names and cover query, playback untouched`() {
        val path = "/m/Fleetwood Mac/Rumours/07 Dreams.wav"
        val item = org.seion.playback.MediaItems.fromFile(java.io.File(path))
        assertEquals(null, ArtUris.fromUri(item.mediaMetadata.artworkUri)!!.album)
        val row =
            org.seion.library.TrackEntity(
                path = path, folder = "/m/Fleetwood Mac/Rumours", fileName = "07 Dreams.wav", title = "Dreams", artist = "Fleetwood Mac",
                albumArtist = null, album = "Rumours", albumKey = "rumours", genre = null, year = null, trackNumber = null,
                discNumber = null, durationMs = 256_540, lastModified = 1, sizeBytes = 1, dateAdded = 1, hasEmbeddedArt = false
            )
        val updated = org.seion.playback.ArtworkRefresh.identified(item, row)!!
        assertEquals("Dreams", updated.mediaMetadata.title.toString())
        assertEquals("Fleetwood Mac", updated.mediaMetadata.artist.toString())
        val ref = ArtUris.fromUri(updated.mediaMetadata.artworkUri)!!
        assertEquals(ArtRef(path, "/m/Fleetwood Mac/Rumours", "Fleetwood Mac", "Rumours"), ref)
        assertEquals(item.mediaId, updated.mediaId)
        assertEquals(item.localConfiguration, updated.localConfiguration)
        assertNull(org.seion.playback.ArtworkRefresh.identified(item, row.copy(path = "/m/other.wav")))
    }
}
