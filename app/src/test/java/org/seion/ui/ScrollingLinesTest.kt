package org.seion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.AppContainer
import org.seion.playback.PlayerUiState
import org.seion.playback.QueueEntry
import org.seion.settings.SeionSettings
import org.seion.ui.components.LocalContainer
import org.seion.ui.components.LocalReduceMotion
import org.seion.ui.components.RowSubtitle
import org.seion.ui.components.TrackRow
import org.seion.ui.components.artistSubtitle
import org.seion.ui.nowplaying.MiniPlayerLayer
import org.seion.ui.nowplaying.NowPlayingActions
import org.seion.ui.nowplaying.NowPlayingScreen

/**
 * Long lines roll once: instead of "+N" (artists) and "…" (titles, albums), a line on Now
 * playing or the mini-player that doesn't fit waits, rolls left until its last character reaches the inner edge of
 * the right margin (Now playing: a clear 13 % margin on each side) or gutter (mini-player: it rests at the column
 * start), holds, fades back to the start and stays there. Lists don't scroll: a row shows every artist, cut with "…".
 * Drives the real screens on a manual clock.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollingLinesTest {
    @get:Rule val compose = createComposeRule()

    private val container by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AppContainer(context)
    }

    private val longTitle = "The Extraordinarily Long Title of a Song That Rolls Once"
    private val four = "Ana Lima; Bruno Souza; Carla Dias; Davi Rocha"
    private val joined = "Ana Lima, Bruno Souza, Carla Dias, Davi Rocha"
    private val spoken = "Ana Lima, Bruno Souza, Carla Dias and Davi Rocha"
    private val longAlbum = "A Very Long Album Name for the Scrolling Line Demo Edition"

    private fun item(id: String, title: String, artist: String, album: String) = MediaItem.Builder()
        .setMediaId("/sdcard/Music/$id.flac")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build())
        .build()

    private var current by mutableStateOf(item("long", longTitle, four, longAlbum))
    private var visible by mutableStateOf(true)
    private val actions = NowPlayingActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun state() = PlayerUiState(connected = true, current = current, currentIndex = 0, queue = listOf(QueueEntry(0, current)))

    private fun nowPlaying(reduceMotion: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container, LocalReduceMotion provides reduceMotion) {
                Box(Modifier.size(360.dp, 640.dp)) {
                    SharedTransitionLayout {
                        AnimatedVisibility(visible) {
                            NowPlayingScreen(state(), MutableStateFlow(0L), null, SeionSettings(), this, actions)
                        }
                    }
                }
            }
        }
        advance(16)
    }

    private fun miniPlayer() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                SharedTransitionLayout {
                    AnimatedVisibility(true) {
                        MiniPlayerLayer(state(), MutableStateFlow(0L), null, this, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
        advance(16)
    }

    private fun advance(ms: Long) = compose.mainClock.advanceTimeBy(ms)

    /** A state change from the test thread, seen by the next frame (the paused main looper would hold it back). */
    private fun change(block: () -> Unit) {
        block()
        Snapshot.sendApplyNotifications()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /**
     * A line box (px) and where an overflowing text's box rests (its left edge) and holds (its right edge), within
     * [tol] px. Now playing: at the inner edges of its 13 % margins, give or take the first and last glyphs' side
     * bearings (the ink, not the box, meets the margins: `ScrollingMarginsTest`). Mini-player: rests at the column
     * start, holds at the inner edge of the right gutter.
     */
    private class Line(val first: Float, val second: Float, startAligned: Boolean = false) {
        private val gutter = (second - first) * 0.13f
        val restLeft = if (startAligned) first else first + gutter
        val endRight = second - gutter
        val tol = if (startAligned) 2f else 4f
    }

    /** Now playing's text lines: the screen minus 20 dp each side (px at xhdpi). */
    private val npLine = Line(40f, 680f)

    /**
     * The text's left edge, unclipped (its bounds are clipped to the line, so a start-aligned line's bounds start at
     * the column start even while it has rolled away).
     */
    private fun left(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x

    private fun assertAtStart(tag: String, line: Line) {
        assertEquals("$tag: the first character rests at the start (${bounds(tag)})", line.restLeft, left(tag), line.tol)
    }

    private fun assertScrolling(tag: String, line: Line) {
        val b = bounds(tag)
        assertTrue("$tag has not moved left (${left(tag)}, $b)", left(tag) < line.restLeft - 10f)
        assertTrue("$tag is already at its end ($b)", b.right > line.endRight + 10f)
    }

    private fun atEnd(tag: String, line: Line) = kotlin.math.abs(bounds(tag).right - line.endRight) <= line.tol

    private fun atStart(tag: String, line: Line) = kotlin.math.abs(left(tag) - line.restLeft) <= line.tol

    /**
     * Runs the [tags]' passes (they run at the same time) to their ends: each reaches the inner edge of the right
     * gutter, holds there, returns to the start in one step (a fade, not a scroll back) and stays.
     */
    private fun assertFinishPasses(tags: List<String>, line: Line) {
        val reachedEnd = mutableMapOf<String, Long>()
        val back = mutableMapOf<String, Long>()
        val t0 = compose.mainClock.currentTime
        while (back.size < tags.size) {
            advance(48) // three frames (the clock advances by whole frames)
            val t = compose.mainClock.currentTime - t0
            assertTrue("$tags: passes not over after 40 s (ends $reachedEnd, back $back)", t < 40_000)
            for (tag in tags) {
                when {
                    tag !in reachedEnd -> if (atEnd(tag, line)) reachedEnd[tag] = t
                    tag in back -> assertTrue("$tag moved after its pass (${bounds(tag)})", atStart(tag, line))
                    atStart(tag, line) -> back[tag] = t
                    else -> assertTrue("$tag left the end other than by jumping to the start (${bounds(tag)})", atEnd(tag, line))
                }
            }
        }
        for (tag in tags) {
            // Hold 2.5 s, fade out 0.15 s, then the jump (sampled every 48 ms; "at the end" within 2 px, a little before
            // the very end while it brakes).
            val held = back.getValue(tag) - reachedEnd.getValue(tag)
            assertTrue("$tag held its end for $held ms", held in 2_600L..2_900L)
        }
        advance(10_000)
        for (tag in tags) assertAtStart(tag, line)
    }

    // ---------------------------------------------------------------- Now playing

    @Test fun `now playing's overflowing title, artist and album roll once and rest at the start`() {
        nowPlaying()
        val lines = listOf("np_title", "np_artist_text", "np_album")
        for (tag in lines) assertAtStart(tag, npLine)
        advance(1_000)
        for (tag in lines) assertAtStart(tag, npLine)
        advance(2_000) // 3 s: past the 1.5 s delay, all three on their way
        for (tag in lines) assertScrolling(tag, npLine)
        assertFinishPasses(listOf("np_title", "np_artist_text", "np_album"), npLine)
    }

    @Test fun `semantics carry the whole text while it scrolls`() {
        nowPlaying()
        advance(3_000)
        compose.onNodeWithTag("np_title", useUnmergedTree = true).assertTextEquals(longTitle)
        compose.onNodeWithTag("np_album", useUnmergedTree = true).assertTextEquals(longAlbum)
        // The artist line shows everyone joined; TalkBack reads the list with "and".
        compose.onNodeWithTag("np_artist").assertTextEquals(joined)
        assertEquals(
            listOf(spoken),
            compose.onNodeWithTag("np_artist").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        )
    }

    @Test fun `a line that fits stays centred and never moves`() {
        current = item("short", "Banda", "Hall & Oates", "Curto")
        nowPlaying()
        val first = bounds("np_title")
        assertEquals("centred", 360f, first.center.x, 2f)
        for (step in listOf(1_000L, 2_000L, 5_000L)) {
            advance(step)
            assertEquals(first, bounds("np_title"))
        }
        assertEquals(360f, bounds("np_artist_text").center.x, 2f)
    }

    @Test fun `with reduced motion an overflowing line ends in an ellipsis and never moves`() {
        nowPlaying(reduceMotion = true)
        val first = bounds("np_title")
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("np_title", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        assertTrue("no ellipsis", results.single().isLineEllipsized(0))
        for (step in listOf(2_000L, 5_000L)) {
            advance(step)
            assertEquals(first, bounds("np_title"))
        }
    }

    @Test fun `a track change restarts the pass from the start`() {
        nowPlaying()
        advance(3_000)
        assertScrolling("np_title", npLine)
        change { current = item("other", "Another Remarkably Long Song Title That Also Rolls Once", four, longAlbum) }
        advance(32)
        compose.onNodeWithTag(
            "np_title",
            useUnmergedTree = true
        ).assertTextEquals("Another Remarkably Long Song Title That Also Rolls Once")
        assertAtStart("np_title", npLine)
        advance(1_000)
        assertAtStart("np_title", npLine)
        advance(2_000)
        assertScrolling("np_title", npLine)
    }

    @Test fun `a track change restarts every line, also one whose text stays the same`() {
        nowPlaying()
        advance(20_000) // the first passes are over
        for (tag in listOf("np_artist_text", "np_album")) assertAtStart(tag, npLine)
        // Same artist and album, another track.
        change { current = item("same-album", "Another Remarkably Long Song Title That Also Rolls Once", four, longAlbum) }
        advance(1_000)
        for (tag in listOf("np_title", "np_artist_text", "np_album")) assertAtStart(tag, npLine)
        advance(2_000)
        for (tag in listOf("np_title", "np_artist_text", "np_album")) assertScrolling(tag, npLine)
    }

    @Test fun `opening now playing again restarts the pass`() {
        nowPlaying()
        advance(3_000)
        assertScrolling("np_title", npLine)
        change { visible = false }
        advance(2_000)
        change { visible = true }
        advance(1_000)
        assertAtStart("np_title", npLine)
        advance(2_500)
        assertScrolling("np_title", npLine)
    }

    // ---------------------------------------------------------------- mini-player

    @Test fun `the mini-player's title and artist roll once too`() {
        miniPlayer()
        val col = bounds("mini_text")
        val line = Line(col.left, col.right, startAligned = true)
        assertAtStart("mini_title", line)
        assertAtStart("mini_artist", line)
        advance(3_000)
        assertScrolling("mini_title", line)
        assertScrolling("mini_artist", line)
        // TalkBack reads every artist.
        assertEquals(
            listOf(spoken),
            compose.onNodeWithTag(
                "mini_artist",
                useUnmergedTree = true
            ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.map {
                it.text
            }
        )
        assertFinishPasses(listOf("mini_title", "mini_artist"), line)
    }

    // ---------------------------------------------------------------- rows don't scroll

    @Test fun `a row shows every artist joined, without a counter`() {
        var sub: RowSubtitle? = null
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                val s = artistSubtitle(four, "Demo Album")
                sub = s
                TrackRow(longTitle, s.text, 200_000, "row", subtitleArtists = s) {}
            }
        }
        compose.waitForIdle()
        assertEquals("$joined · Demo Album", sub!!.text)
        assertEquals("$spoken, Demo Album", sub!!.description)
        compose.onAllNodes(androidx.compose.ui.test.hasTestTag("artist_counter"), useUnmergedTree = true).fetchSemanticsNodes().let {
            assertEquals("a +N counter is still drawn", 0, it.size)
        }
    }
}
