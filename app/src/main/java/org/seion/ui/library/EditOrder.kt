package org.seion.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import org.seion.R
import org.seion.playback.QueueLogic
import org.seion.ui.components.IconBtn
import org.seion.ui.components.ListHeader
import org.seion.ui.components.Pill
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionType

/**
 * How long the "Removed …" bar offers Undo: M3's short snackbar time, stretched like M3's own SnackbarHost by the
 * system's "Time to take action" accessibility setting.
 */
internal const val UNDO_MS = 4_000L

/** A row just removed: the bar's text and how to put it back. */
class Removal(val text: String, val stamp: Long, val undo: () -> Unit)

/** The Undo bar's state, shared by the playlist page and the play queue. */
@Stable
class UndoState {
    var removal by mutableStateOf<Removal?>(null)
        internal set

    fun show(text: String, undo: () -> Unit) {
        removal = Removal(text, System.nanoTime(), undo)
    }

    fun undo() {
        val r = removal ?: return
        removal = null
        r.undo()
    }

    fun dismiss() {
        removal = null
    }
}

/** An [UndoState] whose bar goes away after [UNDO_MS], stretched by the accessibility timeout. */
@Composable
fun rememberUndoState(): UndoState {
    val state = remember { UndoState() }
    val a11y = LocalAccessibilityManager.current
    val removal = state.removal
    LaunchedEffect(removal) {
        if (removal != null) {
            delay(
                a11y?.calculateRecommendedTimeoutMillis(UNDO_MS, containsIcons = false, containsText = true, containsControls = true)
                    ?: UNDO_MS
            )
            if (state.removal === removal) state.dismiss()
        }
    }
    return state
}

/** "Removed “Title”" with Undo, above the mini-player, for [UNDO_MS]. */
@Composable
fun UndoBar(state: UndoState, modifier: Modifier = Modifier) {
    val removal = state.removal
    AnimatedVisibility(removal != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        val r = removal ?: return@AnimatedVisibility
        Row(
            Modifier
                .padding(bottomPadding(8.dp))
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF23262D))
                .padding(start = 16.dp, end = 4.dp)
                .testTag("undo_bar"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                r.text,
                style = SeionType.Body.copy(color = SeionColors.Ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                stringResource(R.string.undo),
                color = SeionColors.Accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { state.undo() }
                    .padding(horizontal = 12.dp, vertical = 14.dp)
                    .testTag("undo_action")
            )
        }
    }
}

/**
 * "Edit order" (rearranging the songs by hand), shared by a playlist and the play queue so both behave alike: a header
 * ("Drag to reorder" + Done), a drag handle on the left of each row, × on the right, and TalkBack's Move up / Move down
 * actions on each row. [onMove] reports every finished move (a drop, or one accessibility step) with the list after it;
 * [onRemove] a ×. Test tags: `<tag>_drag_<pos>`, `<tag>_remove_<pos>`, `<tag>_edit_done`.
 *
 * [row] draws one row with the given leading (handle) and trailing (×) slots; its modifier carries the drag state.
 */
@Composable
fun <T> EditOrderList(
    items: List<T>,
    key: (T) -> Any,
    tag: String,
    removeDescription: String,
    onMove: (from: Int, to: Int, result: List<T>) -> Unit,
    onRemove: (pos: Int, item: T) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    row: @Composable (pos: Int, item: T, leading: @Composable () -> Unit, trailing: @Composable () -> Unit) -> Unit
) {
    var local by remember(items) { mutableStateOf(items) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(1f) }
    val dragDesc = stringResource(R.string.reorder)
    val upLabel = stringResource(R.string.move_up)
    val downLabel = stringResource(R.string.move_down)
    // The header stays above the list (as the queue's does), so Done is always at hand.
    Column(modifier.fillMaxSize()) {
        ListHeader(dragDesc) { Pill(stringResource(R.string.done), "${tag}_edit_done", solid = true, onClick = onDone) }
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = bottomPadding()) {
            itemsIndexed(local, key = { _, it -> key(it) }) { pos, item ->
                val currentPos by rememberUpdatedState(pos)
                val isDragged = dragging == pos
                Box(
                    Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                        .then(if (isDragged) Modifier.background(Color(0xFF1A1C21)) else Modifier.animateItem())
                        .onSizeChanged { rowHeight = it.height.toFloat().coerceAtLeast(1f) }
                        .semantics {
                            customActions =
                                listOfNotNull(
                                    if (pos > 0) {
                                        CustomAccessibilityAction(upLabel) {
                                            local = QueueLogic.move(local, pos, pos - 1).also { onMove(pos, pos - 1, it) }
                                            true
                                        }
                                    } else {
                                        null
                                    },
                                    if (pos < local.lastIndex) {
                                        CustomAccessibilityAction(downLabel) {
                                            local = QueueLogic.move(local, pos, pos + 1).also { onMove(pos, pos + 1, it) }
                                            true
                                        }
                                    } else {
                                        null
                                    }
                                )
                        }
                ) {
                    row(
                        pos,
                        item,
                        {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .semantics { contentDescription = dragDesc }
                                    .testTag("${tag}_drag_$pos")
                                    .pointerInput(key(item)) {
                                        detectDragGestures(
                                            onDragStart = {
                                                dragFrom = currentPos
                                                dragging = currentPos
                                                dragOffset = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                val cur = dragging ?: return@detectDragGestures
                                                dragOffset += amount.y
                                                if (dragOffset > rowHeight / 2 && cur < local.lastIndex) {
                                                    local = QueueLogic.move(local, cur, cur + 1)
                                                    dragging = cur + 1
                                                    dragOffset -= rowHeight
                                                } else if (dragOffset < -rowHeight / 2 && cur > 0) {
                                                    local = QueueLogic.move(local, cur, cur - 1)
                                                    dragging = cur - 1
                                                    dragOffset += rowHeight
                                                }
                                            },
                                            onDragEnd = {
                                                val to = dragging
                                                if (to != null && to != dragFrom) onMove(dragFrom, to, local)
                                                dragging = null
                                                dragOffset = 0f
                                            },
                                            onDragCancel = {
                                                local = items
                                                dragging = null
                                                dragOffset = 0f
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(painterResource(R.drawable.ic_drag), null, tint = SeionColors.Faint, modifier = Modifier.size(20.dp))
                            }
                        },
                        {
                            IconBtn(
                                R.drawable.ic_close,
                                removeDescription,
                                "${tag}_remove_$pos",
                                tint = SeionColors.Faint,
                                iconSize = 18.dp
                            ) {
                                onRemove(pos, item)
                            }
                        }
                    )
                }
            }
        }
    }
}
