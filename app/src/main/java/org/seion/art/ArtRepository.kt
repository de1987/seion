package org.seion.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.core.graphics.scale
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Decoded, downsampled cover bitmaps with an in-memory LRU and in-flight de-duplication. Resolution has two phases
 * [resolveLocal] (embedded art, folder images; never the network) and [resolveOnline] (the online
 * chain, behind its gate, cache, limiter and back-off). [state] shows the local result at once and queues the online
 * lookup, so a cover is never held back by the network. [resolveLocal] and [fileExists] are injected so the miss rule
 * is unit-testable.
 */
class ArtRepository(
    private val resolveLocal: suspend (ArtRef) -> ResolvedArt?,
    private val fileExists: (String) -> Boolean = { File(it).isFile },
    private val resolveOnline: suspend (ArtRef) -> OnlineLookup = { OnlineLookup.Miss },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(resolver: AlbumArtResolver, fileExists: (String) -> Boolean = { File(it).isFile }) : this(
        resolveLocal = { ref -> resolver.resolveLocal(ref) },
        fileExists = fileExists,
        resolveOnline = { ref -> resolver.lookupOnline(ref) }
    )

    private val memory =
        object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }

    /** No local art (embedded/folder) for this key. */
    private val localMisses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * No online art for this track under these names ([onlineKey]: track key + album artist + album). An identification
     * changes the names, so the track is asked again under its new ones; the same names stay a miss. Only an answer
     * ([OnlineLookup.Miss]) is remembered: a lookup that was skipped or failed is asked again ([retries]).
     */
    private val misses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val inFlight = HashMap<String, Deferred<Outcome>>()
    private val lock = Mutex()

    /** Bumps whenever cached art may have changed (settings toggled, cache cleared, rescan): the UI reloads. */
    private val _generation = MutableStateFlow(0)
    val generationFlow: StateFlow<Int> = _generation.asStateFlow()
    val generation: Int get() = _generation.value

    /** Per-track key -> the key of the image it resolved to (identical images share one decoded bitmap). */
    private val sourceOf = ConcurrentHashMap<String, String>()
    private val originOf = ConcurrentHashMap<String, ArtOrigin>()

    /** Tracks (cache keys) whose online lookup found a cover in this generation. */
    private val onlineFound: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val _onlineHits = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /** The cache key of every track whose cover arrived online (the playback service refreshes the session artwork). */
    val onlineHits: SharedFlow<String> = _onlineHits.asSharedFlow()

    /** Observed states, by key; bounded (the UI observes the current track and its neighbours). */
    private val states =
        object : LinkedHashMap<String, Observed>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Observed>) = size > MAX_STATES
        }

    /** [ref] changes when the track's names do (identification): same track key, new online query. */
    private class Observed(@Volatile var ref: ArtRef, val sizePx: Int, val flow: MutableStateFlow<ArtState>) {
        var job: Job? = null
    }

    /**
     * Unanswered online lookups someone is waiting for (an observed state, the session's artwork), by [onlineKey]:
     * asked again after the back-off ([OnlineLookup.Unanswered.retryInMs]) or when the network allows it
     * ([retryUnanswered]). Bounded like [states]; the resolver's gate, limiters and back-off still apply to every retry.
     */
    private class Retry(val ref: ArtRef, val sizePx: Int, val observed: Boolean) {
        var timer: Job? = null
    }

    private val retries =
        object : LinkedHashMap<String, Retry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Retry>): Boolean =
                (size > MAX_STATES).also { if (it) eldest.value.timer?.cancel() }
        }

    /** A local or online phase's result: [unanswered] (online only) when nothing was learnt and it may be asked again. */
    private class Outcome(val bmp: Bitmap?, val unanswered: OnlineLookup.Unanswered? = null)

    fun cached(ref: ArtRef, sizePx: Int): Bitmap? = sourceOf[key(ref, sizePx)]?.let(memory::get)

    /** Local art, then online (waits for the network): lists, the ambient prefetch. */
    suspend fun load(ref: ArtRef, sizePx: Int): Bitmap? = loadLocal(ref, sizePx) ?: loadOnline(ref, sizePx)

    /** Embedded art and folder images only; never the network. */
    suspend fun loadLocal(ref: ArtRef, sizePx: Int): Bitmap? {
        val k = key(ref, sizePx)
        cached(ref, sizePx)?.let { return it }
        if (k in localMisses) return null
        return phase(ref, sizePx, k, online = false)
    }

    /** The online phase alone (after a local miss). */
    suspend fun loadOnline(ref: ArtRef, sizePx: Int): Bitmap? = onlinePhase(ref, sizePx).bmp

    private suspend fun onlinePhase(ref: ArtRef, sizePx: Int): Outcome {
        cached(ref, sizePx)?.let { return Outcome(it) }
        if (onlineKey(ref, sizePx) in misses) return Outcome(null)
        return phaseOutcome(ref, sizePx, key(ref, sizePx), online = true)
    }

    /** A cover that already came online in this generation (decoded from the disk cache, no request), else null. */
    suspend fun loadIfFoundOnline(ref: ArtRef, sizePx: Int): Bitmap? =
        if (ref.cacheKey in onlineFound) cached(ref, sizePx) ?: loadOnline(ref, sizePx) else null

    /**
     * Queues the online lookup and returns at once; a hit lands in the memory cache, updates [state] and emits. The
     * session's artwork asks this way, so an unanswered lookup is asked again like an observed one.
     */
    fun requestOnline(ref: ArtRef, sizePx: Int) {
        scope.launch { onlinePhase(ref, sizePx).unanswered?.let { retryLater(ref, sizePx, it, observed = false) } }
    }

    /**
     * The network now allows online lookups (connected; unmetered when "Wi-Fi only" is on): every unanswered lookup
     * someone is waiting for is asked again, through the resolver's gate, limiters and back-off as always.
     */
    fun retryUnanswered() {
        val due = synchronized(retries) { retries.keys.toList() }
        due.forEach { retryNow(it, fromTimer = false) }
    }

    private fun retryLater(ref: ArtRef, sizePx: Int, unanswered: OnlineLookup.Unanswered, observed: Boolean) {
        val k = onlineKey(ref, sizePx)
        val r = Retry(ref, sizePx, observed)
        synchronized(retries) {
            retries.remove(k)?.timer?.cancel()
            retries[k] = r
        }
        val delayMs = unanswered.retryInMs ?: return
        r.timer =
            scope.launch {
                delay(delayMs)
                retryNow(k, fromTimer = true)
            }
    }

    private fun retryNow(k: String, fromTimer: Boolean) {
        val r = synchronized(retries) { retries.remove(k) } ?: return
        if (!fromTimer) r.timer?.cancel()
        if (!r.observed) {
            requestOnline(r.ref, r.sizePx)
            return
        }
        // Still observed under the same names and still without a cover: look again (start() re-registers on no answer).
        val o = synchronized(states) { states[key(r.ref, r.sizePx)] }?.takeIf { it.ref == r.ref && it.flow.value !is ArtState.Found }
        o?.let(::start)
    }

    /**
     * The track's art as it resolves: [ArtState.Found] at once when cached, else Loading -> Found (local) or
     * Missing(onlinePending = true) -> Found / Missing(false). A null ref (a track not indexed yet) is Missing.
     */
    fun state(ref: ArtRef?, sizePx: Int): StateFlow<ArtState> {
        if (ref == null) return MutableStateFlow(ArtState.Missing(false))
        val k = key(ref, sizePx)
        synchronized(states) {
            states[k]?.let { o ->
                // The same track under new names (the queue item was rebuilt after an identification): look again.
                if (o.ref != ref) {
                    o.ref = ref
                    if (o.flow.value !is ArtState.Found) start(o)
                }
                return o.flow
            }
            val o = Observed(ref, sizePx, MutableStateFlow(initial(ref, sizePx)))
            states[k] = o
            if (o.flow.value !is ArtState.Found) start(o)
            return o.flow
        }
    }

    private fun initial(ref: ArtRef, sizePx: Int): ArtState {
        val bmp = cached(ref, sizePx) ?: return ArtState.Loading
        return ArtState.Found(bmp, originOf[key(ref, sizePx)] ?: ArtOrigin.EMBEDDED)
    }

    private fun start(o: Observed) {
        o.job?.cancel()
        o.job =
            scope.launch {
                val ref = o.ref
                val k = key(ref, o.sizePx)
                val local = loadLocal(ref, o.sizePx)
                if (local != null) {
                    o.flow.value = ArtState.Found(local, originOf[k] ?: ArtOrigin.EMBEDDED)
                    return@launch
                }
                o.flow.value = ArtState.Missing(onlinePending = true)
                val online = onlinePhase(ref, o.sizePx)
                val bmp = online.bmp
                o.flow.value = if (bmp != null) ArtState.Found(bmp, ArtOrigin.ONLINE) else ArtState.Missing(onlinePending = false)
                // Offline, metered, backing off or failed: not a miss; asked again when that can succeed.
                online.unanswered?.let { retryLater(ref, o.sizePx, it, observed = true) }
            }
    }

    private suspend fun phase(ref: ArtRef, sizePx: Int, k: String, online: Boolean): Bitmap? = phaseOutcome(ref, sizePx, k, online).bmp

    private suspend fun phaseOutcome(ref: ArtRef, sizePx: Int, k: String, online: Boolean): Outcome {
        val flightKey = if (online) "${onlineKey(ref, sizePx)}#online" else k
        val job =
            lock.withLock {
                inFlight[flightKey] ?: scope.async { decode(ref, sizePx, k, online) }.also { inFlight[flightKey] = it }
            }
        val outcome = job.await()
        val bmp = outcome.bmp
        // Only a readable source makes a miss: a track file that was missing for a moment (renamed, card busy) is
        // retried next time instead of showing the placeholder for the rest of the session. Online, only
        // an answer is a miss: a lookup that was skipped (offline, metered, back-off) or failed is not.
        val readable = bmp != null || ref.trackPath == null || fileExists(ref.trackPath)
        lock.withLock {
            inFlight.remove(flightKey)
            if (bmp == null && readable && outcome.unanswered == null) if (online) misses += onlineKey(ref, sizePx) else localMisses += k
        }
        if (bmp != null && online && onlineFound.add(ref.cacheKey)) {
            _onlineHits.tryEmit(ref.cacheKey)
            publishFound(ref.cacheKey, ref)
        }
        return outcome
    }

    /**
     * An online hit for [found]'s names: every observed state of track [cacheKey] resolves again under those names
     * (another size decodes from disk; a state still holding the track's old names takes the new ones).
     */
    private fun publishFound(cacheKey: String, found: ArtRef) {
        val observed = synchronized(states) { states.values.filter { it.ref.cacheKey == cacheKey && it.flow.value !is ArtState.Found } }
        observed.forEach { o ->
            o.ref = o.ref.copy(artist = found.artist, album = found.album)
            cached(o.ref, o.sizePx)?.let { o.flow.value = ArtState.Found(it, ArtOrigin.ONLINE) } ?: start(o)
        }
    }

    /** Whether the track has embedded art or a folder image (never the network). */
    suspend fun hasLocal(ref: ArtRef): Boolean = withContext(io) { resolveLocal(ref) != null }

    /**
     * One online search for a cover nobody is looking at yet (after an identification, "Find missing covers again"):
     * only when [ref] has no local art; a hit is published to every track in [keys] like any online hit.
     */
    suspend fun findOnline(ref: ArtRef, keys: Collection<String> = listOf(ref.cacheKey)): CoverSearch {
        if (hasLocal(ref)) return CoverSearch.LOCAL
        if (withContext(io) { resolveOnline(ref) } !is OnlineLookup.Hit) return CoverSearch.NONE
        for (key in keys) {
            // Whatever this track remembered as missing is outdated now (another size, the names before).
            misses.removeIf { it.startsWith("$key@") }
            if (onlineFound.add(key)) _onlineHits.tryEmit(key)
            publishFound(key, ref)
        }
        return CoverSearch.FOUND
    }

    /** "Find missing covers again": the remembered online misses go (found and local art stay decoded). */
    fun forgetOnlineMisses() = misses.clear()

    /**
     * Memory is short and Seion is in the background (`onTrimMemory`): the decoded covers go; the per-track mappings and
     * the misses stay, so a screen that comes back decodes its covers again from the files and the disk cache, with no
     * new lookup.
     */
    fun trimMemory() = memory.evictAll()

    fun invalidate() {
        memory.evictAll()
        sourceOf.clear()
        originOf.clear()
        misses.clear()
        localMisses.clear()
        onlineFound.clear()
        synchronized(retries) {
            retries.values.forEach { it.timer?.cancel() }
            retries.clear()
        }
        _generation.value++
        // Observed tracks resolve again; the cover on screen stays until its new state arrives.
        synchronized(states) { states.values.toList() }.forEach(::start)
    }

    private suspend fun decode(ref: ArtRef, sizePx: Int, trackKey: String, online: Boolean): Outcome = withContext(io) {
        val art =
            if (online) {
                when (val found = resolveOnline(ref)) {
                    is OnlineLookup.Hit -> found.art
                    OnlineLookup.Miss -> return@withContext Outcome(null)
                    is OnlineLookup.Unanswered -> return@withContext Outcome(null, found)
                }
            } else {
                resolveLocal(ref) ?: return@withContext Outcome(null)
            }
        val source =
            when (art) {
                is ResolvedArt.Bytes -> "emb:${art.bytes.size}:${art.bytes.contentHashCode()}@$sizePx"
                is ResolvedArt.FileImage -> "file:${art.file.absolutePath}@$sizePx"
            }
        sourceOf[trackKey] = source
        originOf[trackKey] = art.origin
        memory.get(source)?.let { return@withContext Outcome(it) }
        val bmp =
            when (art) {
                is ResolvedArt.Bytes -> decodeSampled(sizePx) { opts -> BitmapFactory.decodeByteArray(art.bytes, 0, art.bytes.size, opts) }
                is ResolvedArt.FileImage -> decodeSampled(sizePx) { opts -> BitmapFactory.decodeFile(art.file.absolutePath, opts) }
            }
        if (bmp != null) memory.put(source, bmp) else sourceOf.remove(trackKey)
        Outcome(bmp)
    }

    private fun key(ref: ArtRef, sizePx: Int) = "${ref.cacheKey}@$sizePx"

    private fun onlineKey(ref: ArtRef, sizePx: Int) =
        key(ref, sizePx) + "\u0001" + ref.artist?.trim()?.lowercase().orEmpty() + "\u0001" + ref.album?.trim()?.lowercase().orEmpty()

    companion object {
        private const val MAX_STATES = 32

        /** The front cover when the container says which picture it is ([EmbeddedPictures]), else the retriever's pick. */
        fun embeddedPicture(path: String): ByteArray? {
            val file = File(path)
            if (!file.isFile) return null
            if (EmbeddedPictures.handles(file)) EmbeddedPictures.read(file)?.let { return it }
            val mmr = MediaMetadataRetriever()
            return try {
                mmr.setDataSource(path)
                mmr.embeddedPicture
            } catch (_: Exception) {
                null
            } finally {
                runCatching { mmr.release() }
            }
        }

        /**
         * Decodes at the largest power-of-two reduction that stays ≥ [sizePx], then scales a result larger than 1.5 ×
         * [sizePx] down to [sizePx]: a 1200 px online cover asked for at 720 would otherwise stay 1200 px (5.8 MB
         * in the LRU instead of 2.1 MB, and a larger input to the ambient's blur).
         */
        private inline fun decodeSampled(sizePx: Int, decode: (BitmapFactory.Options?) -> Bitmap?): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decode(bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = runCatching { decode(opts) }.getOrNull() ?: return null
            return capped(bmp, sizePx)
        }

        /** [bmp] itself, or scaled to [sizePx] on its long side when it is more than 1.5 × that (pure size rule: [cappedSize]). */
        fun capped(bmp: Bitmap, sizePx: Int): Bitmap {
            val (w, h) = cappedSize(bmp.width, bmp.height, sizePx) ?: return bmp
            return runCatching { bmp.scale(w, h, filter = true) }.getOrNull()?.also { if (it !== bmp) bmp.recycle() } ?: bmp
        }

        /** The scaled size when a [width] × [height] decode exceeds 1.5 × [sizePx] on its long side, else null. */
        fun cappedSize(width: Int, height: Int, sizePx: Int): Pair<Int, Int>? {
            val long = max(width, height)
            if (sizePx <= 0 || long * 2 <= sizePx * 3) return null
            val scale = sizePx.toDouble() / long
            return Pair((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
        }
    }
}

/** What [ArtRepository.findOnline] did. */
enum class CoverSearch {
    /** The track has embedded art or a folder image: nothing asked. */
    LOCAL,
    FOUND,

    /** No cover (or the lookup was not allowed, or backed off). */
    NONE
}

/** Cover references travel inside MediaItem metadata as `seion-art://cover?path=…&folder=…&artist=…&album=…`. */
object ArtUris {
    const val SCHEME = "seion-art"

    fun toUri(ref: ArtRef): Uri = Uri
        .Builder()
        .scheme(SCHEME)
        .authority("cover")
        .apply {
            ref.trackPath?.let { appendQueryParameter("path", it) }
            ref.folder?.let { appendQueryParameter("folder", it) }
            ref.artist?.let { appendQueryParameter("artist", it) }
            ref.album?.let { appendQueryParameter("album", it) }
        }.build()

    /** The same reference with a version fragment (`#v=n`): the session reloads the artwork, the cache key stays. */
    fun withVersion(uri: Uri, version: Int): Uri = uri.buildUpon().fragment("v=$version").build()

    /** The fragment is ignored: only the query names the cover. */
    fun fromUri(uri: Uri?): ArtRef? {
        if (uri?.scheme != SCHEME) return null
        return ArtRef(
            uri.getQueryParameter("path"),
            uri.getQueryParameter("folder"),
            uri.getQueryParameter("artist"),
            uri.getQueryParameter("album")
        )
    }
}

/** Lets the media notification, lock screen and Bluetooth metadata load `seion-art:` covers. */
@UnstableApi
class SeionBitmapLoader(
    private val loadArt: suspend (ArtRef, Int) -> Bitmap?,
    /** A track without art shows the same no-cover tile as the app; null = no artwork. */
    private val noCover: () -> Bitmap? = { null }
) : BitmapLoader {
    /**
     * The session never waits for the network: local art, or a cover that already came online, else the no-cover tile
     * while the online lookup runs; a hit makes the service swap in a re-versioned artwork URI.
     */
    constructor(art: ArtRepository, noCover: () -> Bitmap?) : this(
        { ref, size ->
            art.loadLocal(ref, size) ?: art.loadIfFoundOnline(ref, size) ?: null.also { art.requestOnline(ref, size) }
        },
        noCover
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The service is gone: cancel artwork loads still running (tidiness; the scope holds no thread of its own). */
    fun close() = scope.cancel()

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = scope.future {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IllegalArgumentException("undecodable artwork")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val ref = ArtUris.fromUri(uri) ?: return Futures.immediateFailedFuture(IllegalArgumentException("unsupported uri"))
        return scope.future { loadArt(ref, NOTIFICATION_SIZE) ?: noCover() ?: throw IllegalStateException("no artwork") }
    }

    /**
     * The panel, lock screen and Bluetooth artwork come from Seion's resolver (per track, front cover first), not from
     * the stream's own picture: Media3 would otherwise decode `artworkData` (e.g. the first MP4 `covr` image).
     */
    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val uri = metadata.artworkUri
        if (uri != null && ArtUris.fromUri(uri) != null) return loadBitmap(uri)
        return super.loadBitmapFromMetadata(metadata)
    }

    private companion object {
        const val NOTIFICATION_SIZE = 512
    }
}
