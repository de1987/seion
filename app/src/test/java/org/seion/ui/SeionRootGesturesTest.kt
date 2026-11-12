package org.seion.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.AppContainer
import org.seion.ui.components.LocalContainer

/**
 * A swipe up on now playing must not reach the root: if now playing reported its drag, the root would scrub the
 * shared collapse transition with it. Here the whole root runs: while the transition is idle only now playing is composed;
 * any seek or animation of it would compose the mini-player too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeionRootGesturesTest {
    @get:Rule val compose = createComposeRule()

    private val container by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AppContainer(context)
    }

    private fun assertTransitionIdle(what: String, frames: Int) {
        repeat(frames) { f ->
            compose.mainClock.advanceTimeByFrame()
            val mini = compose.onAllNodesWithTag("mini_player").fetchSemanticsNodes().size
            assertTrue("$what: the collapse transition ran (frame $f)", mini == 0)
        }
    }

    @Test fun `a swipe up or a short drag down never moves the collapse transition`() {
        val vm = SeionViewModel().apply { nowPlaying = true }
        compose.setContent { CompositionLocalProvider(LocalContainer provides container) { SeionRoot(vm) } }
        compose.waitForIdle()
        compose.onNodeWithTag("now_playing").assertExists()
        assertTrue(compose.onAllNodesWithTag("mini_player").fetchSemanticsNodes().isEmpty())

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("now_playing").performTouchInput { swipeUp(startY = bottom - 200f, endY = top + 100f, durationMillis = 250) }
        assertTransitionIdle("swipe up", 40)

        compose.onNodeWithTag("now_playing").performTouchInput {
            down(Offset(centerX, 300f))
            repeat(5) {
                advanceEventTime(20)
                moveBy(Offset(0f, 20f))
            }
            advanceEventTime(300)
            up()
        }
        assertTransitionIdle("short drag down", 40)
        compose.mainClock.autoAdvance = true
    }
}
