package org.seion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.ui.components.TrackRow

/**
 * A short Arabic file name must not end in "…" in a row with half its width free ("مرحبا بالعالم.m…"). Laid out at
 * their own measured width, the row's title and subtitle cut a right-to-left line that shapes a little wider than
 * that measurement; they take the row's whole text column.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RtlRowTitleTest {
    @get:Rule val compose = createComposeRule()

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun row(title: String, subtitle: String) {
        compose.setContent {
            Box(Modifier.width(360.dp)) { TrackRow(title, subtitle, 35_000, "row") {} }
        }
        compose.waitForIdle()
    }

    @Test fun `a short Arabic file name is not cut`() {
        row("مرحبا بالعالم.mp3", "فرقة · Canções")
        val title = layoutOf("مرحبا بالعالم.mp3")
        assertFalse("title ellipsized in a ${title.size.width} px box", title.isLineEllipsized(0))
        assertFalse(layoutOf("فرقة · Canções").isLineEllipsized(0))
    }

    @Test fun `a short Hebrew name is not cut`() {
        row("שלום עולם.mp3", "להקה · Canções")
        assertFalse(layoutOf("שלום עולם.mp3").isLineEllipsized(0))
    }

    @Test fun `a short mixed-script name is not cut`() {
        row("Ação — مرحبا 🎧.flac", "Mar · Canções")
        assertFalse(layoutOf("Ação — مرحبا 🎧.flac").isLineEllipsized(0))
    }

    @Test fun `the title and subtitle take the whole text column`() {
        // Laid out at the column's width, not at the text's own measurement, so a shaping difference can't cut it.
        row("مرحبا بالعالم.mp3", "فرقة · Canções")
        val title = layoutOf("مرحبا بالعالم.mp3")
        val sub = layoutOf("فرقة · Canções")
        assertTrue("title box ${title.size.width} px", title.size.width > title.multiParagraph.intrinsics.maxIntrinsicWidth + 50)
        assertTrue("subtitle box ${sub.size.width} px", sub.size.width == title.size.width)
    }

    @Test fun `a long name is still cut with an ellipsis`() {
        val long = "مرحبا بالعالم ".repeat(8) + ".mp3"
        row(long, "فرقة")
        assertTrue(layoutOf(long).isLineEllipsized(0))
    }
}
