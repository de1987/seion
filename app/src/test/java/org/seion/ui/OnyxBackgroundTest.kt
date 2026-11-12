package org.seion.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import org.seion.settings.BackgroundStyle
import org.seion.ui.ambient.Ambient
import org.seion.ui.ambient.AmbientParams
import org.seion.ui.ambient.CoverGeometry
import org.seion.ui.ambient.NowPlayingBackground
import org.seion.ui.settings.BackgroundStyleOptions
import org.seion.ui.theme.SeionColors

/**
 * Onyx: a Now playing background that is just the app's own black, the colour of the Settings
 * screens, with no ambient colour, glow or motion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnyxBackgroundTest {
    @get:Rule val compose = createComposeRule()

    private fun solid(color: Int) = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap()

    /** A loud, saturated cover's ambient: any of its layers would show in the capture. */
    private val vivid =
        Ambient(
            key = "vivid@1",
            cover = solid(android.graphics.Color.RED),
            far = solid(android.graphics.Color.RED),
            near = solid(android.graphics.Color.GREEN),
            params = AmbientParams.DEFAULT,
            classicBase = Color(0xFFE04020),
            accent = Color(0xFFFFD000),
            tint = Color(0xFF2060FF)
        )

    private fun render(style: BackgroundStyle, ambient: Ambient?): Set<Int> {
        compose.setContent {
            Box(Modifier.size(120.dp, 240.dp)) {
                NowPlayingBackground(ambient, style, CoverGeometry(20.dp, 40.dp, 80.dp), drifting = true, statusBarHeight = 24.dp)
            }
        }
        compose.waitForIdle()
        val img = compose.onRoot().captureToImage()
        val px = IntArray(img.width * img.height)
        img.readPixels(px)
        return px.toSet()
    }

    @Test fun `Onyx is a solid fill of the app background, with or without a cover`() {
        assertEquals(setOf(SeionColors.Page.toArgb()), render(BackgroundStyle.ONYX, vivid))
    }

    @Test fun `Onyx with no track is the same plain background`() {
        assertEquals(setOf(SeionColors.Page.toArgb()), render(BackgroundStyle.ONYX, null))
    }

    @Test fun `the other styles still draw the cover's colours (the capture sees ambient layers)`() {
        assertTrue(render(BackgroundStyle.CLASSIC, vivid).size > 1)
    }

    @Test fun `the picker lists Onyx after the existing styles and selects it`() {
        var selected by mutableStateOf(BackgroundStyle.AMBIENT)
        compose.setContent { BackgroundStyleOptions(selected) { selected = it } }
        val tops = listOf("set_bg_ambient", "set_bg_classic", "set_bg_onyx").map {
            compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(tops.sorted(), tops)
        compose.onNodeWithText("Onyx").assertExists()
        compose.onNodeWithTag("set_bg_onyx").performClick()
        assertEquals(BackgroundStyle.ONYX, selected)
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
    fun `pt-BR fits on a 360 dp screen at 1,0 and 1,3`() {
        var scale by mutableStateOf(1f)
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                Box(Modifier.width(360.dp)) { BackgroundStyleOptions(BackgroundStyle.ONYX) {} }
            }
        }
        for (s in listOf(1f, 1.3f)) {
            scale = s
            compose.waitForIdle()
            for (text in listOf("Seion Infinite", "Clássico", "Ônix")) {
                val l = layoutOf(text)
                assertEquals("\"$text\" wraps at $s", 1, l.lineCount)
                assertFalse("\"$text\" cut at $s", l.didOverflowHeight)
                assertFalse("\"$text\" ellipsized at $s", l.isLineEllipsized(0))
            }
        }
    }
}
