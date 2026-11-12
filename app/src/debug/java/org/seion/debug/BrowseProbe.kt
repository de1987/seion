package org.seion.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.browse.MediaBrowser as PlatformBrowser
import android.media.session.MediaController as PlatformController
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.seion.playback.PlaybackService
import org.seion.playback.SeionCommands

/**
 * Debug builds only: an adb-driven media browser for the browse tree, standing in for a
 * car or another browser. It connects with [SeionCommands.HINT_EXTERNAL_PROBE], so the service treats it like an
 * external browser, not like Seion's UI. Output: logcat tag `SeionProbe`.
 *
 * ```
 * adb shell am broadcast -n io.github.de1987.seion.debug/org.seion.debug.BrowseProbe --es op children --es id songs --ei page 2 --ei size 50
 * ops: root [--ez recent true] [--ei limit 4] | children --es id <id> [--ei page n --ei size n] | item --es id <id>
 *      search --es q <query> | play --es id <id> | art --es uri <content uri>
 *      legacy --es id <id> [--ei limit 4] (platform MediaBrowser: the path cars and SystemUI use)
 *      legacyplay --es id <id> | voice --es q <query> (platform MediaController: playFromMediaId / playFromSearch)
 *      hold [--ei seconds 70] (a platform browser that stays connected, like a car)
 *      sleep --ei minutes <n> (the sleep timer's session command: n minutes, 0 = off, -1 = end of song; also
 *      allows a 1-minute timer, which the dialog doesn't offer) | sleepextend --ei minutes <n>
 * ```
 */
@OptIn(UnstableApi::class)
class BrowseProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        if (intent.getStringExtra("op") == "hold") {
            // A car's long browse session: a platform browser stays connected for --ei seconds (default 70), then leaves.
            pending.finish()
            scope.launch { hold(app, intent.getIntExtra("seconds", HOLD_DEFAULT_S)) }
            return
        }
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            try {
                withTimeout(TIMEOUT_MS) { run(app, intent) }
            } catch (e: Exception) {
                Log.w(TAG, "probe failed: $e")
            } finally {
                Log.i(TAG, "done in ${SystemClock.elapsedRealtime() - t0} ms")
                pending.finish()
            }
        }
    }

    private suspend fun run(context: Context, intent: Intent) {
        val op = intent.getStringExtra("op") ?: "root"
        val id = intent.getStringExtra("id")
        when (op) {
            "legacy" -> return legacy(context, id, intent.getIntExtra("limit", 0))
            "legacyplay" -> return legacyControl(context) { it.transportControls.playFromMediaId(id, Bundle()) }
            "voice" -> return legacyControl(context) { it.transportControls.playFromSearch(intent.getStringExtra("q").orEmpty(), Bundle()) }
            "art" -> return art(context, Uri.parse(intent.getStringExtra("uri")))
        }
        val browser = connect(context)
        try {
            when (op) {
                "root" -> {
                    val extras = Bundle()
                    intent.getIntExtra("limit", 0).takeIf {
                        it > 0
                    }?.let { extras.putInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, it) }
                    val params = LibraryParams.Builder().setRecent(intent.getBooleanExtra("recent", false)).setExtras(extras).build()
                    val root = browser.getLibraryRoot(params).await()
                    Log.i(TAG, "root result=${root.resultCode} ${root.value?.let(::line)}")
                    root.value?.let { r -> log("root children", browser.getChildren(r.mediaId, 0, 100, params).await().value.orEmpty()) }
                }
                "children" -> {
                    val page = intent.getIntExtra("page", 0)
                    val size = intent.getIntExtra("size", 100)
                    val t = SystemClock.elapsedRealtime()
                    val r = browser.getChildren(id.orEmpty(), page, size, null).await()
                    Log.i(TAG, "children of $id page $page size $size: result=${r.resultCode} in ${SystemClock.elapsedRealtime() - t} ms")
                    log("children", r.value.orEmpty())
                }
                "sleep", "sleepextend" -> {
                    val action = if (op == "sleep") SeionCommands.SET_SLEEP_TIMER else SeionCommands.EXTEND_SLEEP_TIMER
                    val args = Bundle().apply { putInt(SeionCommands.KEY_MINUTES, intent.getIntExtra("minutes", 0)) }
                    val r = browser.sendCustomCommand(androidx.media3.session.SessionCommand(action, Bundle.EMPTY), args).await()
                    Log.i(TAG, "$op result=${r.resultCode} extras=${browser.sessionExtras}")
                }
                "item" -> {
                    val r = browser.getItem(id.orEmpty()).await()
                    Log.i(TAG, "item result=${r.resultCode} ${r.value?.let(::line)}")
                }
                "search" -> {
                    val q = intent.getStringExtra("q").orEmpty()
                    val r = browser.search(q, null).await()
                    Log.i(TAG, "search '$q' result=${r.resultCode}")
                    log("search results", browser.getSearchResult(q, 0, 50, null).await().value.orEmpty())
                }
                "play" -> {
                    browser.setMediaItem(MediaItem.Builder().setMediaId(id.orEmpty()).build())
                    browser.prepare()
                    browser.play()
                    delay(PLAY_SETTLE_MS)
                    Log.i(
                        TAG,
                        "after play: items=${browser.mediaItemCount} index=${browser.currentMediaItemIndex} " +
                            "title=${browser.currentMediaItem?.mediaMetadata?.title} playing=${browser.isPlaying} " +
                            "shuffle=${browser.shuffleModeEnabled} repeat=${browser.repeatMode}"
                    )
                }
                else -> Log.w(TAG, "unknown op $op")
            }
        } finally {
            browser.release()
        }
    }

    private suspend fun connect(context: Context): MediaBrowser = MediaBrowser
        .Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
        .setConnectionHints(Bundle().apply { putBoolean(SeionCommands.HINT_EXTERNAL_PROBE, true) })
        .buildAsync()
        .await()

    private fun line(item: MediaItem): String {
        val m = item.mediaMetadata
        val kind = (if (m.isBrowsable == true) "B" else "") + (if (m.isPlayable == true) "P" else "")
        return "${item.mediaId} | ${m.title} | ${m.subtitle ?: m.artist ?: ""} | $kind | art=${m.artworkUri}"
    }

    private fun log(what: String, items: List<MediaItem>) {
        Log.i(TAG, "$what: ${items.size}")
        items.take(LOG_ITEMS).forEach { Log.i(TAG, "  " + line(it)) }
        if (items.size > LOG_ITEMS) Log.i(TAG, "  … last: " + line(items.last()))
    }

    private fun art(context: Context, uri: Uri) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        Log.i(TAG, "art $uri: type=${context.contentResolver.getType(uri)} bytes=${bytes?.size} bitmap=${bmp?.width}x${bmp?.height}")
    }

    /** The platform MediaBrowser (MediaBrowserService protocol): what Android Auto, AVRCP and SystemUI use. */
    private suspend fun legacy(context: Context, id: String?, limit: Int) {
        val connected = CompletableDeferred<Unit>()
        val hints = Bundle().apply {
            putBoolean(SeionCommands.HINT_EXTERNAL_PROBE, true)
            if (limit > 0) putInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, limit)
        }
        lateinit var browser: PlatformBrowser
        browser = PlatformBrowser(
            context,
            ComponentName(context, PlaybackService::class.java),
            object : PlatformBrowser.ConnectionCallback() {
                override fun onConnected() {
                    connected.complete(Unit)
                }

                override fun onConnectionFailed() {
                    connected.completeExceptionally(IllegalStateException("legacy connection failed"))
                }
            },
            hints
        )
        browser.connect()
        try {
            connected.await()
            val extras = browser.extras
            Log.i(TAG, "legacy root=${browser.root} search=${extras?.getBoolean("android.media.browse.SEARCH_SUPPORTED")}")
            val parent = id ?: browser.root
            val loaded = CompletableDeferred<List<PlatformBrowser.MediaItem>>()
            browser.subscribe(
                parent,
                object : PlatformBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<PlatformBrowser.MediaItem>) {
                        loaded.complete(children)
                    }

                    override fun onError(parentId: String) {
                        loaded.completeExceptionally(IllegalStateException("legacy subscribe error for $parentId"))
                    }
                }
            )
            val t = SystemClock.elapsedRealtime()
            val children = loaded.await()
            Log.i(TAG, "legacy children of $parent: ${children.size} in ${SystemClock.elapsedRealtime() - t} ms")
            children.take(LOG_ITEMS).forEach {
                val d = it.description
                Log.i(TAG, "  ${it.mediaId} | ${d.title} | ${d.subtitle} | flags=${it.flags} | icon=${d.iconUri}")
            }
            browser.unsubscribe(parent)
        } finally {
            browser.disconnect()
        }
    }

    private suspend fun hold(context: Context, seconds: Int) {
        val connected = CompletableDeferred<Unit>()
        val browser = PlatformBrowser(
            context,
            ComponentName(context, PlaybackService::class.java),
            object : PlatformBrowser.ConnectionCallback() {
                override fun onConnected() {
                    connected.complete(Unit)
                }

                override fun onConnectionSuspended() {
                    Log.i(TAG, "hold: connection suspended (the session ended)")
                }

                override fun onConnectionFailed() {
                    connected.completeExceptionally(IllegalStateException("hold: connection failed"))
                }
            },
            Bundle().apply { putBoolean(SeionCommands.HINT_EXTERNAL_PROBE, true) }
        )
        browser.connect()
        try {
            withTimeout(TIMEOUT_MS) { connected.await() }
            Log.i(TAG, "hold: connected to ${browser.root}, staying $seconds s")
            delay(seconds * 1_000L)
        } catch (e: Exception) {
            Log.w(TAG, "hold failed: $e")
        } finally {
            browser.disconnect()
            Log.i(TAG, "hold: disconnected")
        }
    }

    private suspend fun legacyControl(context: Context, block: (PlatformController) -> Unit) {
        val connected = CompletableDeferred<Unit>()
        lateinit var browser: PlatformBrowser
        browser = PlatformBrowser(
            context,
            ComponentName(context, PlaybackService::class.java),
            object : PlatformBrowser.ConnectionCallback() {
                override fun onConnected() {
                    connected.complete(Unit)
                }

                override fun onConnectionFailed() {
                    connected.completeExceptionally(IllegalStateException("legacy connection failed"))
                }
            },
            Bundle().apply { putBoolean(SeionCommands.HINT_EXTERNAL_PROBE, true) }
        )
        browser.connect()
        try {
            connected.await()
            val controller = PlatformController(context, browser.sessionToken)
            block(controller)
            delay(PLAY_SETTLE_MS)
            Log.i(
                TAG,
                "legacy control: state=${controller.playbackState?.state} title=${controller.metadata?.description?.title} " +
                    "queue=${controller.queue?.size}"
            )
        } finally {
            browser.disconnect()
        }
    }

    private companion object {
        const val TAG = "SeionProbe"
        const val TIMEOUT_MS = 9_000L
        const val PLAY_SETTLE_MS = 2_000L
        const val LOG_ITEMS = 12
        const val HOLD_DEFAULT_S = 70
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
