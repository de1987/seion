package org.seion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.AppContainer
import org.seion.playback.PlayerUiState
import org.seion.playback.QueueEntry
import org.seion.settings.SeionSettings
import org.seion.ui.components.LocalContainer
import org.seion.ui.nowplaying.NowPlayingActions
import org.seion.ui.nowplaying.NowPlayingScreen

/**
 * The Now playing layout stays put whatever the artist line holds. A two-line artist would move the title, album,
 * seek bar and transport by different amounts between tracks, and on the 360 × 640 dp reference it could push the
 * transport towards the navigation bar. Long lines roll, so the artist is always one line: nothing moves between a
 * short artist and a long list, on any screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingArtistLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val one = track("Banda", "Hall & Oates")
    private val two =
        track("Longo Nome", "The Extraordinarily Long Named Brazilian Chamber Ensemble Orchestra; Ana Lima; Bruno Souza; Carla Dias")

    private fun track(title: String, artist: String) = MediaItem.Builder()
        .setMediaId("/sdcard/Music/$title.flac")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle("Artist Lines Demo").build())
        .build()

    @Before fun workManager() = WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())

    private val actions = NowPlayingActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    /** Bounds in dp of the tags, on a screen of [heightDp] with a 24 dp status bar, for both tracks. */
    @OptIn(ExperimentalSharedTransitionApi::class)
    private fun measure(heightDp: Int, fontScale: Float): Pair<Map<String, Rect>, Map<String, Rect>> {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        var current by mutableStateOf(one)
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalContainer provides container, LocalDensity provides Density(d.density, fontScale)) {
                // The status bar as padding: Robolectric reports no insets.
                Box(Modifier.padding(top = 24.dp).size(360.dp, (heightDp - 24).dp)) {
                    SharedTransitionLayout {
                        AnimatedVisibility(true) {
                            NowPlayingScreen(
                                PlayerUiState(
                                    connected = true,
                                    current = current,
                                    currentIndex = 0,
                                    queue = listOf(QueueEntry(0, current))
                                ),
                                MutableStateFlow(0L),
                                null,
                                SeionSettings(),
                                this,
                                actions
                            )
                        }
                    }
                }
            }
        }
        fun bounds(): Map<String, Rect> {
            compose.waitForIdle()
            val px = compose.density.density
            return listOf("np_title", "np_artist_text", "np_album", "np_seek", "np_play").associateWith { tag ->
                val b = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                Rect(b.left / px, b.top / px, b.right / px, b.bottom / px)
            }
        }
        val a = bounds()
        current = two
        val b = bounds()
        return a to b
    }

    /** Asserts nothing moves between the tracks and the artist stays one line. */
    private fun assertSteady(heightDp: Int, fontScale: Float) {
        val (a, b) = measure(heightDp, fontScale)
        println("$heightDp dp @ $fontScale: one=$a two=$b")
        for (tag in listOf("np_title", "np_artist_text", "np_album", "np_seek", "np_play")) {
            assertEquals("$tag moved between tracks (${a[tag]} -> ${b[tag]})", a.getValue(tag).top, b.getValue(tag).top, 0.5f)
        }
        // Above a 48 dp three-button navigation bar, and the album line never squeezed.
        for (m in listOf(a, b)) {
            assertEquals("album squeezed", a.getValue("np_album").height, m.getValue("np_album").height, 0.5f)
            val play = m.getValue("np_play")
            assert(play.bottom <= heightDp - 48 + 0.5f) { "transport bottom ${play.bottom} dp is under the navigation bar" }
        }
        assertEquals(
            "the artist is one line on both tracks",
            a.getValue("np_artist_text").height,
            b.getValue("np_artist_text").height,
            0.5f
        )
    }

    @Test fun `tall screen keeps the layout steady between a short and a long artist`() {
        assertSteady(800, 1f)
    }

    @Test fun `tall screen at 1_3x text keeps the layout steady`() {
        assertSteady(800, 1.3f)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun `the 360 x 640 reference keeps the transport above the navigation bar and steady`() {
        assertSteady(640, 1f)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun `the 360 x 640 reference at 1_3x text keeps the transport above the navigation bar`() {
        assertSteady(640, 1.3f)
    }
}
