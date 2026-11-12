package org.seion.art

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** front-1200 for dense screens, and the decode cap that keeps a 1200 px cover from filling the LRU. */
class CoverSizeTest {
    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    @Test fun `1200 px only when Now playing's cover is wider than 500 px`() {
        assertEquals(500, CoverArtSize.forDensity(2.0f)) // xhdpi, the 360 x 640 dp design target: 496 px
        assertEquals(1200, CoverArtSize.forDensity(2.25f)) // 360 dpi: 558 px
        assertEquals(1200, CoverArtSize.forDensity(2.625f)) // 420 dpi (1080 x 2400 phone): 651 px
        assertEquals(1200, CoverArtSize.forDensity(3.5f))
        assertEquals(500, CoverArtSize.forDensity(1.5f))
        assertEquals(500, CoverArtSize.forSlot(500))
        assertEquals(1200, CoverArtSize.forSlot(501))
    }

    @Test fun `the cover archive URL carries the chosen size, anything else falls back to 500`() {
        assertEquals("https://c/release-group/x/front-1200", MusicBrainzCoverSource.frontUrl("https://c", "x", 1200))
        assertEquals("https://c/release-group/x/front-500", MusicBrainzCoverSource.frontUrl("https://c", "x", 500))
        assertEquals("https://c/release-group/x/front-500", MusicBrainzCoverSource.frontUrl("https://c", "x", 250))
    }

    @Test fun `a dense phone asks for front-1200`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"release-groups":[{"id":"rg-1","score":100,"title":"Kind of Blue","primary-type":"Album","artist-credit":[{"name":"Miles Davis","joinphrase":""}]}]}"""
            ).build()
        )
        server.enqueue(MockResponse.Builder().code(200).body("IMG").build())
        val src = MusicBrainzCoverSource(
            limiter = RateLimiter(0),
            musicBrainzBase = server.url("/").toString().trimEnd('/'),
            coverArtBase = server.url("/caa").toString(),
            coverSize = { CoverArtSize.forDensity(2.625f) }
        )
        src.frontCover("Miles Davis", "Kind of Blue")
        server.takeRequest()
        assertEquals("/caa/release-group/rg-1/front-1200", server.takeRequest().url.encodedPath)
    }

    @Test fun `a decode more than one and a half times the asked size is scaled down to it`() {
        assertEquals(720 to 720, ArtRepository.cappedSize(1200, 1200, 720))
        assertEquals(320 to 240, ArtRepository.cappedSize(600, 450, 320)) // a 1200 px cover at a 320 px row: sampled to 600, then 320
        assertNull(ArtRepository.cappedSize(1000, 1000, 720)) // a 1000 px store cover at 720 stays as before
        assertNull(ArtRepository.cappedSize(500, 500, 720))
        assertNull(ArtRepository.cappedSize(1080, 1080, 720)) // exactly 1.5 x
    }
}
