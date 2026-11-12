package org.seion.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.AppContainer
import org.seion.playback.QueueEntry
import org.seion.playback.QueueLogic
import org.seion.ui.components.LocalContainer
import org.seion.ui.library.EditOrderList
import org.seion.ui.library.QueueList
import org.seion.ui.library.editItemFor
import org.seion.ui.library.queueItemOfNowPlaying

/**
 * The play queue in sections (the played rows, NOW PLAYING, UP NEXT) with an "Edit order" mode shared with
 * the playlist page: handles and × only while editing, and TalkBack's Move up / Move down.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QueueSectionsTest {
    @get:Rule val compose = createComposeRule()

    private val container by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AppContainer(context)
    }

    private fun entry(i: Int) = QueueEntry(
        i,
        MediaItem
            .Builder()
            .setMediaId("/m/$i.flac")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Song $i").setArtist("Artist").setDurationMs(60_000).build())
            .build()
    )

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun topOfText(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test fun `played rows, then NOW PLAYING, then UP NEXT, and no handles or remove buttons outside Edit order`() {
        val queue = (0 until 5).map(::entry)
        var edit = 0
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                QueueList(queue, QueueLogic.sections(5, 2), currentIndex = 2, isPlaying = false, state = rememberLazyListState(), onEdit = {
                    edit++
                }, onLongClick = {}, onPlay = {})
            }
        }
        val order = listOf(top("queue_1"), topOfText("NOW PLAYING"), top("queue_2"), topOfText("UP NEXT"), top("queue_3"))
        assertEquals(order.sorted(), order)
        assertTrue(compose.onAllNodesWithTag("queue_drag_0", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("queue_remove_0", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("queue_edit").performClick()
        assertEquals(1, edit)
    }

    @Test fun `Edit order has a handle and a remove button per row, and Move up and Move down for TalkBack`() {
        val queue = (0 until 3).map(::entry)
        val moves = mutableListOf<Pair<Int, Int>>()
        val removed = mutableListOf<Int>()
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                EditOrderList(queue, key = {
                    it.item.mediaId
                }, tag = "queue", removeDescription = "Remove from the queue", onMove = { from, to, _ ->
                    moves += from to to
                }, onRemove = { pos, _ -> removed += pos }, onDone = {}) { pos, e, leading, trailing ->
                    org.seion.ui.components.TrackRow(
                        e.item.mediaMetadata.title.toString(),
                        null,
                        0,
                        "queue_$pos",
                        leading = leading,
                        trailing = trailing
                    ) {}
                }
            }
        }
        for (i in 0 until 3) {
            compose.onNodeWithTag("queue_drag_$i", useUnmergedTree = true).assertExistsCompat()
            compose.onNodeWithTag("queue_remove_$i", useUnmergedTree = true).assertExistsCompat()
        }
        compose.onNodeWithTag("queue_edit_done").assertExistsCompat()
        val withActions = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes()
        val labels = withActions.map { n -> n.config[SemanticsActions.CustomActions].map { it.label } }
        assertEquals(listOf(listOf("Move down"), listOf("Move up", "Move down"), listOf("Move up")), labels)
        compose.runOnIdle { withActions[0].config[SemanticsActions.CustomActions].single().action() }
        assertEquals(listOf(0 to 1), moves)
        compose.onNodeWithTag("queue_remove_2", useUnmergedTree = true).performClick()
        assertEquals(listOf(2), removed)
        // The handle says what it does.
        assertEquals(
            listOf("Drag to reorder"),
            compose.onNodeWithTag(
                "queue_drag_0",
                useUnmergedTree = true
            ).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        )
    }

    @Test fun `the list opens at NOW PLAYING, and Edit order at the row the list showed`() {
        val s = QueueLogic.sections(10, 4)
        assertEquals(4, queueItemOfNowPlaying(s))
        // List items: 0..3 played rows 0..3, 4 NOW PLAYING label, 5 row 4, 6 UP NEXT label, 7.. rows 5...
        // Edit order items: row n at n.
        assertEquals(0, editItemFor(0, s))
        assertEquals(3, editItemFor(3, s))
        assertEquals(4, editItemFor(4, s))
        assertEquals(4, editItemFor(5, s))
        assertEquals(4, editItemFor(6, s))
        assertEquals(5, editItemFor(7, s))
        assertEquals(9, editItemFor(11, s))
        // Without a current track the two lists line up.
        assertEquals(3, editItemFor(3, QueueLogic.sections(10, -1)))
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() = assertExists()
}
