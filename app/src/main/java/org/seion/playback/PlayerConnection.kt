package org.seion.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import org.seion.BuildConfig
import org.seion.settings.RepeatSetting
import org.seion.ui.NowPlayingRule

/** A skipped track: unreadable/corrupt, or a format no decoder on this device supports. */
data class PlaybackIssue(
    val title: String,
    val unsupportedFormat: Boolean,
    /** The queue stopped on this file (nothing playable after it). */
    val stopped: Boolean = false
)

/** One queue row: [index] is the playlist index, the list order is the play order. */
data class QueueEntry(val index: Int, val item: MediaItem)

/** A row removed from the queue, kept for Undo: its playlist index and item, the play order before, the size after. */
data class QueueRemoval(val index: Int, val item: MediaItem, val orderBefore: List<Int>, val shuffled: Boolean, val countAfter: Int)

data class PlayerUiState(
    val connected: Boolean = false,
    val current: MediaItem? = null,
    val currentIndex: Int = C.INDEX_UNSET,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatSetting = RepeatSetting.OFF,
    val queue: List<QueueEntry> = emptyList(),
    /**
     * The service's queue is known for this connection: it answered the restore check and the controller shows what it
     * restored. Until then an empty queue only means "not restored yet" (see NowPlayingRule).
     */
    val settled: Boolean = false
) {
    /** The current track's position in the play order (what "3/7" shows). */
    val orderPosition: Int get() = queue.indexOfFirst { it.index == currentIndex }
}

/** The service's sleep timer as the UI shows it: [deadline] on the elapsedRealtime clock (−1 = none), or end of song. */
data class SleepTimerState(val deadline: Long = SleepTimer.NONE, val endOfSong: Boolean = false) {
    val active: Boolean get() = deadline != SleepTimer.NONE || endOfSong

    companion object {
        fun from(extras: Bundle): SleepTimerState = SleepTimerState(
            extras.getLong(SeionCommands.KEY_SLEEP_DEADLINE, SleepTimer.NONE),
            extras.getBoolean(SeionCommands.KEY_SLEEP_END_OF_SONG, false)
        )
    }
}

/** The UI's single MediaController: every screen (mini-player, now playing, queue) observes the same state. */
class PlayerConnection(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var connectJob: Job? = null
    private var ticker: Job? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    // The restore check of the current connection: answered, and how many items the service had then.
    private var restoreAnswered = false
    private var serviceItems = 0
    private var settled = false

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)

    /** Current position, ticking while playing (separate from [state] so lists don't recompose every tick). */
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _sleepTimer = MutableStateFlow(SleepTimerState())

    /** The service's sleep timer (session extras). */
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    fun connect() {
        if (controller != null || connectJob?.isActive == true) return
        connectJob =
            scope.launch {
                val c = buildController() ?: return@launch
                controller = c
                c.addListener(listener)
                _sleepTimer.value = SleepTimerState.from(c.sessionExtras)
                val restore = if (android.os.Environment.isExternalStorageManager()) command(c, SeionCommands.RESTORE_IF_EMPTY) else null
                refresh()
                pending.forEach { it(c) }
                pending.clear()
                // The service answers once its saved queue is restored (or there was nothing to restore).
                serviceItems =
                    try {
                        restore?.await()?.extras?.getInt(SeionCommands.KEY_COUNT, 0) ?: 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("SeionUi", "restore check failed", e)
                        0
                    }
                restoreAnswered = true
                refresh()
            }
    }

    /**
     * A service that is closing refuses new controllers (PlaybackService.onGetSession); the next bind reaches a fresh
     * instance, so a refused connection is tried again a few times instead of failing the UI.
     */
    private suspend fun buildController(): MediaController? {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        repeat(CONNECT_ATTEMPTS) { attempt ->
            try {
                return MediaController
                    .Builder(context, token)
                    .setListener(controllerListener)
                    .buildAsync()
                    .await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                // "Session rejected the connection request": the service is closing; the next bind reaches a new one.
                android.util.Log.i("SeionUi", "controller connection refused, the service is closing (attempt ${attempt + 1})")
                delay(CONNECT_RETRY_MS)
            } catch (e: Exception) {
                android.util.Log.w("SeionUi", "controller connection failed (attempt ${attempt + 1})", e)
                delay(CONNECT_RETRY_MS)
            }
        }
        return null
    }

    fun disconnect() {
        connectJob?.cancel()
        ticker?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        restoreAnswered = false
        serviceItems = 0
        settled = false
        _state.value = _state.value.copy(connected = false, settled = false)
        _sleepTimer.value = SleepTimerState()
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending += block.also { connect() }
    }

    private val _errors = MutableSharedFlow<PlaybackIssue>(extraBufferCapacity = 8)

    /** A track that could not be played (the service skips it); the UI shows a short, non-blocking message. */
    val errors: SharedFlow<PlaybackIssue> = _errors.asSharedFlow()

    private val controllerListener =
        object : MediaController.Listener {
            override fun onCustomCommand(
                controller: MediaController,
                command: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                when (command.customAction) {
                    SeionCommands.EVENT_UNSUPPORTED ->
                        _errors.tryEmit(PlaybackIssue(args.getString(SeionCommands.KEY_TITLE).orEmpty(), unsupportedFormat = true))
                    SeionCommands.EVENT_SKIPPED ->
                        _errors.tryEmit(
                            PlaybackIssue(
                                args.getString(SeionCommands.KEY_TITLE).orEmpty(),
                                unsupportedFormat = false,
                                stopped = args.getBoolean(SeionCommands.KEY_STOPPED)
                            )
                        )
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                _sleepTimer.value = SleepTimerState.from(extras)
            }
        }

    private val listener =
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = refresh(retick = events.containsAny(*POSITION_EVENTS))

            // Player errors are reported by the service's EVENT_SKIPPED (the controller can miss the transient error).
        }

    /** [retick]: the position may have jumped (seek, new track, play/pause), not just a buffering or metadata event. */
    private fun refresh(retick: Boolean = true) {
        val c = controller ?: return
        val shuffle = c.shuffleModeEnabled
        // Latched for the connection: once known, a queue emptied later is a real end (now playing closes).
        if (!settled) settled = NowPlayingRule.settled(restoreAnswered, serviceItems, c.mediaItemCount)
        _state.value =
            PlayerUiState(
                connected = true,
                current = displayedCurrent(c.currentMediaItem),
                currentIndex = c.currentMediaItemIndex,
                isPlaying = c.isPlaying,
                durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: c.currentMediaItem?.mediaMetadata?.durationMs ?: 0,
                shuffle = shuffle,
                repeat = RepeatSetting.fromMedia3(c.repeatMode),
                queue = playOrder(c.currentTimeline, shuffle).map { QueueEntry(it, c.getMediaItemAt(it)) },
                settled = settled
            )
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "SeionUi",
                "order shuffle=$shuffle current=${c.currentMediaItemIndex} ${_state.value.queue.map {
                    it.index
                }}"
            )
        }
        // Restarted on every event that can move the position (a seek, a new track, play): the ticks re-align on the
        // new position's grid. Other events (loading, metadata) leave the running ticker alone: each would cost a frame.
        if (!retick && c.isPlaying && ticker?.isActive == true) return
        _position.value = c.currentPosition
        ticker?.cancel()
        if (c.isPlaying) ticker = scope.launch { tick() }
    }

    /**
     * While playing: the position just past each whole second (the elapsed time) and, in between, about once per pixel
     * of a full-width progress bar ([PositionTicks]; a fixed 200 ms tick would redraw 5 times a second on any
     * screen with the mini-player). Each wait is computed from the position read at that tick, so an early wake-up corrects itself.
     */
    private suspend fun tick() {
        while (true) {
            val c = controller ?: return
            val pos = c.currentPosition
            _position.value = pos
            PositionTicks.lastTickAtMs = android.os.SystemClock.elapsedRealtime()
            val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            delay(PositionTicks.delayMs(pos, PositionTicks.stepMs(duration, context.resources.displayMetrics.widthPixels)))
        }
    }

    private fun displayedCurrent(controllerItem: MediaItem?): MediaItem? {
        val pending = pendingStart ?: return controllerItem
        val age = android.os.SystemClock.elapsedRealtime() - pending.second
        val id = QueueLogic.displayedCurrent(controllerItem?.mediaId, pending.first.mediaId, age)
        if (id == controllerItem?.mediaId) {
            if (age >= QueueLogic.PENDING_START_MS || id == pending.first.mediaId) pendingStart = null
            return controllerItem
        }
        return pending.first
    }

    private fun playOrder(timeline: Timeline, shuffle: Boolean): List<Int> {
        if (timeline.isEmpty) return emptyList()
        val out = ArrayList<Int>(timeline.windowCount)
        var i = timeline.getFirstWindowIndex(shuffle)
        while (i != C.INDEX_UNSET && out.size < timeline.windowCount) {
            out += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        return out
    }

    /** Plays [items] from [start]; [shuffle] = true starts a shuffled play of the list (the Shuffle buttons). */
    /**
     * Plays [items] from [start]. [shuffle] = the list Shuffle buttons: a one-shot shuffled order for this play only;
     * the global shuffle mode is left as it is. With shuffle mode on, the service re-applies "current first" itself.
     */
    fun play(items: List<MediaItem>, start: Int, shuffle: Boolean = false) {
        if (items.isEmpty()) return
        val list = if (shuffle) items.shuffled(Random) else items
        val first = if (shuffle) 0 else start.coerceIn(0, items.lastIndex)
        pendingStart = list[first] to android.os.SystemClock.elapsedRealtime()
        withController { c ->
            c.setMediaItems(list, first, 0)
            c.prepare()
            c.play()
        }
        refresh()
    }

    /** The first item of a list just started: shown until the controller reports it. */
    private var pendingStart: Pair<MediaItem, Long>? = null

    fun togglePlay() = withController { c ->
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = withController { it.seekToNext() }

    fun previous() = withController { it.seekToPrevious() }

    fun seekTo(ms: Long) = withController {
        it.seekTo(ms)
        _position.value = ms
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController { it.repeatMode = RepeatSetting.fromMedia3(it.repeatMode).next().media3 }

    fun playAt(index: Int) = withController {
        it.seekTo(index, 0)
        it.play()
    }

    /** Queue reorder by play-order positions; while shuffled only the shuffle order changes. */
    fun move(fromPos: Int, toPos: Int) = withController { c ->
        val order = _state.value.queue.map { it.index }
        if (fromPos !in order.indices || toPos !in order.indices || fromPos == toPos) return@withController
        if (c.shuffleModeEnabled) {
            val newOrder = QueueLogic.move(order, fromPos, toPos).toIntArray()
            command(c, SeionCommands.SET_ORDER, Bundle().apply { putIntArray(SeionCommands.KEY_ORDER, newOrder) })
        } else {
            c.moveMediaItem(fromPos, toPos)
        }
    }

    /**
     * Removes the row at play-order position [pos] (the service moves on first when it is the current track); returns
     * what [undoRemove] needs to put it back (null when there is no such row).
     */
    fun removeAt(pos: Int): QueueRemoval? {
        val queue = _state.value.queue
        val entry = queue.getOrNull(pos) ?: return null
        val removal = QueueRemoval(entry.index, entry.item, queue.map { it.index }, _state.value.shuffle, countAfter = queue.size - 1)
        withController { c ->
            command(
                c,
                SeionCommands.REMOVE_AT,
                Bundle().apply {
                    putInt(SeionCommands.KEY_INDEX, entry.index)
                    putInt(SeionCommands.KEY_COUNT, queue.size)
                }
            )
        }
        return removal
    }

    /**
     * Undo of [removeAt]: the song goes back to its playlist index and, while shuffled, the play order it had, in one
     * session command ([SeionCommands.RESTORE_ITEM]: the service inserts and re-orders in the same step, and drops the
     * Undo when the queue changed meanwhile, [QueueLogic.canRestore]). A removed current track comes back as a row;
     * playback stays on the track that followed it.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun undoRemove(r: QueueRemoval) = withController { c ->
        command(
            c,
            SeionCommands.RESTORE_ITEM,
            Bundle().apply {
                putInt(SeionCommands.KEY_INDEX, r.index)
                putInt(SeionCommands.KEY_COUNT, r.countAfter)
                putBundle(SeionCommands.KEY_ITEM, r.item.toBundleIncludeLocalConfiguration())
                if (r.shuffled) putIntArray(SeionCommands.KEY_ORDER, r.orderBefore.toIntArray())
            }
        )
    }

    /** Starts the sleep timer for [minutes], [SleepTimer.END_OF_SONG], or turns it off ([SleepTimer.OFF]). */
    fun setSleepTimer(minutes: Int) = withController { c ->
        command(c, SeionCommands.SET_SLEEP_TIMER, Bundle().apply { putInt(SeionCommands.KEY_MINUTES, minutes) })
    }

    fun extendSleepTimer(minutes: Int = SleepTimer.EXTEND_MINUTES) = withController { c ->
        command(c, SeionCommands.EXTEND_SLEEP_TIMER, Bundle().apply { putInt(SeionCommands.KEY_MINUTES, minutes) })
    }

    private fun command(c: MediaController, action: String, args: Bundle = Bundle.EMPTY): ListenableFuture<SessionResult> =
        c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)

    private companion object {
        /** The events after which the position is read again and the ticks re-aligned. */
        val POSITION_EVENTS =
            intArrayOf(
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED
            )
        const val CONNECT_ATTEMPTS = 3
        const val CONNECT_RETRY_MS = 300L
    }
}
