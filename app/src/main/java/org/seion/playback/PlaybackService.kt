package org.seion.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.seion.BuildConfig
import org.seion.MainActivity
import org.seion.R
import org.seion.SeionApp
import org.seion.art.SeionBitmapLoader
import org.seion.playback.dsp.SeionRenderersFactory
import org.seion.settings.LocaleContext
import org.seion.settings.RepeatSetting
import org.seion.ui.components.NoCoverArt

/** Session-level rules, pure for the tests. */
@OptIn(UnstableApi::class)
object SeionSession {
    /**
     * Media3's default player commands without COMMAND_SET_SPEED_AND_PITCH: with float output the audio
     * sink drops Sonic, so a speed change would be a silent no-op; Seion never uses speed.
     */
    fun availablePlayerCommands(): Player.Commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
        .buildUpon()
        .remove(Player.COMMAND_SET_SPEED_AND_PITCH)
        .build()

    /**
     * The session commands a controller gets. Every controller has Media3's defaults (and the library commands of the
     * browse tree); Seion's own commands ([SeionCommands.all]: queue edits, shuffle order, queue restore, sleep timer)
     * only [appUi], Seion's own UI. System controllers need none of them: the media notification, the lock screen,
     * Android Auto and Bluetooth/media buttons use player commands (play/pause, previous/next, stop, and shuffle and
     * repeat as COMMAND_SET_SHUFFLE_MODE / COMMAND_SET_REPEAT_MODE), the panel's only extra button ([mediaButtons]) is
     * a player-command button, and the browse tree uses the library commands.
     */
    fun availableSessionCommands(appUi: Boolean): SessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
        .buildUpon()
        .apply { if (appUi) SeionCommands.all.forEach(::add) }
        .build()

    /** Whether [action] is one of Seion's own commands, which only its UI may send ([availableSessionCommands]). */
    fun isAppCommand(action: String): Boolean = SeionCommands.all.any { it.customAction == action }

    /**
     * The system media panel (shade, Quick Settings, lock screen): Previous / Play-Pause / Next come from the
     * player commands; the only extra button is Stop, a player-command button (`COMMAND_STOP` -> `player.stop()`,
     * Media3 >= 1.9) in the overflow slot, so Android 13+ shows it in the expanded card. No favourite, shuffle or
     * repeat buttons; slot 5 stays empty. No button sends a session command, so system controllers need none.
     */
    fun mediaButtons(stopLabel: String): List<CommandButton> = listOf(
        CommandButton
            .Builder(CommandButton.ICON_STOP)
            .setPlayerCommand(Player.COMMAND_STOP)
            .setDisplayName(stopLabel)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
    )
}

/** Session commands shared by the service and the UI's controller. */
object SeionCommands {
    const val TOGGLE_SHUFFLE = "org.seion.TOGGLE_SHUFFLE"
    const val CYCLE_REPEAT = "org.seion.CYCLE_REPEAT"

    /** Sets the shuffle order (int array [KEY_ORDER] of playlist indices): queue reorder while shuffled. */
    const val SET_ORDER = "org.seion.SET_ORDER"

    /**
     * UI -> service on every (re)connect with all-files access: restore the saved queue if the player is empty. Answered
     * once the restore (this one or the one started when the service was created) has finished, with the queue's item
     * count in [KEY_COUNT].
     */
    const val RESTORE_IF_EMPTY = "org.seion.RESTORE_IF_EMPTY"
    const val KEY_COUNT = "count"
    const val KEY_ORDER = "order"

    /**
     * UI -> service: the queue's Undo of a removal. Inserts the item ([KEY_ITEM], a MediaItem bundle) at playlist index
     * [KEY_INDEX] and, while shuffled, sets the play order [KEY_ORDER] in the same step: a separate SET_ORDER could reach
     * the service before the insertion (it is applied asynchronously) and be refused for its size.
     */
    const val RESTORE_ITEM = "org.seion.RESTORE_ITEM"
    const val KEY_INDEX = "index"
    const val KEY_ITEM = "item"

    /**
     * UI -> service: the queue's ×, removing playlist index [KEY_INDEX] (moving on first when it is the current item). A
     * session command like [RESTORE_ITEM], so an Undo right after it is applied after it: the session runs custom
     * commands in the order they arrive, but queues player commands (removeMediaItem) separately.
     */
    const val REMOVE_AT = "org.seion.REMOVE_AT"

    /**
     * UI -> service: the sleep timer ([SleepTimer]). [KEY_MINUTES] = a countdown in minutes, [SleepTimer.OFF] (0) turns
     * it off, [SleepTimer.END_OF_SONG] (−1) pauses at the end of the current song. The state comes back in the session
     * extras ([KEY_SLEEP_DEADLINE], [KEY_SLEEP_END_OF_SONG]).
     */
    const val SET_SLEEP_TIMER = "org.seion.SET_SLEEP_TIMER"

    /** UI -> service: adds [KEY_MINUTES] to a running countdown. */
    const val EXTEND_SLEEP_TIMER = "org.seion.EXTEND_SLEEP_TIMER"
    const val KEY_MINUTES = "minutes"

    /** Session extras: when the countdown pauses (elapsedRealtime ms, −1 = none) and the end-of-song mode. */
    const val KEY_SLEEP_DEADLINE = "org.seion.sleep_deadline"
    const val KEY_SLEEP_END_OF_SONG = "org.seion.sleep_end_of_song"

    /** Service -> controllers: the current item has no decodable audio track on this device ([KEY_TITLE]). */
    const val EVENT_UNSUPPORTED = "org.seion.EVENT_UNSUPPORTED"
    const val KEY_TITLE = "title"

    /** Service -> controllers: an unplayable file was skipped ([KEY_TITLE]); [KEY_STOPPED] = the queue stopped there. */
    const val EVENT_SKIPPED = "org.seion.EVENT_SKIPPED"
    const val KEY_STOPPED = "stopped"

    /**
     * Connection hint of the debug build's browse probe (`BrowseProbe`, adb-driven): it runs inside Seion's process, so
     * without the hint it would count as Seion's own UI and hold the service; with it, it is treated like any external
     * browser (a car, SystemUI).
     */
    const val HINT_EXTERNAL_PROBE = "org.seion.EXTERNAL_PROBE"

    val all = listOf(
        TOGGLE_SHUFFLE,
        CYCLE_REPEAT,
        SET_ORDER,
        RESTORE_IF_EMPTY,
        RESTORE_ITEM,
        REMOVE_AT,
        SET_SLEEP_TIMER,
        EXTEND_SLEEP_TIMER
    ).map {
        SessionCommand(it, Bundle.EMPTY)
    }
}

/**
 * Media3 playback: ExoPlayer in a MediaLibraryService. Background playback with the media notification, lock
 * screen, Bluetooth/headset buttons, audio focus, pause on becoming noisy, gapless transitions. Shuffle and repeat
 * live on the player (so the notification and remote controllers see them) and persist in DataStore; the queue
 * persists in [QueueStore]. The library session also serves a browse tree ([BrowseTree]: Android Auto, AVRCP
 * browsing, external search, the system's "recent" root for the resumption card).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val bitmapLoader by lazy { SeionBitmapLoader(container.art) { NoCoverArt.bitmap(this, 512) } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gapless = AtomicBoolean(true)
    private val container get() = (application as SeionApp).container
    private var restoreJob: Job? = null
    private var artworkVersion = 0

    // The panel's "Stop" label follows Seion's language.
    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleContext.wrap(base))

    override fun onCreate() {
        super.onCreate()
        val initial = runBlocking { container.settings.current() }
        gapless.set(initial.gapless)
        // The DSP switches reach the audio thread through the control snapshot, before the player starts.
        container.dsp.control.update {
            it.copy(
                crystalX = initial.crystalX,
                renovaPure = initial.renovaPure,
                replayGain = initial.replayGainMode,
                preampDb = initial.replayGainPreampDb.toFloat()
            )
        }
        player =
            ExoPlayer
                .Builder(this, SeionRenderersFactory(this, container.dsp))
                .setMediaSourceFactory(DefaultMediaSourceFactory(this, seionExtractors { gapless.get() }))
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                ).setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
        player.repeatMode = initial.repeat.media3
        player.shuffleModeEnabled = initial.shuffle
        player.addListener(listener)
        // Debug builds log decoder choice, format and errors (the emulator verification reads them).
        if (BuildConfig.DEBUG) player.addAnalyticsListener(EventLogger("SeionExo"))
        // Paused/stopped with nothing playing: no notification (Stop dismisses it; the queue stays for resuming).
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        // The media notification's small icon: the Slots silhouette, flat white.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )
        // Paused = dismissible notification: leave the foreground 2 s after playback stops instead of
        // Media3's 10-minute default. Resuming from the panel or a media button still restarts the foreground service.
        setForegroundServiceTimeoutMs(PAUSED_FOREGROUND_TIMEOUT_MS)

        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_NOW_PLAYING)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        session =
            MediaLibrarySession
                .Builder(
                    this,
                    ShuffleAwarePlayer(player, ::seekInPlace) { if (player.shuffleModeEnabled) applyCurrentFirstShuffle() },
                    callback
                ).setId("seion")
                .setSessionActivity(open)
                .setBitmapLoader(bitmapLoader)
                .setMediaButtonPreferences(buttons())
                .build()

        scope.launch {
            container.settings.settings
                .map { it.gapless }
                .distinctUntilChanged()
                .collect { gapless.set(it) }
        }
        scope.launch {
            container.settings.settings
                .map { SoundSwitches(it.crystalX, it.renovaPure, it.replayGainMode, it.replayGainPreampDb.toFloat()) }
                .distinctUntilChanged()
                .collect { sw ->
                    container.dsp.control.update {
                        it.copy(crystalX = sw.crystalX, renovaPure = sw.renovaPure, replayGain = sw.replayGain, preampDb = sw.preampDb)
                    }
                }
        }
        // A cover found online for a queued track: swap in the same item with a re-versioned artwork URI, so the panel, the
        // lock screen and Bluetooth reload it (a metadata-only replace never interrupts playback).
        scope.launch {
            container.art.onlineHits.collect { key ->
                artworkVersion++
                for (i in 0 until player.mediaItemCount) {
                    val refreshed = ArtworkRefresh.refreshed(player.getMediaItemAt(i), key, artworkVersion) ?: continue
                    player.replaceMediaItem(i, refreshed)
                }
            }
        }
        // A cover chosen or removed (album ⋮): the queued tracks of that album reload their artwork the same way.
        scope.launch {
            container.chosenCovers.collect { keys ->
                artworkVersion++
                for (i in 0 until player.mediaItemCount) {
                    val refreshed = ArtworkRefresh.refreshedForChosen(player.getMediaItemAt(i), keys, artworkVersion) ?: continue
                    player.replaceMediaItem(i, refreshed)
                }
            }
        }
        // A queued track identified online: its new title/artist/album, and the cover names its artwork URI carries.
        scope.launch {
            container.identified.collect { t ->
                for (i in 0 until player.mediaItemCount) {
                    val updated = ArtworkRefresh.identified(player.getMediaItemAt(i), t) ?: continue
                    player.replaceMediaItem(i, updated)
                }
            }
        }
        restoreQueue()
        routes = org.seion.playback.dsp.RouteMonitor(this, container.dsp.control).also { it.start() }
        live = java.lang.ref.WeakReference(this)
    }

    private var routes: org.seion.playback.dsp.RouteMonitor? = null

    // A service that is closing (session released, stopSelf() sent) accepts no new controller: a UI that binds in that
    // window is refused and reconnects to the next instance (PlayerConnection retries).
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = if (shuttingDown) null else session

    /**
     * The DSP measurement hook: `adb shell dumpsys activity service
     * io.github.de1987.seion/org.seion.playback.PlaybackService` prints the engine load and block times; `… reset` zeroes them.
     */
    override fun dump(fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        if (args?.contains("reset") == true) {
            container.dsp.stats.requestReset()
            writer.println("Seion DSP counters reset")
            return
        }
        // Debug builds: the raw output tap (channel 0, oldest first), e.g. around the last gapless boundary.
        if (args?.contains("tap") == true && container.dsp.status.tapEnabled) {
            writer.println("tapraw ${container.dsp.status.sampleRate} " + container.dsp.status.tapSnapshot().joinToString(" "))
            container.dsp.status.releaseTap()
            return
        }
        org.seion.playback.dsp.DspReport
            .lines(container.dsp.stats, container.dsp.status, container.dsp.control.params)
            .forEach(writer::println)
    }

    /** Swiped from Recents: keeps playing while engaged (playing, buffering, a call's transient focus loss), else ends. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (ServiceLifecycle.stopOnTaskRemoved(player.playWhenReady, player.playbackState, player.mediaItemCount)) {
            shutDown("task removed")
        }
    }

    override fun onDestroy() {
        if (live?.get() === this) live = null
        idle.cancel()
        sleepJob?.cancel()
        // The last save, synchronous: the service scope is cancelled on the next line (a background write would never run).
        if (!shuttingDown) persistNow("destroy")
        routes?.stop()
        scope.cancel()
        session.release() // idempotent: shutDown() may have released it already
        player.release() // renderers -> SeionAudioSink -> DefaultAudioSink (AudioTrack); the wake lock goes with it
        bitmapLoader.close()
        super.onDestroy()
    }

    // --- Ending the service when nothing needs it ---

    private var shuttingDown = false

    /**
     * A controller of Seion's own code: same app (the uid comes from Binder and cannot be claimed), not Media3's
     * notification controller (it relays the system's controllers: the lock screen, Bluetooth, Android Auto). Only these
     * get Seion's own session commands ([SeionSession.availableSessionCommands]). The debug build's browse probe is
     * one of them (it runs in Seion's process); the hint only changes how it counts for [isUi].
     */
    private fun isAppController(controller: MediaSession.ControllerInfo) = controller.uid == Process.myUid() &&
        controller.packageName == packageName &&
        !session.isMediaNotificationController(controller)

    /**
     * Seion's own UI controller (PlayerConnection); Media3's notification controller, system controllers and browsers
     * (SystemUI, a car, the debug probe) don't count.
     */
    private fun isUi(controller: MediaSession.ControllerInfo) = isAppController(controller) &&
        !controller.connectionHints.getBoolean(SeionCommands.HINT_EXTERNAL_PROBE, false)

    private fun uiConnected() = session.connectedControllers.any(::isUi)

    /**
     * External browsers still connected that asked for the library root (a car, the Bluetooth stack, the debug probe).
     * They hold the session like Seion's UI: a browser keeps the service bound, so ending the session would only break
     * its browsing without freeing anything; the paused countdown starts when the last one disconnects. SystemUI's
     * "recent" request is answered by Media3 itself and never lands here.
     */
    private val browsers = HashSet<MediaSession.ControllerInfo>()

    private fun idleGrace() = ServiceLifecycle.idleGraceMs(
        player.playWhenReady,
        player.playbackState,
        player.mediaItemCount,
        uiConnected() || browsers.isNotEmpty()
    )

    /**
     * (Re)starts or cancels the idle timer. Called from player events and UI connect/disconnect, never directly from
     * onCreate (the restore's own events arm it there: a restarted, paused service ends after the paused grace).
     */
    private fun reevaluateIdle() {
        if (!shuttingDown) idle.update(idleGrace())
    }

    private val idle by lazy {
        IdleCountdown(scope, ::idleGrace, AlarmIdleAlarm(this)) { grace ->
            shutDown(if (grace == ServiceLifecycle.STOPPED_GRACE_MS) "stopped, UI gone" else "paused, UI gone")
        }
    }

    // --- The sleep timer ---

    private val sleep = SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepJob: Job? = null

    /** Applies the timer to the player (pause at the end of the item, the fade's volume) and tells the controllers. */
    private fun applySleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        player.pauseAtEndOfMediaItems = sleep.endOfItem
        player.volume = sleep.volume()
        publishSleepTimer()
        if (sleep.deadline == SleepTimer.NONE) return
        sleepJob =
            scope.launch {
                while (true) {
                    if (sleep.expired()) {
                        Log.i(TAG, "sleep timer: pausing")
                        player.pause()
                        sleep.cancel()
                        player.volume = 1f
                        publishSleepTimer()
                        return@launch
                    }
                    player.volume = sleep.volume()
                    delay(sleep.nextCheckMs())
                }
            }
    }

    private fun publishSleepTimer() {
        session.setSessionExtras(
            Bundle().apply {
                putLong(SeionCommands.KEY_SLEEP_DEADLINE, sleep.deadline)
                putBoolean(SeionCommands.KEY_SLEEP_END_OF_SONG, sleep.endOfItem)
            }
        )
    }

    /**
     * Ends the service: the queue is written synchronously, then the session is released (the platform drops the card
     * at once and a later media key goes to the receiver, a clean cold start, instead of to this dying instance), then
     * stopSelf(). The timer only runs with no UI and no browser connected (a swipe has already stopped the UI).
     */
    private fun shutDown(reason: String) {
        if (shuttingDown) return
        shuttingDown = true
        idle.cancel()
        persistNow(reason)
        session.release()
        stopSelf()
    }

    private fun persistNow(reason: String) {
        val queue = snapshot()
        val ms = queue?.let(persister::saveNow)
        val saved = when {
            queue == null -> "not saved (empty or restoring)"
            ms == null -> "NOT saved (write failed)"
            else -> "saved in $ms ms"
        }
        Log.i(TAG, "stopping: $reason; queue $saved")
    }

    private val listener =
        object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) applyCurrentFirstShuffle()
                persist { it.setShuffle(shuffleModeEnabled) }
                Log.i(TAG, "shuffle=$shuffleModeEnabled")
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                persist { it.setRepeat(RepeatSetting.fromMedia3(repeatMode)) }
                Log.i(TAG, "repeat=${RepeatSetting.fromMedia3(repeatMode)}")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.i(
                    TAG,
                    "transition reason=$reason index=${player.currentMediaItemIndex} id=${mediaItem?.mediaId?.substringAfterLast('/')}"
                )
                // Only the current track moved (a queue change saves the paths through onTimelineChanged).
                savePosition()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // Songs added, removed or moved: the paths are written (once the edits settle), not every few seconds.
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) scheduleQueueSave()
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        Player.EVENT_TIMELINE_CHANGED
                    )
                ) {
                    reevaluateIdle()
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // A seek while paused: nothing else saves it before the service may end, and someone is still there,
                // so the paused card gets its full 60 s again.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !player.isPlaying) {
                    savePosition()
                    if (!shuttingDown) idle.restartOnUserSeek(idleGrace())
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // The sleep timer's "end of the current song": the player paused there; the timer is done.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleep.endOfItem) {
                    Log.i(TAG, "sleep timer: paused at the end of the song")
                    sleep.cancel()
                    applySleepTimer()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.i(TAG, "isPlaying=$isPlaying")
                if (isPlaying) consecutiveErrors = 0
                // Stop: written at once, a swipe from Recents may kill the process right after.
                if (!isPlaying) {
                    if (player.playbackState == Player.STATE_IDLE) snapshot()?.let(persister::saveNow) else saveQueue()
                }
                // While playing, the position (not the paths) is saved every few seconds too (a kill restores close to
                // where it was).
                positionSaver?.cancel()
                if (isPlaying) {
                    positionSaver =
                        scope.launch {
                            while (true) {
                                kotlinx.coroutines.delay(POSITION_SAVE_MS)
                                savePosition()
                            }
                        }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // A file whose audio no decoder on this device supports (e.g. ALAC without an ALAC decoder) would
                // "play" silently with the clock running: treat it like an error, tell the UI and skip it.
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isNotEmpty() && audio.none { it.isSupported }) {
                    val mime = audio.first().getTrackFormat(0).sampleMimeType
                    val title = player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
                    Log.w(TAG, "unsupported audio format $mime on ${player.currentMediaItem?.mediaId?.substringAfterLast('/')}")
                    session.broadcastCustomCommand(
                        SessionCommand(SeionCommands.EVENT_UNSUPPORTED, Bundle.EMPTY),
                        Bundle().apply { putString(SeionCommands.KEY_TITLE, title) }
                    )
                    skipAfterError()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val failing = failingWindow(error)
                Log.e(
                    TAG,
                    "player error ${error.errorCodeName} on ${player.currentMediaItem?.mediaId?.substringAfterLast('/')} " +
                        "current=${player.currentMediaItemIndex} hasNext=${player.hasNextMediaItem()} " +
                        "consecutive=$consecutiveErrors failingWindow=$failing size=${player.mediaItemCount} state=${player.playbackState}"
                )
                // The message names the item that failed, which can be the next one while the previous is current.
                val item = failing?.takeIf { it < player.mediaItemCount }?.let(player::getMediaItemAt) ?: player.currentMediaItem
                val title = item?.mediaMetadata?.title?.toString().orEmpty()
                val stopped = !skipAfterError(failing)
                // Tell the UI explicitly: the controller may never observe the transient error state.
                session.broadcastCustomCommand(
                    SessionCommand(SeionCommands.EVENT_SKIPPED, Bundle.EMPTY),
                    Bundle().apply {
                        putString(SeionCommands.KEY_TITLE, title)
                        putBoolean(SeionCommands.KEY_STOPPED, stopped)
                    }
                )
            }
        }

    /** The playlist index of the period that failed (ExoPlaybackException.mediaPeriodId), or null when unknown. */
    private fun failingWindow(error: PlaybackException): Int? {
        val uid = (error as? ExoPlaybackException)?.mediaPeriodId?.periodUid ?: return null
        val timeline = player.currentTimeline
        val period = timeline.getIndexOfPeriod(uid).takeIf { it >= 0 } ?: return null
        return timeline.getPeriod(period, Timeline.Period()).windowIndex
    }

    private var consecutiveErrors = 0
    private var positionSaver: kotlinx.coroutines.Job? = null
    private var queueSaver: kotlinx.coroutines.Job? = null

    /**
     * An unplayable file (corrupt, truncated, 0 bytes, wrong extension, deleted meanwhile) is skipped: the UI shows
     * a non-blocking message from the controller's onPlayerError. The skip stops once every queue item failed in a
     * row, so a queue of broken files can never loop.
     */
    /** Returns true when it skipped to the next item, false when the queue stopped. */
    private fun skipAfterError(failing: Int? = null): Boolean {
        consecutiveErrors++
        val timeline = player.currentTimeline
        // Navigation semantics of hasNextMediaItem(): repeat-one counts as off, repeat-all wraps.
        val repeat = if (player.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        fun nextOf(index: Int): Int? = if (index !in 0 until timeline.windowCount) {
            null
        } else {
            timeline.getNextWindowIndex(index, repeat, player.shuffleModeEnabled).takeIf { it != C.INDEX_UNSET }
        }
        val current = player.currentMediaItemIndex
        val step = QueueLogic.afterError(
            failing,
            current,
            failing?.let(::nextOf),
            nextOf(current),
            consecutiveErrors,
            player.mediaItemCount
        )
        return if (step.action == QueueLogic.ErrorAction.SKIP && step.target != null) {
            player.seekTo(step.target, C.TIME_UNSET)
            player.prepare()
            player.play()
            true
        } else {
            Log.w(TAG, "playback stopped after $consecutiveErrors consecutive errors")
            player.pause()
            consecutiveErrors = 0
            false
        }
    }

    private fun applyCurrentFirstShuffle() {
        val count = player.mediaItemCount
        if (count == 0) return
        val order = QueueLogic.currentFirstOrder(count, player.currentMediaItemIndex)
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
        Log.d(TAG, "shuffle order (current first) ${order.joinToString()}")
    }

    /** The system media panel's buttons ([SeionSession.mediaButtons]), labelled in Seion's language. */
    private fun buttons(): List<CommandButton> = SeionSession.mediaButtons(getString(R.string.stop))

    private val callback =
        object : MediaLibrarySession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                Log.d(TAG, "controller connected: ${controller.packageName}")
                // A browser loads the covers of browse items itself (content:// URIs of BrowseArtProvider).
                BrowseArtProvider.grantTo(this@PlaybackService, controller.packageName)
                // Seion's own commands (queue edits, sleep timer) only for its own UI; Media3 refuses a custom command
                // that is not in a controller's available commands.
                return MediaSession.ConnectionResult
                    .AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(SeionSession.availableSessionCommands(appUi = isAppController(controller)))
                    .setAvailablePlayerCommands(SeionSession.availablePlayerCommands())
                    .setMediaButtonPreferences(buttons())
                    .build()
            }

            // After the controller is in connectedControllers (onConnect runs before it is added).
            override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
                if (isUi(controller)) reevaluateIdle()
            }

            override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
                rootLimits.remove(controller)
                if (browsers.remove(controller)) {
                    Log.i(TAG, "browser disconnected: ${controller.packageName} (${browsers.size} left)")
                    reevaluateIdle()
                }
                if (isUi(controller)) reevaluateIdle()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                // Belt and braces: Media3 already refuses commands a controller was not granted in onConnect.
                if (SeionSession.isAppCommand(customCommand.customAction) && !isAppController(controller)) {
                    Log.w(TAG, "custom command ${customCommand.customAction} refused for ${controller.packageName}")
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                }
                when (customCommand.customAction) {
                    SeionCommands.TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                    // A queue that couldn't be restored at start (no all-files access yet) is restored once access is
                    // back and the UI reconnects.
                    SeionCommands.RESTORE_IF_EMPTY -> return scope.future {
                        if (player.mediaItemCount == 0 && restoreJob?.isActive != true) restoreQueue()
                        restoreJob?.join()
                        SessionResult(
                            SessionResult.RESULT_SUCCESS,
                            Bundle().apply {
                                putInt(SeionCommands.KEY_COUNT, player.mediaItemCount)
                            }
                        )
                    }
                    SeionCommands.CYCLE_REPEAT -> player.repeatMode = RepeatSetting.fromMedia3(player.repeatMode).next().media3
                    SeionCommands.SET_ORDER -> {
                        val order = args.getIntArray(SeionCommands.KEY_ORDER)
                        if (order == null || !QueueLogic.isOrder(order, player.mediaItemCount)) {
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        }
                        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
                    }
                    SeionCommands.REMOVE_AT -> {
                        val index = args.getInt(SeionCommands.KEY_INDEX, -1)
                        // [KEY_COUNT]: the size the UI saw; a second × on the same row before the first was applied
                        // would otherwise remove the row that moved into its place.
                        if (index !in 0 until player.mediaItemCount || args.getInt(SeionCommands.KEY_COUNT, -1) != player.mediaItemCount) {
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        }
                        // Removing the playing item: move on first. With repeat-one, ExoPlayer would otherwise resolve the
                        // "next" item as the removed one itself and end the queue at item 0.
                        if (QueueLogic.skipBeforeRemoving(
                                index,
                                player.currentMediaItemIndex,
                                player.hasNextMediaItem()
                            )
                        ) {
                            player.seekToNextMediaItem()
                        }
                        player.removeMediaItem(index)
                    }
                    SeionCommands.RESTORE_ITEM -> {
                        val index = args.getInt(SeionCommands.KEY_INDEX, -1)
                        val bundle = args.getBundle(SeionCommands.KEY_ITEM)
                        if (bundle == null ||
                            !QueueLogic.canRestore(player.mediaItemCount, args.getInt(SeionCommands.KEY_COUNT, -1), index)
                        ) {
                            Log.i(TAG, "queue undo dropped: the queue changed")
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        }
                        player.addMediaItem(index, MediaItems.withUri(MediaItem.fromBundle(bundle)))
                        val order = args.getIntArray(SeionCommands.KEY_ORDER)
                        if (order != null && player.shuffleModeEnabled && QueueLogic.isOrder(order, player.mediaItemCount)) {
                            player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
                        }
                        Log.i(TAG, "queue undo: restored item $index of ${player.mediaItemCount}")
                    }
                    SeionCommands.SET_SLEEP_TIMER -> {
                        val minutes = args.getInt(SeionCommands.KEY_MINUTES, Int.MIN_VALUE)
                        if (!SleepTimer.validSet(minutes)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        when (minutes) {
                            SleepTimer.OFF -> sleep.cancel()
                            SleepTimer.END_OF_SONG -> sleep.startEndOfItem()
                            else -> sleep.start(minutes)
                        }
                        Log.i(
                            TAG,
                            "sleep timer: " + if (minutes == SleepTimer.OFF) {
                                "off"
                            } else if (minutes < 0) {
                                "end of song"
                            } else {
                                "$minutes min"
                            }
                        )
                        applySleepTimer()
                    }
                    SeionCommands.EXTEND_SLEEP_TIMER -> {
                        if (!sleep.extend(args.getInt(SeionCommands.KEY_MINUTES, 0))) {
                            return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                        }
                        Log.i(TAG, "sleep timer: extended, ${sleep.remainingMs() / 1000} s left")
                        applySleepTimer()
                    }
                    else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                // Seion's UI sends queue items (path ids); a browser may add browse songs, resolved to their queue items.
                if (mediaItems.none { BrowseIds.isBrowseId(it.mediaId) }) {
                    return Futures.immediateFuture(mediaItems.map(MediaItems::withUri).toMutableList())
                }
                return scope.future {
                    mediaItems
                        .mapNotNull { if (BrowseIds.isBrowseId(it.mediaId)) browse.single(it.mediaId) else MediaItems.withUri(it) }
                        .ifEmpty { throw IllegalArgumentException("nothing playable") }
                        .toMutableList()
                }
            }

            /**
             * A browser's tap (Media3 setMediaItem, a car's playFromMediaId) or a voice request (playFromSearch): one browse
             * id, or an empty id with a search query, becomes its list from that song (BrowseTree.play). Everything else
             * (Seion's UI: whole lists of queue items) goes the default way, through onAddMediaItems.
             */
            override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val single = mediaItems.singleOrNull()
                val request = single != null &&
                    (BrowseIds.isBrowseId(single.mediaId) || (single.mediaId.isEmpty() && single.requestMetadata.searchQuery != null))
                if (single == null || !request) {
                    return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
                }
                return scope.future {
                    restoreJob?.join()
                    val play = browse.play(single.mediaId, single.requestMetadata.searchQuery)
                        ?: throw IllegalArgumentException("nothing to play for ${single.mediaId}")
                    Log.i(TAG, "browse play ${single.mediaId.substringBefore('|')}: ${play.items.size} items from ${play.start}")
                    resumeInPlace = play.sameQueue
                    MediaSession.MediaItemsWithStartPosition(play.items, play.start, play.positionMs)
                }
            }

            // --- The browse tree ---

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                // SystemUI's "recent" request never gets here: Media3 answers it with onPlaybackResumption (the card).
                params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 0)?.takeIf {
                    it > 0
                }?.let { rootLimits[browser] = it }
                if (!isUi(browser) && browsers.add(browser)) {
                    Log.i(TAG, "browser connected: ${browser.packageName} (${browsers.size})")
                    reevaluateIdle()
                }
                val root = if (params?.isRecent == true) browseRecentRoot() else browse.rootItem()
                return Futures.immediateFuture(LibraryResult.ofItem(root, params))
            }

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
                restoreJob?.join()
                val items = browse.children(parentId, page, pageSize, rootLimits[browser] ?: Int.MAX_VALUE)
                if (items == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItemList(items, params)
            }

            override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String
            ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
                restoreJob?.join()
                browse.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }

            override fun onSearch(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<Void>> = scope.future {
                val count = browse.search(query).size
                session.notifySearchResultChanged(browser, query, count, params)
                LibraryResult.ofVoid()
            }

            override fun onGetSearchResult(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
                val items = browse.children(BrowseIds.node(BrowseIds.Node.Search(query)), page, pageSize).orEmpty()
                LibraryResult.ofItemList(items, params)
            }

            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                isForPlayback: Boolean
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
                // The app restore's rule (missing files dropped, the same track kept), read off the main thread: this is
                // the receiver's cold start, where the main thread is also building the player.
                val plan =
                    withContext(Dispatchers.IO) {
                        val saved = container.queueStore.load() ?: return@withContext null
                        QueueRestore.plan(saved) { File(it).isFile }
                    } ?: throw IllegalStateException("nothing to resume")
                val items = loadItems(plan.paths)
                val index = plan.index.coerceIn(0, items.lastIndex)
                if (isForPlayback) {
                    MediaSession.MediaItemsWithStartPosition(items, index, plan.positionMs)
                } else {
                    // Only for SystemUI's resumption card (the "recent" root, e.g. after a restart): it shows this item and
                    // loads its cover itself, so it gets an opaque id and a content:// cover, never the file path. Its
                    // Play goes through onPlaybackResumption(isForPlayback = true).
                    MediaSession.MediaItemsWithStartPosition(listOf(resumptionCardItem(items[index], index)), 0, plan.positionMs)
                }
            }
        }

    /** The resumption card's item: the queue item's names, an opaque id and a cover SystemUI can read. */
    private suspend fun resumptionCardItem(item: MediaItem, index: Int): MediaItem {
        val trackId = withContext(Dispatchers.IO) { container.db.tracks().idForPath(item.mediaId) }
        val meta =
            item.mediaMetadata
                .buildUpon()
                .setArtworkUri(trackId?.let(BrowseArtProvider::uri))
                .setExtras(null)
                .build()
        return MediaItem
            .Builder()
            .setMediaId(BrowseIds.song(BrowseIds.Node.Recent, BrowseIds.queueKey(index, item.mediaId)))
            .setMediaMetadata(meta)
            .build()
    }

    // --- Browse tree ---

    private val browse by lazy {
        BrowseTree(LibraryBrowseSource(container, ::queueSnapshot), browseStrings, BrowseArtProvider::uri)
    }

    private val browseStrings =
        object : BrowseStrings {
            override fun title(node: BrowseIds.Node): String = getString(
                when (node) {
                    BrowseIds.Node.Recent -> R.string.play_queue
                    BrowseIds.Node.Albums -> R.string.albums
                    BrowseIds.Node.Artists -> R.string.artists
                    BrowseIds.Node.Playlists -> R.string.playlists
                    BrowseIds.Node.Songs -> R.string.songs
                    else -> R.string.app_name
                }
            )

            override fun unknownArtist(): String = getString(R.string.unknown_artist)

            override fun songs(count: Int): String = resources.getQuantityString(R.plurals.n_songs, count, count)
        }

    /** The "children limit" a car sent with its root request (Android Auto shows at most 4 tabs), per browser. */
    private val rootLimits = HashMap<MediaSession.ControllerInfo, Int>()

    /** A browser's own "recent" request (not SystemUI's, which Media3 answers): the play queue node. */
    private fun browseRecentRoot(): MediaItem = MediaItem
        .Builder()
        .setMediaId(BrowseIds.RECENT)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata
                .Builder()
                .setTitle(getString(R.string.play_queue))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        ).build()

    /** The live queue (main thread, after the restore) for the browse tree. */
    private suspend fun queueSnapshot(): BrowseQueue = withContext(Dispatchers.Main.immediate) {
        restoreJob?.join()
        val n = player.mediaItemCount
        if (n == 0) return@withContext BrowseQueue.EMPTY
        val timeline = player.currentTimeline
        val shuffle = player.shuffleModeEnabled
        val order = ArrayList<Int>(n)
        var i = timeline.getFirstWindowIndex(shuffle)
        while (i != C.INDEX_UNSET && order.size < n) {
            order += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        BrowseQueue((0 until n).map(player::getMediaItemAt), order, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0))
    }

    /**
     * Set by onSetMediaItems for a play of the queue itself (the play queue node, an empty voice request): the session's
     * following setMediaItems of the same items becomes a seek, so the shuffle order (an Edit order under shuffle
     * included) stays. Consumed by the next setMediaItems.
     */
    private var resumeInPlace = false

    private fun seekInPlace(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): Boolean {
        if (!resumeInPlace) return false
        resumeInPlace = false
        if (items.size != player.mediaItemCount ||
            items.indices.any { items[it].mediaId != player.getMediaItemAt(it).mediaId }
        ) {
            return false
        }
        player.seekTo(startIndex, startPositionMs)
        Log.i(TAG, "browse play: the same queue, moved to item $startIndex")
        return true
    }

    private fun restoreQueue() {
        // Without all-files access nothing is readable: keep the saved queue for when access is back.
        if (!android.os.Environment.isExternalStorageManager()) {
            Log.w(TAG, "queue restore skipped: no full file access")
            return
        }
        restoreJob = scope.launch {
            var savedPaths: List<String>? = null
            val plan =
                withContext(Dispatchers.IO) {
                    val saved = container.queueStore.load() ?: return@withContext null
                    savedPaths = saved.paths
                    QueueRestore.plan(saved) { File(it).isFile }
                }
            if (plan != null && player.mediaItemCount == 0) {
                val items = loadItems(plan.paths)
                // Every saved file still there: queue.json already holds these paths (no rewrite after the restore).
                if (plan.paths == savedPaths && items.size == plan.paths.size) persister.restored(plan.paths)
                player.setMediaItems(items, plan.index.coerceIn(0, items.lastIndex), plan.positionMs)
                player.prepare()
                if (player.shuffleModeEnabled) applyCurrentFirstShuffle()
                Log.i(TAG, "queue restored: ${items.size} items, index ${plan.index} at ${plan.positionMs} ms")
            }
        }
    }

    private suspend fun loadItems(paths: List<String>): List<MediaItem> = withContext(Dispatchers.IO) {
        val existing = paths.filter { File(it).isFile }
        val byPath = existing.chunked(500).flatMap { container.db.tracks().byPaths(it) }.associateBy { it.path }
        existing.map { p -> byPath[p]?.let(MediaItems::fromTrack) ?: MediaItems.fromFile(File(p)) }
    }

    private val persister by lazy { QueuePersister(container.queueStore, scope) }

    /** The queue as it stands (main thread), or null while restoring or empty (never overwrite a saved queue with nothing). */
    private fun snapshot(): SavedQueue? {
        if (restoreJob?.isActive == true || !::player.isInitialized) return null
        val n = player.mediaItemCount
        if (n == 0) return null
        val paths = (0 until n).map { player.getMediaItemAt(it).mediaId }
        return SavedQueue(paths, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0))
    }

    /** The whole queue (paths and position): pause, a queue change. */
    private fun saveQueue() {
        queueSaver?.cancel()
        snapshot()?.let(persister::save)
    }

    /** A queue change: the paths are written once the edits settle (a drag or a list of removals is one write). */
    private fun scheduleQueueSave() {
        queueSaver?.cancel()
        queueSaver =
            scope.launch {
                kotlinx.coroutines.delay(QUEUE_SAVE_SETTLE_MS)
                saveQueue()
            }
    }

    /** Only the current track and position (every few seconds while playing, a transition, a seek while paused). */
    private fun savePosition() {
        if (restoreJob?.isActive == true || !::player.isInitialized) return
        val item = player.currentMediaItem ?: return
        persister.savePosition(SavedPosition(item.mediaId, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0)))
    }

    private fun persist(block: suspend (org.seion.settings.SettingsRepository) -> Unit) {
        scope.launch { block(container.settings) }
    }

    companion object {
        private const val TAG = "SeionPlayback"
        private const val PAUSED_FOREGROUND_TIMEOUT_MS = 2_000L
        private const val POSITION_SAVE_MS = 5_000L
        private const val QUEUE_SAVE_SETTLE_MS = 1_000L

        /** The running service, for [IdleAlarmReceiver] (same process, main thread). */
        @Volatile private var live: java.lang.ref.WeakReference<PlaybackService>? = null

        /** The idle alarm's broadcast: true when the live service's paused countdown was due (it ends shortly). */
        fun onIdleAlarm(): Boolean {
            val service = live?.get() ?: return false
            if (service.shuttingDown) return false
            return service.idle.onAlarm()
        }
    }
}

/** The Sound screen's switches as the DSP control snapshot takes them. */
private data class SoundSwitches(
    val crystalX: Boolean,
    val renovaPure: Boolean,
    val replayGain: org.seion.playback.dsp.ReplayGainMode,
    val preampDb: Float
)

/** The extractors the player reads files with: the gapless setting, and the real duration of a file cut short. */
@OptIn(UnstableApi::class)
internal fun seionExtractors(gapless: () -> Boolean): ExtractorsFactory = EarlyEndExtractors(GaplessAwareExtractors(gapless))

/**
 * Gapless setting: ExoPlayer is gapless by design (no gap between items; encoder delay/padding trimmed from MP4
 * edit lists, the LAME header and iTunes gapless data). With the setting off, the MP4 edit lists and ID3 gapless
 * metadata are ignored, so lossy files play their encoder padding (the classic short gap). FLAC/WAV/ALAC are
 * sample-exact either way. Applies to items prepared after the change.
 */
@OptIn(UnstableApi::class)
private class GaplessAwareExtractors(private val gapless: () -> Boolean) : ExtractorsFactory {
    private val on = DefaultExtractorsFactory()
    private val off =
        DefaultExtractorsFactory()
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA)

    override fun createExtractors(): Array<Extractor> = (if (gapless()) on else off).createExtractors()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        (if (gapless()) on else off).createExtractors(uri, responseHeaders)
}

/**
 * The player the session (and so every controller, the media panel and Bluetooth) talks to. A new list replaces the
 * playlist asynchronously after `onAddMediaItems`, and ExoPlayer then picks a random shuffle order; with shuffle on,
 * [onListReplaced] re-applies "current track first" right after the replacement, in the same call.
 */
@OptIn(UnstableApi::class)
private class ShuffleAwarePlayer(
    player: Player,
    /** A browser's play of the queue the player already has: a seek instead of a replacement (true = handled). */
    private val seekInPlace: (List<MediaItem>, Int, Long) -> Boolean,
    private val onListReplaced: () -> Unit
) : ForwardingPlayer(player) {
    override fun setMediaItems(mediaItems: MutableList<MediaItem>) {
        super.setMediaItems(mediaItems)
        onListReplaced()
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        super.setMediaItems(mediaItems, resetPosition)
        onListReplaced()
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        if (seekInPlace(mediaItems, startIndex, startPositionMs)) return
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
        onListReplaced()
    }
}
