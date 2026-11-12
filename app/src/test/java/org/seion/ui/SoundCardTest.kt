package org.seion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.ui.settings.EmblemAlpha
import org.seion.ui.settings.SoundBrand
import org.seion.ui.settings.SoundCard

/** The Sound screen's brand cards: emblem + wordmark, a switch, a description and an info button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SoundCardTest {
    @get:Rule val compose = createComposeRule()

    private fun emblemAlpha(tag: String): Float =
        compose.onNodeWithTag("${tag}_emblem", useUnmergedTree = true).fetchSemanticsNode().config[EmblemAlpha]

    @Test fun `each card shows its wordmark, toggles, and dims its emblem when off`() {
        var brand by mutableStateOf(SoundBrand.CRYSTALX)
        var on by mutableStateOf(false)
        var infos = 0
        compose.setContent { SoundCard(brand, checked = on, onChange = { on = it }, onInfo = { infos++ }) }
        for (b in SoundBrand.entries) {
            brand = b
            on = false
            compose.waitForIdle()
            compose.onNodeWithText(if (b == SoundBrand.CRYSTALX) "CrystalX+" else "Seion RenovaPure").assertExists()
            compose.onNodeWithTag(b.tag).assertIsOff()
            val off = emblemAlpha(b.tag)
            // Subtle: dimmed, never gone.
            assertTrue("$b off alpha $off", off in 0.4f..0.75f)
            compose.onNodeWithTag(b.tag).performClick()
            assertTrue(on)
            compose.onNodeWithTag(b.tag).assertIsOn()
            compose.waitForIdle()
            assertEquals(1f, emblemAlpha(b.tag), 0f)
            val before = infos
            compose.onNodeWithTag("${b.tag}_info").performClick()
            assertEquals(before + 1, infos)
        }
    }

    @Test fun `the descriptions are the technology copy`() {
        compose.setContent { SoundCard(SoundBrand.RENOVAPURE, checked = true, onChange = {}, onInfo = {}) }
        compose.onNodeWithText("top octave", substring = true).assertExists()
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
            .action!!
            .invoke(results)
        return results.single()
    }

    @Config(qualifiers = "pt-rBR-w360dp-h640dp-xhdpi")
    @Test
    fun `pt-BR cards fit on a 360 dp screen at 1,0 and 1,3`() {
        var scale by mutableStateOf(1f)
        var brand by mutableStateOf(SoundBrand.CRYSTALX)
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                Box(Modifier.width(360.dp)) { SoundCard(brand, checked = true, onChange = {}, onInfo = {}) }
            }
        }
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        for (s in listOf(1f, 1.3f)) {
            for (b in SoundBrand.entries) {
                scale = s
                brand = b
                compose.waitForIdle()
                for (text in listOf(ctx.getString(b.nameRes), ctx.getString(b.summaryRes))) {
                    val l = layoutOf(text)
                    assertFalse("\"$text\" cut at $s", l.didOverflowHeight)
                    assertFalse("\"$text\" ellipsized at $s", (0 until l.lineCount).any { l.isLineEllipsized(it) })
                }
                // The wordmark stays whole on one line next to the emblem (it shrinks to fit).
                val mark = layoutOf(ctx.getString(b.nameRes))
                assertEquals("${b.name} wordmark lines at $s", 1, mark.lineCount)
                assertFalse("${b.name} wordmark overflows at $s", mark.didOverflowWidth)
            }
        }
    }
}
