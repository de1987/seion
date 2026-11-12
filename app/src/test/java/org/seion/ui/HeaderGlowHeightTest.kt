package org.seion.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.seion.ui.library.HeaderGlow

/**
 * In a list (unbounded height) the glow's square 12 px bitmap, laid out with fillMaxSize, would measure itself full
 * width and square, so an album with a cover would get a 360 dp header (the first track at 440 dp) instead of the
 * 166 dp reference. The glow only paints behind the header; it must never size it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HeaderGlowHeightTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `the cover glow does not make a page header taller than its reference height`() {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).asImageBitmap()
        compose.setContent {
            LazyColumn(Modifier.width(360.dp)) {
                item {
                    Box(Modifier.fillMaxWidth().heightIn(min = 166.dp).testTag("header")) { HeaderGlow(bmp) }
                }
            }
        }
        val b = compose.onNodeWithTag("header").getBoundsInRoot()
        assertEquals(166.dp, b.bottom - b.top)
    }
}
