package org.seion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.seion.ui.library.EntryScroll
import org.seion.ui.library.LocalEntryScroll
import org.seion.ui.library.rememberEntryListState

/**
 * A list left at its end (Settings, Sound: short lists) came back short of it after a real close: the restore ran
 * before the mini-player was back, so the list had less bottom padding, the saved row was past its end and was clamped
 * there, and nothing put it back once the padding (and the scroll range) grew (24 dp short).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
class KeepScrollPaddingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a list restored past its end reaches the saved row once its bottom padding grows`() {
        val vm = SeionViewModel()
        vm.push(Screen.Settings)
        val id = vm.top.id
        // 20 rows of 50 dp in 300 dp: without padding the last first-visible row is 14; with 100 dp of padding, 16.
        vm.setScroll(id, ScrollPos(16, 0))
        var padding by mutableStateOf(0.dp)
        lateinit var state: LazyListState
        compose.setContent {
            CompositionLocalProvider(LocalEntryScroll provides EntryScroll(id, vm)) {
                state = rememberEntryListState()
                LazyColumn(Modifier.fillMaxWidth().height(300.dp), state = state, contentPadding = PaddingValues(bottom = padding)) {
                    items(20) { i -> Box(Modifier.fillMaxWidth().height(50.dp).testTag("row_$i")) }
                }
            }
        }
        compose.waitForIdle()
        assertEquals("clamped while the padding is missing", 14, state.firstVisibleItemIndex)

        padding = 100.dp // the mini-player is back
        compose.waitForIdle()
        assertEquals(16, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }
}
