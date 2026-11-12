package org.seion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.art.FindCoversStatus
import org.seion.ui.settings.FindCoversAgainRow

/**
 * Settings › Library › "Find missing covers again": enabled only with the online switch on, one run at a time, its
 * progress under the row; a tap while it runs stops it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FindCoversAgainRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `online off, the row is disabled and a tap does nothing`() {
        var taps = 0
        compose.setContent {
            FindCoversAgainRow(enabled = false, status = FindCoversStatus.Idle, onStart = { taps++ }, onStop = { taps++ })
        }
        compose.onNodeWithTag("set_find_covers_again").assertIsNotEnabled().performClick()
        assertEquals(0, taps)
        compose.onNodeWithText("Search again for albums and tracks that still have no cover").assertExists()
    }

    private fun clickLabel(label: String?) = SemanticsMatcher("onClick label $label") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label ==
            label
    }

    @Test fun `a tap starts a run, a tap while it runs stops it, then the summary shows`() {
        var starts = 0
        var stops = 0
        var status by mutableStateOf<FindCoversStatus>(FindCoversStatus.Idle)
        compose.setContent { FindCoversAgainRow(enabled = true, status = status, onStart = { starts++ }, onStop = { stops++ }) }
        val row = compose.onNodeWithTag("set_find_covers_again")
        row.assertIsEnabled().performClick()
        assertEquals(1 to 0, starts to stops)
        compose.onNodeWithTag("find_covers_progress").assertDoesNotExist()

        // Before the total is known: indeterminate.
        status = FindCoversStatus.Running()
        compose.onNodeWithText("Searching for covers…").assertExists()
        compose.onNodeWithTag("find_covers_progress", useUnmergedTree = true).assertExists()
        // The row itself is the stop control (no separate button): TalkBack announces the action.
        row.assertIsEnabled().assert(clickLabel("Stop searching")).performClick()
        assertEquals(1 to 1, starts to stops)

        status = FindCoversStatus.Running(processed = 0, total = 46, found = 0)
        compose.onNodeWithText("Searching for covers… 0 of 46 albums · none found").assertExists()
        status = FindCoversStatus.Running(processed = 12, total = 46, found = 3)
        compose.onNodeWithText("Searching for covers… 12 of 46 albums · 3 found").assertExists()
        compose
            .onNodeWithTag("find_covers_progress", useUnmergedTree = true)
            .assertRangeInfoEquals(ProgressBarRangeInfo(12f / 46f, 0f..1f))
            .assertContentDescriptionEquals("Cover search progress")
        compose.onNodeWithText("Stop", substring = false).assertDoesNotExist()

        status = FindCoversStatus.Stopped(found = 3, missing = 31)
        compose.onNodeWithText("Stopped · 3 new covers found · 31 albums still without a cover").assertExists()
        compose.onNodeWithTag("find_covers_progress", useUnmergedTree = true).assertDoesNotExist()
        row.assert(clickLabel(null)).performClick()
        assertEquals(2 to 1, starts to stops)
        status = FindCoversStatus.Stopped(found = 0, missing = -1)
        compose.onNodeWithText("Stopped · No new covers found").assertExists()
        status = FindCoversStatus.Done(found = 7, missing = 39)
        compose.onNodeWithText("Done · 7 new covers found · 39 albums still without a cover").assertExists()
        status = FindCoversStatus.Done(found = 1, missing = 1)
        compose.onNodeWithText("Done · 1 new cover found · 1 album still without a cover").assertExists()
        status = FindCoversStatus.Done(found = 0, missing = 0)
        compose.onNodeWithText("Done · No new covers found").assertExists()
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
        var status by mutableStateOf<FindCoversStatus>(FindCoversStatus.Idle)
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, scale)) {
                Box(Modifier.width(360.dp)) { FindCoversAgainRow(enabled = true, status = status, onStart = {}, onStop = {}) }
            }
        }
        val cases =
            listOf(
                FindCoversStatus.Idle to "Procura de novo os álbuns e faixas que ainda estão sem capa",
                FindCoversStatus.Running() to "Buscando capas…",
                FindCoversStatus.Running(12, 46, 3) to "Buscando capas… 12 de 46 álbuns · 3 encontradas",
                FindCoversStatus.Running(1, 1, 1) to "Buscando capas… 1 de 1 álbum · 1 encontrada",
                // CLDR puts 0 in pt's "one": a plural would read "0 encontrada".
                FindCoversStatus.Running(1, 12, 0) to "Buscando capas… 1 de 12 álbuns · nenhuma encontrada",
                FindCoversStatus.Stopped(3, 31) to "Interrompida · 3 capas novas encontradas · 31 álbuns ainda sem capa",
                FindCoversStatus.Done(12, 3) to "Concluída · 12 capas novas encontradas · 3 álbuns ainda sem capa"
            )
        for (s in listOf(1f, 1.3f)) {
            for ((st, subtitle) in cases) {
                scale = s
                status = st
                compose.waitForIdle()
                for (text in listOf("Buscar novamente as capas que faltam", subtitle)) {
                    val l = layoutOf(text)
                    // Wrapping is fine; a cut (clipped height, an ellipsis) is not.
                    assertFalse("\"$text\" cut at $s", l.didOverflowHeight)
                    assertFalse("\"$text\" ellipsized at $s", (0 until l.lineCount).any { l.isLineEllipsized(it) })
                }
            }
        }
    }
}
