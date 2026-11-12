package org.seion.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.ui.nowplaying.NowPlayingBounds
import org.seion.ui.nowplaying.cancelSeek

/**
 * A scrubbed collapse that is let go (predictive back cancelled, a short drag up on the mini-player) must return the
 * way it came, not go on towards the other end and then snap back (the cover, a shared element, would dive to the
 * mini-player's corner before jumping back to full size).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowPlayingSeekCancelTest {
    @get:Rule val compose = createComposeRule()

    private val red = Color.Red.toArgb()
    private val green = Color.Green.toArgb()

    private class Frame(val coverTop: Int, val screen: Int)

    /** The red shared cover's top edge (px, -1 when it isn't drawn) and the colour of now playing's own background. */
    private fun frame(): Frame {
        val img = compose.onRoot().captureToImage()
        val px = IntArray(img.width * img.height)
        img.readPixels(px)
        val i = px.indexOfFirst { it == red }
        return Frame(if (i < 0) -1 else i / img.width, px[300 * img.width + 10])
    }

    @Test fun `a cancelled scrub goes back the way it came, with no jump`() {
        val np = SeekableTransitionState(true)
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            SharedTransitionLayout(Modifier.size(360.dp, 640.dp).background(Color.Black)) {
                rememberTransition(np, label = "np").AnimatedContent(
                    transitionSpec = { fadeIn(tween(450)) togetherWith fadeOut(tween(350)) }
                ) { expanded ->
                    // Now playing: its own (green) background; the mini-player: a bar at the bottom over the browse
                    // screen (black).
                    Box(Modifier.fillMaxSize().then(if (expanded) Modifier.background(Color.Green) else Modifier)) {
                        val (y, s) = if (expanded) 90.dp to 248.dp else 576.dp to 64.dp
                        Box(
                            Modifier
                                .offset(if (expanded) 56.dp else 0.dp, y)
                                .size(s)
                                .sharedElement(
                                    rememberSharedContentState("cover"),
                                    this@AnimatedContent,
                                    boundsTransform = NowPlayingBounds
                                )
                                .background(Color.Red)
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val open = frame()
        assertEquals(90, open.coverTop)
        assertEquals(green, open.screen)

        // Scrub 60 % of the way towards the mini-player, as a drag or predictive back does.
        compose.mainClock.autoAdvance = false
        scope.launch { np.seekTo(0.6f, targetState = false) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val released = frame()
        assertTrue("the scrub moved the cover (${released.coverTop})", released.coverTop > open.coverTop + 5)
        assertTrue("the scrub faded now playing", released.screen != green)

        scope.launch { np.cancelSeek(reduceMotion = false) }
        var last = released
        repeat(60) { f ->
            compose.mainClock.advanceTimeByFrame()
            val now = frame()
            assertTrue("frame $f: cover not drawn", now.coverTop >= 0)
            assertTrue("frame $f: the cover went further away (${last.coverTop} -> ${now.coverTop})", now.coverTop <= last.coverTop + 1)
            val g = (now.screen shr 8) and 0xFF
            val gLast = (last.screen shr 8) and 0xFF
            assertTrue("frame $f: now playing faded out again ($gLast -> $g)", g >= gLast - 1)
            last = now
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(open.coverTop, frame().coverTop)
        assertEquals(green, frame().screen)
        assertTrue(np.currentState && np.targetState)
    }

    @Test fun `a scrub that never left home skips the run back`() {
        val np = SeekableTransitionState(true)
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            rememberTransition(np, label = "np").AnimatedContent(transitionSpec = { fadeIn() togetherWith fadeOut() }) { expanded ->
                Box(Modifier.size(if (expanded) 200.dp else 50.dp).background(Color.Green))
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        /** Frames until [block] returns, after a scrub that stayed at fraction 0. */
        fun framesFor(block: suspend () -> Unit): Int {
            scope.launch { np.seekTo(0f, targetState = false) }
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            var done = false
            scope.launch {
                block()
                done = true
            }
            var frames = 0
            while (!done && frames < 120) {
                compose.mainClock.advanceTimeByFrame()
                frames++
            }
            assertTrue(np.currentState && np.targetState)
            return frames
        }
        val settleOnly = framesFor { np.animateTo(true) }
        val cancel = framesFor { np.cancelSeek(reduceMotion = false) }
        assertTrue("cancel took $cancel frames, the settle alone $settleOnly", cancel <= settleOnly + 1)
        compose.mainClock.autoAdvance = true
    }
}
