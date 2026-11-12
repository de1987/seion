package org.seion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import org.seion.ui.library.AlbumHeaderActions

/**
 * In pt-BR the album header's "Aleatório" pill could read "Alea" at font scale 1.0 and "A" at 1.3. Both pills
 * must show their whole label in the header's column (360 dp screen, the column starts at 158 dp) at 1.0, 1.3 and 2.0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumHeaderActionsTest {
    @get:Rule val compose = createComposeRule()

    private fun layoutOf(label: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun check(fontScale: Float, play: String, shuffle: String) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                Box(Modifier.width(360.dp)) { AlbumHeaderActions({}, {}, Modifier.padding(start = 158.dp, end = 12.dp)) }
            }
        }
        for (label in listOf(play, shuffle)) {
            val l = layoutOf(label)
            // The whole label on one line: its natural width fits in the width it was given.
            val natural = l.multiParagraph.intrinsics.maxIntrinsicWidth
            assertFalse("\"$label\" is cut off at font scale $fontScale ($natural > ${l.size.width})", natural > l.size.width + 1f)
        }
    }

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h640dp-xhdpi")
    fun `pt-BR labels fit at 1_0`() = check(1.0f, "Reproduzir", "Aleatório")

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h640dp-xhdpi")
    fun `pt-BR labels fit at 1_3`() = check(1.3f, "Reproduzir", "Aleatório")

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h640dp-xhdpi")
    fun `pt-BR labels fit at 2_0`() = check(2.0f, "Reproduzir", "Aleatório")

    @Test fun `English labels fit at 2_0`() = check(2.0f, "Play", "Shuffle")
}
