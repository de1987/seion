package org.seion.art

import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What to find a cover for: a track (its embedded art), its folder, and its tag names for the online lookup. */
data class ArtRef(val trackPath: String?, val folder: String?, val artist: String?, val album: String?) {
    /**
     * Cover identity is per track (embedded art is per track and the front cover is chosen per file); refs without a
     * track (album rows) use folder + album. Identical images still share one bitmap ([ArtRepository] dedupes by source).
     */
    val cacheKey: String get() = trackPath ?: "${folder.orEmpty()}\u0001${album.orEmpty().lowercase()}\u0001${artist.orEmpty().lowercase()}"
}

/** Where a cover came from: [CHOSEN] is a picture the user picked for the album ([CoverOverrides]), kept by Seion. */
enum class ArtOrigin { CHOSEN, EMBEDDED, FOLDER, ONLINE }

/** Which kind of online source supplied a cover (the details sheet; no source is named in the UI). */
enum class OnlineKind {
    /** The open music database and its cover archive. */
    OPEN_DATABASE,

    /** Cached without a recorded source, or with one Seion does not know. */
    UNKNOWN;

    companion object {
        /** The chain's source name ([NamedSource.name]) to its kind. */
        fun of(source: String?): OnlineKind = when (source) {
            "musicbrainz" -> OPEN_DATABASE
            else -> UNKNOWN
        }
    }
}

/** What the details sheet says about a cover: its [origin] (null: none), and for an online one the kind of source. */
data class CoverSource(val origin: ArtOrigin?, val online: OnlineKind? = null)

sealed interface ResolvedArt {
    val origin: ArtOrigin

    class Bytes(val bytes: ByteArray, override val origin: ArtOrigin) : ResolvedArt

    class FileImage(val file: File, override val origin: ArtOrigin) : ResolvedArt
}

/**
 * What one online lookup came to. Only [Miss] (the sources answered "no cover", or the disk cache holds that answer) is
 * remembered as "no cover online"; an [Unanswered] lookup is asked again when that can succeed.
 */
sealed interface OnlineLookup {
    class Hit(val art: ResolvedArt) : OnlineLookup

    data object Miss : OnlineLookup

    /**
     * Not asked, or no answer: the switch is off, offline, metered with "Wi-Fi only", backing off, a network error, a
     * server error (MusicBrainz's 503) or an unexpected response. [retryInMs]: when a timed retry makes sense (a failure
     * or the back-off: when the back-off ends); null when only a change of network or settings can help.
     */
    data class Unanswered(val retryInMs: Long?) : OnlineLookup

    companion object {
        /** A found cover, or a miss when there is none (tests and simple sources). */
        fun of(art: ResolvedArt?): OnlineLookup = art?.let(::Hit) ?: Miss
    }
}

/** The settings and network the resolver consults on every call. */
data class ArtPolicy(val useFolderImages: Boolean, val onlineEnabled: Boolean, val wifiOnly: Boolean)

/**
 * Album-art lookup order (unit-tested): (0) a cover the user chose for the album ([CoverOverrides], Seion's own storage);
 * (a) embedded art, always; (b) a folder image, if enabled ([FolderImageMatcher]); (c) online, only when [OnlineGate]
 * allows it. A miss is cached per artist + album.
 * When online is disabled (or the network is metered with Wi-Fi only) the online source and its cache are never
 * touched, so no network call can happen.
 */
class AlbumArtResolver(
    private val embedded: (String) -> ByteArray?,
    private val listNames: (String) -> List<String>,
    private val policy: suspend () -> ArtPolicy,
    private val network: NetworkStateProvider,
    private val onlineSource: OnlineCoverSource,
    private val onlineCache: OnlineCoverCache,
    private val backoff: Backoff = Backoff(),
    /** Called whenever the online source is actually asked (drives the one-time privacy notice). */
    private val onOnlineLookup: () -> Unit = {},
    /** The cover the user chose for a folder + album ([CoverOverrides.file]), or null. */
    private val chosen: (folder: String?, album: String?) -> File? = { _, _ -> null }
) {
    suspend fun resolve(ref: ArtRef): ResolvedArt? = resolveLocal(ref) ?: resolveOnline(ref)

    /** (0) a chosen cover, (a) embedded art and (b) a folder image: never the network (the local phase). */
    suspend fun resolveLocal(ref: ArtRef): ResolvedArt? {
        chosen(ref.folder, ref.album)?.let { return ResolvedArt.FileImage(it, ArtOrigin.CHOSEN) }
        ref.trackPath?.let { path -> embedded(path)?.let { return ResolvedArt.Bytes(it, ArtOrigin.EMBEDDED) } }
        val p = policy()
        if (p.useFolderImages && ref.folder != null) {
            FolderImageMatcher.pick(listNames(ref.folder), ref.folder.substringAfterLast('/'))?.let { name ->
                return ResolvedArt.FileImage(File(ref.folder, name), ArtOrigin.FOLDER)
            }
        }
        return null
    }

    /**
     * Where [ref]'s cover comes from, for the details sheet: the local phase, else a cover downloaded earlier (read from
     * the disk cache, never the network; with the online switch off the cache isn't read either, as for the cover itself).
     */
    suspend fun describe(ref: ArtRef): CoverSource {
        resolveLocal(ref)?.let { return CoverSource(it.origin) }
        if (!policy().onlineEnabled) return CoverSource(null)
        val artist = ref.artist?.takeIf { it.isNotBlank() } ?: return CoverSource(null)
        val album = ref.album?.takeIf { it.isNotBlank() } ?: return CoverSource(null)
        if (onlineCache.get(artist, album) !is CachedCover.Hit) return CoverSource(null)
        return CoverSource(ArtOrigin.ONLINE, OnlineKind.of(onlineCache.source(artist, album)))
    }

    /** (c) online: the cover, or null for a miss and for a lookup that got no answer ([lookupOnline] tells them apart). */
    suspend fun resolveOnline(ref: ArtRef): ResolvedArt? = (lookupOnline(ref) as? OnlineLookup.Hit)?.art

    /** (c) online, behind the gate, the disk cache, the per-album lock, one queue and the back-off. */
    suspend fun lookupOnline(ref: ArtRef): OnlineLookup {
        val p = policy()
        // Without both names nothing can be asked, whatever the network: a lasting answer for these names.
        val artist = ref.artist?.takeIf { it.isNotBlank() } ?: return OnlineLookup.Miss
        val album = ref.album?.takeIf { it.isNotBlank() } ?: return OnlineLookup.Miss
        val decision = OnlineGate.decide(p.onlineEnabled, p.wifiOnly, network.current())
        if (decision == OnlineDecision.DISABLED) {
            Log.d(TAG, "online lookup skipped: disabled")
            return OnlineLookup.Unanswered(retryInMs = null)
        }
        // One lookup per album at a time: the list, the grid and now playing ask concurrently; the others wait and then
        // read the cache the first one filled.
        val lock = albumLocks.getOrPut(OnlineCoverCache.key(artist, album)) { Mutex() }
        return lock.withLock { lookupAlbum(artist, album, decision) }
    }

    private suspend fun lookupAlbum(artist: String, album: String, decision: OnlineDecision): OnlineLookup {
        // Offline or metered: a cover downloaded earlier still shows (reading the disk cache is no network call).
        when (val cached = onlineCache.get(artist, album)) {
            is CachedCover.Hit -> return OnlineLookup.Hit(ResolvedArt.FileImage(cached.file, ArtOrigin.ONLINE))
            CachedCover.Miss -> return OnlineLookup.Miss
            CachedCover.Unknown -> Unit
        }
        if (decision != OnlineDecision.ALLOWED) {
            // Asked again when the network allows it (ArtRepository.retryUnanswered).
            Log.d(TAG, "online lookup skipped: ${decision.name.lowercase()}")
            return OnlineLookup.Unanswered(retryInMs = null)
        }
        if (!backoff.allowed()) {
            Log.d(TAG, "online lookup skipped: backoff")
            return OnlineLookup.Unanswered(retryInMs = backoff.remainingMs().coerceAtLeast(MIN_RETRY_MS))
        }
        // Albums are looked up one at a time (about 10 requests each at most).
        return queue.withLock { askSource(artist, album) }
    }

    private suspend fun askSource(artist: String, album: String): OnlineLookup = try {
        onOnlineLookup()
        val found =
            if (onlineSource is SourcedCoverSource) {
                onlineSource.sourcedCover(artist, album)
            } else {
                onlineSource.frontCover(artist, album)?.let { SourcedCover(it, null) }
            }
        backoff.success()
        if (found == null) {
            onlineCache.putMiss(artist, album)
            Log.i(TAG, "online lookup: no cover")
            OnlineLookup.Miss
        } else {
            Log.i(TAG, "online lookup: cover found (${found.bytes.size} bytes)")
            OnlineLookup.Hit(ResolvedArt.FileImage(onlineCache.putHit(artist, album, found.bytes, found.source), ArtOrigin.ONLINE))
        }
    } catch (_: LookupCancelled) {
        // The online switch went off mid-lookup: no answer, so no miss is cached and nothing backs off.
        Log.i(TAG, "online lookup cancelled")
        OnlineLookup.Unanswered(retryInMs = null)
    } catch (e: IOException) {
        backoff.failure()
        Log.w(TAG, "online lookup failed (${e.message}); backing off ${backoff.currentDelayMs / 1000} s")
        OnlineLookup.Unanswered(retryInMs = backoff.remainingMs().coerceAtLeast(MIN_RETRY_MS))
    } catch (_: kotlinx.serialization.SerializationException) {
        backoff.failure()
        Log.w(TAG, "online lookup: unexpected response")
        OnlineLookup.Unanswered(retryInMs = backoff.remainingMs().coerceAtLeast(MIN_RETRY_MS))
    }

    private val albumLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val queue = Mutex()

    companion object {
        private const val TAG = "SeionArt"

        /** A timed retry never comes sooner than this (the back-off itself starts at 30 s). */
        const val MIN_RETRY_MS = 1_000L
    }
}

/**
 * After a transient online failure (network error, HTTP 5xx such as the 503 MusicBrainz sends when rate-limited)
 * no request is made for a while: 30 s, doubling up to 30 min, reset by the next success. Pure, unit-tested.
 */
class Backoff(
    private val baseMs: Long = 30_000,
    private val maxMs: Long = 30 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var failures = 0
    private var until = 0L

    val currentDelayMs: Long get() = if (failures == 0) 0 else minOf(maxMs, baseMs shl (failures - 1).coerceAtMost(20))

    @Synchronized fun allowed(): Boolean = clock() >= until

    /** How long until the next request is allowed (0 when it is). */
    @Synchronized fun remainingMs(): Long = (until - clock()).coerceAtLeast(0)

    @Synchronized fun failure() {
        failures++
        until = clock() + currentDelayMs
    }

    @Synchronized fun success() {
        failures = 0
        until = 0
    }
}
