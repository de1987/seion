package org.seion.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.seion.MainActivity

/**
 * Changing the font size or the app language re-creates the activity: the view model survives, the composition does
 * not. Now playing re-opened over the screen that was open, because the media panel's last "open now playing" request
 * was replayed. A re-creation must restore exactly the
 * screen that was open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ConfigChangeRestoreTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `a re-created screen does not re-open now playing`() {
        val vm = SeionViewModel()
        var opened = 0
        val tester = StateRestorationTester(rule)
        tester.setContent { ExpandOnRequest(vm, hasCurrent = true) { opened++ } }

        vm.requestExpand() // the media panel's tap
        rule.waitForIdle()
        assertEquals(1, opened)

        // The user closes now playing, then changes the font size: the activity and its composition are re-created.
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals("now playing re-opened after a re-creation", 1, opened)

        vm.requestExpand() // a new tap still opens it
        rule.waitForIdle()
        assertEquals(2, opened)
    }

    @Test fun `a request waits for a track, and opens once`() {
        val vm = SeionViewModel()
        var opened = 0
        var hasCurrent by mutableStateOf(false)
        rule.setContent { ExpandOnRequest(vm, hasCurrent) { opened++ } }

        vm.requestExpand()
        rule.waitForIdle()
        assertEquals(0, opened)

        hasCurrent = true
        rule.waitForIdle()
        assertEquals(1, opened)

        hasCurrent = false
        rule.waitForIdle()
        hasCurrent = true
        rule.waitForIdle()
        assertEquals(1, opened)
    }

    @Test fun `the media panel's intent opens now playing on a fresh start, not when the activity is re-created`() {
        assertTrue(MainActivity.opensNowPlaying(MainActivity.ACTION_OPEN_NOW_PLAYING, recreated = false))
        // A re-created activity gets its original launch intent again: replaying it would re-open now playing.
        assertFalse(MainActivity.opensNowPlaying(MainActivity.ACTION_OPEN_NOW_PLAYING, recreated = true))
        assertFalse(MainActivity.opensNowPlaying(null, recreated = false))
        assertFalse(MainActivity.opensNowPlaying("android.intent.action.MAIN", recreated = false))
    }
}
