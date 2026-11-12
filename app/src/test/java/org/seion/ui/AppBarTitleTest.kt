package org.seion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.R
import org.seion.ui.components.AppBar
import org.seion.ui.components.IconBtn
import org.seion.ui.settings.LICENSES_TITLE

/** App-bar titles fit beside the bar's icons: a long pt-BR licences title read "Licenças de código a…" there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppBarTitleTest {
    @get:Rule val compose = createComposeRule()

    private fun assertTitleFits() {
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                AppBar(stringResource(LICENSES_TITLE), onBack = {}) {
                    IconBtn(R.drawable.ic_queue, "q", "q") {}
                    IconBtn(R.drawable.ic_more, "m", "m") {}
                }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("bar_title").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val l = results.single()
        val natural = l.multiParagraph.intrinsics.maxIntrinsicWidth
        assertFalse("title cut off ($natural > ${l.size.width})", natural > l.size.width + 1f)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h640dp-xhdpi")
    fun `the pt-BR licences title fits`() = assertTitleFits()

    @Test fun `the English licences title fits`() = assertTitleFits()
}
