package org.seion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
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
import org.seion.ui.components.ScrollPass
import org.seion.ui.nowplaying.MiniPlayerLayer
import org.seion.ui.nowplaying.NowPlayingActions
import org.seion.ui.nowplaying.NowPlayingScreen

/**
 * Margins of a rolling line, read from the drawn pixels. Now playing: both sides keep a clear 13 % margin at the
 * start rest and at the end hold (no faded text in them), the first glyph's ink rests a pixel inside the inner edge
 * of the left margin and the last glyph's ink holds a pixel inside the right one (side bearings included: the two
 * gaps match within 1 px). Mini-player: an overflowing line rests at the column start, where a line that fits starts.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollingMarginsTest {
    @get:Rule val compose = createComposeRule()

    private val container by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AppContainer(context)
    }

    private val four = "Ana Lima; Bruno Souza; Carla Dias; Davi Rocha"

    private fun item(title: String, artist: String, album: String) = MediaItem.Builder()
        .setMediaId("/sdcard/Music/margins.flac")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build())
        .build()

    private var current = item(
        "The Extraordinarily Long Title of a Song That Rolls Once",
        four,
        "A Very Long Album Name for the Scrolling Line Demo Edition"
    )
    private val actions = NowPlayingActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun state() = PlayerUiState(connected = true, current = current, currentIndex = 0, queue = listOf(QueueEntry(0, current)))

    private fun show(fontScale: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalContainer provides container, LocalDensity provides Density(d.density, fontScale)) {
                content()
            }
        }
        compose.mainClock.advanceTimeBy(16)
    }

    private fun nowPlaying(fontScale: Float) = show(fontScale) {
        Box(Modifier.fillMaxSize()) {
            SharedTransitionLayout {
                AnimatedVisibility(true) { NowPlayingScreen(state(), MutableStateFlow(0L), null, SeionSettings(), this, actions) }
            }
        }
    }

    private fun miniPlayer(fontScale: Float) = show(fontScale) {
        SharedTransitionLayout {
            AnimatedVisibility(true) { MiniPlayerLayer(state(), MutableStateFlow(0L), null, this, {}, {}, {}, {}, {}, {}) }
        }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private val density get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density

    /** A line box (px) and its 13 % margins' inner edges. */
    private class Line(val tag: String, val left: Float, val right: Float) {
        val gutter = ScrollPass.gutter(right - left)
        val innerLeft = left + gutter
        val innerRight = right - gutter
    }

    /**
     * Per column of the screen, how much brighter than its row's background (the darkest pixel of the row inside the
     * line box) the brightest pixel in the [line]'s text rows is: the ink profile.
     */
    private fun inkProfile(line: Line): IntArray {
        compose.waitForIdle()
        val img = compose.onRoot().captureToImage()
        val px = IntArray(img.width * img.height)
        img.readPixels(px)
        fun lum(c: Int) = maxOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        val band = bounds(line.tag)
        val x0 = line.left.toInt().coerceAtLeast(0)
        val x1 = ceil(line.right).toInt().coerceAtMost(img.width) - 1
        val col = IntArray(img.width)
        for (y in band.top.toInt().coerceAtLeast(0)..band.bottom.toInt().coerceAtMost(img.height - 1)) {
            var bg = 255
            for (x in x0..x1) bg = minOf(bg, lum(px[y * img.width + x]))
            for (x in x0..x1) col[x] = maxOf(col[x], lum(px[y * img.width + x]) - bg)
        }
        return col
    }

    private fun IntArray.inked(from: Int, to: Int) = (from..to).filter { this[it] >= INK }

    /**
     * The ink inside the line's two margins. On a side where text is hidden ([hiddenLeft] / [hiddenRight]) the fade
     * reaches nothing at the margin's inner edge, so the column holding that edge must be clear too (a seam there left
     * a hard sliver of the hidden glyph); on the other side the last glyph may touch it (1 px of anti-aliasing).
     */
    private fun marginInk(line: Line, col: IntArray, hiddenLeft: Boolean, hiddenRight: Boolean): List<Int> =
        col.inked(ceil(line.left).toInt(), floor(line.innerLeft).toInt() - if (hiddenLeft) 0 else 2) +
            col.inked(ceil(line.innerRight).toInt() + if (hiddenRight) -1 else 1, floor(line.right).toInt() - 1)

    private class Gaps(val restLeft: Float, val holdRight: Float)

    /**
     * Checks [lines] at the start rest (now) and at each one's end hold (its right edge has stopped after moving, plus
     * 400 ms): no ink in either margin, and returns the ink gaps to the margins' inner edges.
     */
    private fun measure(lines: List<Line>, label: String): Map<String, Gaps> {
        val restLeft = lines.associate { l ->
            val col = inkProfile(l)
            assertEquals(
                "$label ${l.tag}: ink in a margin at the start rest",
                emptyList<Int>(),
                marginInk(l, col, hiddenLeft = false, hiddenRight = true)
            )
            l.tag to col.inked(ceil(l.left).toInt(), floor(l.right).toInt() - 1).first() - l.innerLeft
        }
        val result = mutableMapOf<String, Gaps>()
        val t0 = compose.mainClock.currentTime
        val lastRight = lines.associate { it.tag to bounds(it.tag).right }.toMutableMap()
        val moved = mutableSetOf<String>()
        val stableSince = mutableMapOf<String, Long>()
        while (result.size < lines.size) {
            compose.mainClock.advanceTimeBy(48)
            val t = compose.mainClock.currentTime - t0
            assertTrue("$label: no end hold for ${lines.map { it.tag } - result.keys}", t < 40_000)
            for (l in lines) {
                if (l.tag in result) continue
                val r = bounds(l.tag).right
                if (r != lastRight[l.tag]) {
                    moved += l.tag
                    stableSince.remove(l.tag)
                } else if (l.tag in moved) {
                    stableSince.putIfAbsent(l.tag, t)
                }
                lastRight[l.tag] = r
                val since = stableSince[l.tag] ?: continue
                if (t - since < 400) continue
                val col = inkProfile(l)
                assertEquals(
                    "$label ${l.tag}: ink in a margin at the end hold",
                    emptyList<Int>(),
                    marginInk(l, col, hiddenLeft = true, hiddenRight = false)
                )
                val last = col.inked(ceil(l.left).toInt(), floor(l.right).toInt() - 1).last()
                result[l.tag] = Gaps(restLeft.getValue(l.tag), l.innerRight - (last + 1))
            }
        }
        return result
    }

    private fun nowPlayingMargins(fontScale: Float) {
        nowPlaying(fontScale)
        val w = compose.onRoot().fetchSemanticsNode().size.width
        val pad = 20 * density
        val lines = listOf("np_title", "np_artist_text", "np_album").map { Line(it, pad, w - pad) }
        val gaps = measure(lines, "now playing fs=$fontScale")
        for ((tag, g) in gaps) {
            println("margins np fs=$fontScale $tag: left gap at rest ${g.restLeft} px, right gap at hold ${g.holdRight} px")
            assertTrue(
                "now playing fs=$fontScale $tag: left gap at rest ${g.restLeft} px != right gap at hold ${g.holdRight} px",
                abs(g.restLeft - g.holdRight) <= 1.01f
            )
            // The outline starts (and ends) a pixel inside the margin's inner edge (room for anti-aliasing), not a side
            // bearing away from it; read in whole pixel columns, the anti-aliased fringe reaches about a pixel further.
            assertTrue("$tag: rest gap ${g.restLeft}", g.restLeft in -1.01f..2.01f)
            assertTrue("$tag: hold gap ${g.holdRight}", g.holdRight in -1.01f..2.01f)
        }
    }

    @Test fun `now playing keeps equal clear margins at the start rest and the end hold`() = nowPlayingMargins(1f)

    @Test fun `now playing keeps equal clear margins at font scale 1_3`() = nowPlayingMargins(1.3f)

    // ---------------------------------------------------------------- mini-player

    /** The mini-player's title fits, its artist line doesn't: both start at the column start at rest. */
    private fun miniAligned(fontScale: Float) {
        current = item("Banda", four, "Curto")
        miniPlayer(fontScale)
        val col = bounds("mini_text")
        val g = ScrollPass.gutter(col.width)

        // Unclipped positions (the bounds are clipped to the column, so they would hide a line that has rolled away).
        fun x(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        fun assertAligned(moment: String) {
            assertEquals("fs=$fontScale $moment: the title starts at the column start", col.left, x("mini_title"), 1f)
            assertEquals("fs=$fontScale $moment: the artist line starts where the title does", x("mini_title"), x("mini_artist"), 1f)
        }
        assertAligned("before the pass")
        compose.mainClock.advanceTimeBy(1_000)
        assertAligned("still resting")
        // Rolling, then holding with its last character at the inner edge of the right fade.
        compose.mainClock.advanceTimeBy(2_000)
        var t = 0L
        while (abs(bounds("mini_artist").right - (col.right - g)) > 2f) {
            compose.mainClock.advanceTimeBy(48)
            t += 48
            assertTrue("fs=$fontScale: the artist line never reached the inner edge of the right fade", t < 30_000)
        }
        compose.mainClock.advanceTimeBy(30_000)
        assertAligned("after the pass")
    }

    @Test fun `the mini-player's lines start at the column start at rest`() = miniAligned(1f)

    @Test fun `the mini-player's lines start at the column start at font scale 1_3`() = miniAligned(1.3f)

    private companion object {
        /** A column has ink when a pixel is this much brighter (0..255) than its row's background. */
        const val INK = 24
    }
}
