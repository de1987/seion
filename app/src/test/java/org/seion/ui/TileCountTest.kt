package org.seion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.ui.library.TileCount

/** At font scale 2.0 the home tiles' counts must stay whole, not end in "…" ("5049 albu…"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileCountTest {
    @get:Rule val compose = createComposeRule()

    private fun assertWhole(text: String, fontScale: Float) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                // A home tile's inner width on a 360 dp screen.
                Box(Modifier.width(88.dp)) { TileCount(text) }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("tile_count").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val l = results.single()
        assertFalse("\"$text\" cut off at $fontScale", l.didOverflowHeight || l.isLineEllipsized(l.lineCount - 1))
    }

    @Test fun `counts stay whole at 2_0`() = assertWhole("5049 albums", 2.0f)

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h640dp-xhdpi")
    fun `pt-BR counts stay whole at 2_0`() = assertWhole("5052 músicas", 2.0f)

    @Test fun `counts stay whole at 1_0`() = assertWhole("5049 albums", 1.0f)
}
