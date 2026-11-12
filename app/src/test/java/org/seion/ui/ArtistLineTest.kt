package org.seion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.ui.components.CompactArtistLine
import org.seion.ui.components.NowPlayingArtistLine
import org.seion.ui.components.RowSubtitle
import org.seion.ui.components.TrackRow
import org.seion.ui.components.artistSubtitle
import org.seion.ui.theme.SeionType

/**
 * Artist lines: the tag is split into artists and shown
 * joined with ", " on one line (no "+N" counter): Now playing's line rolls when it doesn't fit
 * (`ScrollingLinesTest`), compact places and rows end in "…". TalkBack reads everyone ("… and Davi Rocha").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtistLineTest {
    @get:Rule val compose = createComposeRule()

    private val four = "Ana Lima; Bruno Souza; Carla Dias; Davi Rocha"

    /** Now playing's artist line as laid out there: the 360 dp screen with 20 dp side padding. */
    private fun nowPlaying(raw: String?) {
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                NowPlayingArtistLine(raw, SeionType.NpSecondary, "np_artist", Modifier.padding(horizontal = 20.dp))
            }
        }
    }

    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(
            tag,
            useUnmergedTree = true
        ).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    // ---------------------------------------------------------------- Now playing

    @Test fun `now playing shows every artist on one line, without a counter`() {
        nowPlaying(four)
        compose.onNodeWithTag("np_artist").assertTextEquals("Ana Lima, Bruno Souza, Carla Dias, Davi Rocha")
        assertEquals(1, layout("np_artist_text").lineCount)
        compose.onAllNodes(hasTestTag("artist_counter"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun `exactly two artists show both`() {
        nowPlaying("Daft Punk feat. Pharrell Williams")
        compose.onNodeWithTag("np_artist").assertTextEquals("Daft Punk, Pharrell Williams")
    }

    @Test fun `a single long name stays on one line`() {
        val name = "The Extraordinarily Long Named Brazilian Chamber Ensemble"
        nowPlaying(name)
        compose.onNodeWithTag("np_artist").assertTextEquals(name)
        assertEquals(1, layout("np_artist_text").lineCount)
    }

    @Test fun `TalkBack reads everyone on now playing, not the counter`() {
        nowPlaying(four)
        compose.onNodeWithTag("np_artist").assertContentDescriptionEquals("Ana Lima, Bruno Souza, Carla Dias and Davi Rocha")
    }

    @Test
    @Config(qualifiers = "pt-rBR-w360dp-h640dp-xhdpi")
    fun `TalkBack reads the pt-BR list`() {
        nowPlaying(four)
        compose.onNodeWithTag("np_artist").assertContentDescriptionEquals("Ana Lima, Bruno Souza, Carla Dias e Davi Rocha")
    }

    @Test fun `no artist shows Unknown artist`() {
        nowPlaying(null)
        compose.onNodeWithTag("np_artist").assertTextEquals("Unknown artist")
    }

    @Test fun `tapping the artist line lists every artist`() {
        nowPlaying(four)
        compose.onNodeWithTag("np_artist").performClick()
        for (name in listOf("Ana Lima", "Bruno Souza", "Carla Dias", "Davi Rocha")) compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithTag("np_artists_close").performClick()
        compose.onNodeWithText("Davi Rocha").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- compact places

    private fun mergedTexts(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.map { it.text }

    private fun mergedDescription(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)

    /** An album card: one TalkBack stop that reads the title and every artist. */
    private fun card(raw: String) {
        compose.setContent {
            Box(Modifier.width(200.dp)) {
                Column(Modifier.clickable { }.testTag("card")) {
                    Text("Demo Album")
                    CompactArtistLine(raw, style = SeionType.RowSub, tag = "card_artist")
                }
            }
        }
    }

    @Test fun `an album card reads the title and every artist as one stop`() {
        card("Ana Lima; Bruno Souza; Carla Dias")
        assertEquals(listOf("Demo Album", "Ana Lima, Bruno Souza and Carla Dias"), mergedTexts("card"))
        assertNull(mergedDescription("card"))
        // No separate stop for the artist line: nothing below the card merges on its own.
        assertEquals(1, compose.onAllNodes(hasText("Ana Lima", substring = true)).fetchSemanticsNodes().size)
        compose.onAllNodesWithText("+2", substring = true).assertCountEquals(0)
    }

    @Test fun `an album card with one artist reads as before`() {
        card("Earth, Wind & Fire")
        assertEquals(listOf("Demo Album", "Earth, Wind & Fire"), mergedTexts("card"))
        assertNull(mergedDescription("card"))
    }

    @Test fun `compact lines show every artist, without a counter`() {
        compose.setContent {
            Box(Modifier.width(160.dp)) { CompactArtistLine(four, style = SeionType.RowSub, tag = "card_artist") }
        }
        compose.onAllNodes(hasTestTag("artist_counter"), useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Ana Lima, Bruno Souza, Carla Dias and Davi Rocha").assertExists()
    }

    @Test fun `a single compact name is a plain text`() {
        compose.setContent { CompactArtistLine("Earth, Wind & Fire", tag = "card_artist") }
        compose.onNodeWithText("Earth, Wind & Fire").assertExists()
    }

    // ---------------------------------------------------------------- rows

    private fun row(raw: String, width: Int = 360) {
        compose.setContent {
            Box(Modifier.width(width.dp).testTag("box")) {
                val sub = artistSubtitle(raw, "Demo Album")
                TrackRow("Quatro Vozes", sub.text, 200_000, "row", subtitleArtists = sub) {}
            }
        }
    }

    @Test fun `row subtitles show every artist joined`() {
        var s: RowSubtitle? = null
        var single: RowSubtitle? = null
        compose.setContent {
            s = artistSubtitle(four, "Demo Album")
            single = artistSubtitle("Hall & Oates", "Demo Album")
        }
        compose.waitForIdle()
        assertEquals("Ana Lima, Bruno Souza, Carla Dias, Davi Rocha · Demo Album", s!!.text)
        assertEquals("Ana Lima, Bruno Souza, Carla Dias and Davi Rocha, Demo Album", s!!.description)
        assertEquals("Hall & Oates · Demo Album", single!!.text)
        assertNull(single!!.description)
    }

    @Test fun `a row reads its title and every artist as one stop`() {
        row(four)
        val texts = mergedTexts("row")!!
        assertEquals("Quatro Vozes", texts.first())
        assert(texts.contains("Ana Lima, Bruno Souza, Carla Dias and Davi Rocha, Demo Album")) { texts.toString() }
        assertNull(mergedDescription("row"))
        compose.onAllNodes(hasTestTag("artist_counter"), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun `a short now playing line is tappable only where the text is`() {
        nowPlaying("Ana Lima; Bruno")
        val w = compose.onNodeWithTag("np_artist").fetchSemanticsNode().boundsInRoot.width
        assert(w < 320f * 2 * 0.75f) { "tap target $w px spans the whole line" }
    }
}
