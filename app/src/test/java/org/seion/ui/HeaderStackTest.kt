package org.seion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.seion.ui.library.HeaderStack

/**
 * At a fixed 110 dp the album header's pills would cover the "1 track · 1 min" line at font scale 2.0. They keep the
 * reference position, and move below the text when it is taller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HeaderStackTest {
    @get:Rule val compose = createComposeRule()

    private fun layout(textHeight: Int) {
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                HeaderStack(
                    text = { Box(Modifier.size(100.dp, textHeight.dp).testTag("text")) },
                    actions = { Box(Modifier.size(100.dp, 32.dp).testTag("actions")) }
                )
            }
        }
    }

    @Test fun `short text keeps the reference position`() {
        layout(60)
        assertEquals(110.dp, compose.onNodeWithTag("actions").getBoundsInRoot().top)
    }

    @Test fun `tall text pushes the pills below it`() {
        layout(150)
        val text = compose.onNodeWithTag("text").getBoundsInRoot()
        val actions = compose.onNodeWithTag("actions").getBoundsInRoot()
        assertTrue("actions ${actions.top} overlap text ending at ${text.bottom}", actions.top >= text.bottom + 8.dp)
    }
}
