package org.seion.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.AppContainer
import org.seion.playback.PlayerUiState
import org.seion.playback.QueueEntry
import org.seion.settings.BackgroundStyle
import org.seion.settings.SeionSettings
import org.seion.ui.ambient.Ambient
import org.seion.ui.ambient.AmbientParams
import org.seion.ui.ambient.CoverGeometry
import org.seion.ui.ambient.NowPlayingBackground
import org.seion.ui.components.LocalContainer
import org.seion.ui.nowplaying.MiniPlayerLayer
import org.seion.ui.nowplaying.NowPlayingActions
import org.seion.ui.nowplaying.NowPlayingScreen

/**
 * The touch gestures on Now playing: a swipe on the cover skips and leaves the new cover centred and in sight; a
 * swipe up, which has no action, does not animate; a swipe down follows the finger. Drives the real
 * [NowPlayingScreen] with touch input and measures where things are drawn.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingGesturesTest {
    @get:Rule val compose = createComposeRule()

    private val container by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AppContainer(context)
    }

    private var queueSize = 5
    private val items by lazy {
        (0 until queueSize).map { i ->
            MediaItem.Builder()
                .setMediaId("t$i")
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Track $i").setArtist("Artist").build())
                .build()
        }
    }

    private fun stateAt(i: Int) = PlayerUiState(
        connected = true,
        current = items[i],
        currentIndex = i,
        durationMs = 60_000,
        queue = items.mapIndexed { n, item -> QueueEntry(n, item) },
        settled = true
    )

    private var track by mutableStateOf(0)
    private var nexts = 0
    private var previouses = 0
    private var collapses = 0

    private fun show(spectrum: Boolean = false) {
        val actions =
            NowPlayingActions(
                togglePlay = {},
                next = {
                    nexts++
                    track = (track + 1) % items.size
                },
                previous = {
                    previouses++
                    track = (track + items.size - 1) % items.size
                },
                seekTo = {},
                toggleShuffle = {},
                cycleRepeat = {},
                collapse = { collapses++ },
                library = {},
                openQueue = {},
                openSettings = {},
                openSound = {}
            )
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                SharedTransitionLayout {
                    AnimatedVisibility(visible = true) {
                        NowPlayingScreen(
                            state = stateAt(track).copy(isPlaying = spectrum),
                            position = MutableStateFlow(0L),
                            ambient = null,
                            settings = SeionSettings(spectrum = spectrum),
                            visibility = this,
                            actions = actions
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private val rootWidth get() = compose.onRoot().fetchSemanticsNode().boundsInRoot.width

    /** The one cover drawn once everything has settled (two only while one slides out and the next slides in). */
    private fun settledCover(): Rect {
        val nodes = compose.onAllNodesWithTag("np_cover_frame", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("covers on screen after settling", 1, nodes.size)
        return nodes.single().boundsInRoot
    }

    private fun coverNode() = compose.onNodeWithTag("np_cover")

    private fun titleTop() = compose.onNodeWithTag("np_title").fetchSemanticsNode().boundsInRoot.top

    private fun assertCentred(what: String) {
        val c = settledCover()
        assertEquals("$what: cover centre x", rootWidth / 2, c.center.x, 1f)
    }

    @Test fun `a swipe left skips once and the next cover lands centred`() {
        show()
        coverNode().performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f, durationMillis = 150) }
        compose.waitForIdle()
        assertEquals(1, nexts)
        assertEquals(1, track)
        assertCentred("after a swipe left")
    }

    @Test fun `a swipe right goes back once and the cover lands centred`() {
        track = 2
        show()
        coverNode().performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f, durationMillis = 150) }
        compose.waitForIdle()
        assertEquals(1, previouses)
        assertEquals(1, track)
        assertCentred("after a swipe right")
    }

    // A guard.
    @Test fun `a short drag springs back to the centre without skipping`() {
        show()
        coverNode().performTouchInput {
            down(center)
            repeat(10) {
                advanceEventTime(30)
                moveBy(Offset(-6f, 0f))
            }
            advanceEventTime(300) // stop before lifting: no fling
            up()
        }
        compose.waitForIdle()
        assertEquals(0, nexts)
        assertCentred("after a short drag")
    }

    @Test fun `quick repeated swipes always end centred`() {
        show()
        repeat(4) {
            coverNode().performTouchInput { swipeLeft(startX = right - 10f, endX = centerX, durationMillis = 80) }
            compose.mainClock.advanceTimeBy(48)
        }
        compose.waitForIdle()
        assertEquals(4, nexts)
        assertCentred("after four quick swipes")
    }

    // A guard.
    @Test fun `a track change while the cover springs back leaves the new cover centred`() {
        show()
        compose.mainClock.autoAdvance = false
        coverNode().performTouchInput {
            down(center)
            repeat(8) {
                advanceEventTime(30)
                moveBy(Offset(-10f, 0f))
            }
            advanceEventTime(300)
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        track = 3 // the player moves on by itself (end of track, a headset button)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, nexts)
        assertCentred("after a track change mid-animation")
    }

    @Test fun `a swipe up moves nothing and starts no animation`() {
        show()
        val top = titleTop()
        val cover = settledCover()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("now_playing").performTouchInput { swipeUp(startY = bottom - 200f, endY = top + 100f, durationMillis = 250) }
        repeat(30) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals("title moved on a swipe up (frame $it)", top, titleTop(), 0.5f)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(cover, settledCover())
        assertEquals(0, collapses)
    }

    @Test fun `a swipe down follows the finger and springs back when it is short`() {
        show()
        val top = titleTop()
        compose.onNodeWithTag("now_playing").performTouchInput {
            down(Offset(centerX, 300f))
            repeat(10) {
                advanceEventTime(20)
                moveBy(Offset(0f, 20f))
            }
        }
        compose.waitForIdle()
        val moved = titleTop() - top
        assertTrue("now playing follows the finger down 1:1 (moved $moved of 200 px)", moved in 160f..200f)
        compose.onNodeWithTag("now_playing").performTouchInput {
            advanceEventTime(300) // hold still: no fling
            up()
        }
        compose.waitForIdle()
        assertEquals(0, collapses)
        assertEquals("springs back", top, titleTop(), 0.5f)
    }

    @Test fun `a long swipe down collapses once`() {
        show()
        compose.onNodeWithTag("now_playing").performTouchInput { swipeDown(startY = 300f, endY = 1100f, durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(1, collapses)
    }

    @Test fun `a short fling down collapses once`() {
        show()
        compose.onNodeWithTag("now_playing").performTouchInput { swipeDown(startY = 300f, endY = 420f, durationMillis = 40) }
        compose.waitForIdle()
        assertEquals(1, collapses)
    }

    // A guard: Compose locks nested draggables to one axis.
    @Test fun `a diagonal drag on the cover moves along one axis only`() {
        show()
        val top = titleTop()
        val x0 = settledCover().center.x
        compose.mainClock.autoAdvance = false
        coverNode().performTouchInput {
            down(center)
            repeat(12) {
                advanceEventTime(20)
                moveBy(Offset(-12f, 12f))
            }
        }
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        val dx = abs(
            compose.onAllNodesWithTag("np_cover_frame", useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.center.x - x0
        )
        val dy = abs(titleTop() - top)
        assertFalse("both axes moved: dx $dx, dy $dy", dx > 4f && dy > 4f)
        assertTrue("the drag moved something: dx $dx, dy $dy", dx > 4f || dy > 4f)
        coverNode().performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun `a swipe down on the mini-player scrubs nothing`() {
        var ups = 0
        var ends = 0
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                SharedTransitionLayout {
                    AnimatedVisibility(visible = true) {
                        MiniPlayerLayer(
                            state = stateAt(0),
                            position = MutableStateFlow(0L),
                            ambient = null,
                            visibility = this,
                            onExpand = {},
                            onDragUp = { ups++ },
                            onDragEnd = { ends++ },
                            onTogglePlay = {},
                            onNext = {},
                            onPrevious = {}
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("mini_player").performTouchInput { swipeDown(startY = top + 4f, endY = bottom + 200f, durationMillis = 200) }
        compose.waitForIdle()
        assertEquals("expand scrubs reported on a swipe down", 0, ups)
        assertEquals("expand releases reported on a swipe down", 0, ends)
        // A drag up still scrubs and releases once.
        compose.onNodeWithTag("mini_player").performTouchInput { swipeUp(startY = bottom - 4f, endY = top - 300f, durationMillis = 200) }
        compose.waitForIdle()
        assertTrue(ups > 0)
        assertEquals(1, ends)
    }

    /** The first column (px) on the cover's middle row where the near layer (green) outshines the far layer (red). */
    private fun nearLayerLeft(): Int {
        compose.waitForIdle()
        val img = compose.onRoot().captureToImage()
        val px = IntArray(img.width * img.height)
        img.readPixels(px)
        val row = (80 * 2) * img.width // the cover's middle (xhdpi: 2 px per dp)
        return (0 until img.width).first { x ->
            val c = px[row + x]
            ((c shr 8) and 0xFF) > ((c shr 16) and 0xFF)
        }
    }

    @Test fun `the near layer moves with a dragged cover`() {
        fun solid(c: Int) = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(c) }.asImageBitmap()
        val ambient =
            Ambient(
                key = "art@1",
                cover = solid(android.graphics.Color.GREEN),
                far = solid(android.graphics.Color.RED),
                near = solid(android.graphics.Color.GREEN),
                params = AmbientParams.DEFAULT,
                classicBase = Color.Red,
                accent = Color.Yellow,
                tint = Color.Blue
            )
        var shift by mutableStateOf(0f)
        compose.setContent {
            Box(Modifier.size(360.dp, 320.dp)) {
                NowPlayingBackground(
                    ambient,
                    BackgroundStyle.AMBIENT,
                    CoverGeometry(140.dp, 40.dp, 80.dp),
                    drifting = false,
                    statusBarHeight = 0.dp,
                    coverShift = { a -> if (a.key == "art@1") shift else 0f }
                )
            }
        }
        val still = nearLayerLeft()
        shift = -40f
        assertEquals("near layer shift", -40, nearLayerLeft() - still)
    }

    @Test fun `with the spectrum band on, the smaller cover lands centred and the band moves with the screen`() {
        // The band redraws every frame while it plays, so the clock is stepped by hand (never idle).
        compose.mainClock.autoAdvance = false
        show(spectrum = true)
        compose.mainClock.advanceTimeBy(1_000)
        val band = compose.onNodeWithTag("np_spectrum").fetchSemanticsNode().boundsInRoot
        val cover = settledCover()
        assertTrue("the band shrinks the cover (${cover.width} px)", cover.width < 480f)
        coverNode().performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f, durationMillis = 150) }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, nexts)
        assertCentred("band on, after a swipe left")
        assertEquals(band, compose.onNodeWithTag("np_spectrum").fetchSemanticsNode().boundsInRoot)

        compose.onNodeWithTag("now_playing").performTouchInput {
            down(Offset(centerX, 300f))
            repeat(10) {
                advanceEventTime(20)
                moveBy(Offset(0f, 20f))
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val bandMoved = compose.onNodeWithTag("np_spectrum").fetchSemanticsNode().boundsInRoot.top - band.top
        val coverMoved = settledCover().top - cover.top
        assertTrue("the band follows the finger ($bandMoved)", bandMoved > 150f)
        assertEquals("band and cover move together", coverMoved, bandMoved, 0.5f)
        compose.onNodeWithTag("now_playing").performTouchInput {
            advanceEventTime(300)
            up()
        }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(band, compose.onNodeWithTag("np_spectrum").fetchSemanticsNode().boundsInRoot)
    }
    // A cover dragged earlier must not keep its offset and come back with it.

    @Test fun `swipe left then quickly swipe right, the returning cover lands centred`() {
        show()
        compose.mainClock.autoAdvance = false
        coverNode().performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f, durationMillis = 150) }
        compose.mainClock.advanceTimeBy(200) // the old cover is still springing back while it slides out
        coverNode().performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f, durationMillis = 150) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1, nexts)
        assertEquals(1, previouses)
        assertEquals(0, track)
        assertCentred("after left then a quick right")
    }

    @Test fun `two-track queue with repeat all, two quick swipes left land centred`() {
        queueSize = 2
        show()
        compose.mainClock.autoAdvance = false
        coverNode().performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f, durationMillis = 150) }
        compose.mainClock.advanceTimeBy(200)
        coverNode().performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f, durationMillis = 150) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(2, nexts)
        assertEquals(0, track)
        assertCentred("after two quick swipes on a two-track queue")
    }

    @Test fun `the player going back to a cover dragged before shows it centred`() {
        show()
        compose.mainClock.autoAdvance = false
        coverNode().performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f, durationMillis = 150) }
        compose.mainClock.advanceTimeBy(200)
        // A short drag on the new cover (no skip) while the old one is still on its way back.
        coverNode().performTouchInput {
            down(center)
            repeat(3) {
                advanceEventTime(30)
                moveBy(Offset(-6f, 0f))
            }
            advanceEventTime(300)
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        track = 0 // the player goes back by itself (previous button, headset)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertCentred("after the player went back")
    }

    // A drag the system cancels (app sent to the background, the notification shade pulled) is not a release.

    @Test fun `a cancelled swipe down past the threshold springs back instead of collapsing`() {
        show()
        val top = titleTop()
        compose.onNodeWithTag("now_playing").performTouchInput {
            down(Offset(centerX, 200f))
            repeat(20) {
                advanceEventTime(16)
                moveBy(Offset(0f, 30f))
            }
            cancel()
        }
        compose.waitForIdle()
        assertEquals("a cancelled drag collapsed now playing", 0, collapses)
        assertEquals("springs back", top, titleTop(), 0.5f)
    }

    @Test fun `a cancelled cover drag past 30 percent skips nothing`() {
        show()
        coverNode().performTouchInput {
            down(Offset(right - 10f, centerY))
            repeat(20) {
                advanceEventTime(16)
                moveBy(Offset(-20f, 0f))
            }
            cancel()
        }
        compose.waitForIdle()
        assertEquals("a cancelled drag skipped", 0, nexts)
        assertCentred("after a cancelled drag")
    }

    @Test fun `a new drag on the mini-player text starts where the spring back is, without a jump`() {
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                SharedTransitionLayout {
                    AnimatedVisibility(visible = true) {
                        MiniPlayerLayer(
                            state = stateAt(0),
                            position = MutableStateFlow(0L),
                            ambient = null,
                            visibility = this,
                            onExpand = {},
                            onDragUp = {},
                            onDragEnd = {},
                            onTogglePlay = {},
                            onNext = { nexts++ },
                            onPrevious = { previouses++ }
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val text = compose.onNodeWithTag("mini_text", useUnmergedTree = true)
        val rest = text.fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = false
        text.performTouchInput {
            down(center)
            repeat(10) {
                advanceEventTime(30)
                moveBy(Offset(-10f, 0f)) // 100 px: short of a skip
            }
            advanceEventTime(300)
            up()
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val springing = text.fetchSemanticsNode().boundsInRoot.left - rest
        assertTrue("the text is still on its way back ($springing)", springing < -5f)
        text.performTouchInput {
            down(center)
            advanceEventTime(16)
            moveBy(Offset(-24f, 0f)) // a new drag, just past the touch slop
        }
        compose.mainClock.advanceTimeByFrame()
        val touched = text.fetchSemanticsNode().boundsInRoot.left - rest
        assertTrue("a new touch snapped the text back ($springing -> $touched)", touched < springing / 2)
        text.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(0, nexts + previouses)
        assertEquals(rest, text.fetchSemanticsNode().boundsInRoot.left, 0.5f)
    }
}
