package org.seion.art

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.seion.BuildConfig

/**
 * An online source of front covers, queried with only the artist and album names. Returns null when the source
 * has no cover (cached as a miss), throws [IOException] on a transient failure (not cached). Keep new sources
 * behind this interface.
 */
interface OnlineCoverSource {
    suspend fun frontCover(artist: String, album: String): ByteArray?
}

/**
 * At most one request per [minIntervalMs] across every caller of one host (MusicBrainz asks for 1 req/s). Every source
 * owns its limiter; no source waits while holding another source's lock.
 */
class RateLimiter(
    private val minIntervalMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()
    private var last = Long.MIN_VALUE / 2

    /**
     * [block] once the interval has passed. A lookup can wait here for seconds: a run stopped meanwhile ends here, and
     * the online switch is checked again by [OnlineHttp.gate] right before the request goes out.
     */
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val wait = last + minIntervalMs - clock()
        if (wait > 0) {
            sleep(wait)
            currentCoroutineContext().ensureActive()
        }
        last = clock()
        block()
    }
}

/**
 * A lookup cancelled because the online switch went off ([OnlineHttp.stopAll], or [OnlineHttp.gate] refusing the next
 * request): no answer, never a miss or a failure.
 */
class LookupCancelled(cause: IOException) : IOException("lookup cancelled", cause)

/** HTTP bits shared by the online sources: the User-Agent and the client. */
object OnlineHttp {
    /**
     * `Seion/<version> ( <project URL> )`, the format MusicBrainz asks for (application/version plus a contact): the
     * version without the debug suffix, and the project URL from the build (`seion.projectUrl`); never personal data
     * or an e-mail.
     */
    val USER_AGENT = userAgent(BuildConfig.VERSION_NAME, BuildConfig.PROJECT_URL)

    internal fun userAgent(versionName: String, projectUrl: String): String = "Seion/${versionName.substringBefore('-')} ( $projectUrl )"

    /** The client every online source shares; [allowed] is the online switch, checked before every request ([gate]). */
    fun defaultClient(allowed: () -> Boolean = { true }): OkHttpClient = OkHttpClient
        .Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(gate(allowed))
        .build()

    /**
     * The online switch, checked on every request right before it is sent, after any rate-limiter wait:
     * [stopAll] only reaches the calls already made, and a lookup waiting between two sources (or two requests of one
     * source) is in no call. With the switch off the request ends as [LookupCancelled] and no byte is sent. Every
     * source of the cover chain and of identification goes through here, so one check covers them all.
     */
    fun gate(allowed: () -> Boolean): Interceptor = Interceptor { chain ->
        if (!allowed()) {
            Log.i("SeionArt", "online request refused: online lookups are off")
            throw LookupCancelled(IOException("online lookups are off"))
        }
        chain.proceed(chain.request())
    }

    /**
     * The online switch went off: the lookups in flight are cancelled (each ends as [LookupCancelled]: no answer, nothing
     * cached, no back-off) and the idle pooled connections are closed, so no byte moves afterwards.
     */
    fun stopAll(client: OkHttpClient) {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    /**
     * One request; a call cancelled by [stopAll] ends as [LookupCancelled], also while its body is being read. A
     * cancelled coroutine (a stopped "Find missing covers again" run) cancels the call at once and ends with its
     * [kotlinx.coroutines.CancellationException]: no answer, so nothing is cached and nothing backs off. [read] gets
     * the response with its body already in memory ([execute]), so it does no network I/O of its own.
     */
    suspend inline fun <T> fetch(client: OkHttpClient, request: Request, read: (Response) -> T): T = execute(client, request).use(read)

    /**
     * The call, suspended (not blocking) until the whole body has arrived: the network part runs on OkHttp's threads and
     * the body is buffered there, so a cancelled coroutine can still cancel the call ([invokeOnCancellation]) until the
     * last byte. An [IOException] from a cancelled call is [LookupCancelled] while the coroutine is still active
     * ([stopAll]); a cancelled coroutine ends with its own cancellation instead. Timeouts and HTTP errors are unchanged.
     */
    @PublishedApi
    internal suspend fun execute(client: OkHttpClient, request: Request): Response {
        currentCoroutineContext().ensureActive()
        val call = client.newCall(request)
        try {
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

                        override fun onResponse(call: Call, response: Response) {
                            val buffered =
                                try {
                                    response.use { r -> r.newBuilder().body(r.body.bytes().toResponseBody(r.body.contentType())).build() }
                                } catch (e: IOException) {
                                    cont.resumeWithException(e)
                                    return
                                }
                            cont.resume(buffered)
                        }
                    }
                )
            }
        } catch (e: IOException) {
            if (call.isCanceled()) {
                currentCoroutineContext().ensureActive()
                if (e !is LookupCancelled) throw LookupCancelled(e)
            }
            throw e
        }
    }

    fun request(url: String, accept: String): Request = Request
        .Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", accept)
        .build()

    /** An image download: 404 = none; any other failure throws [IOException] (transient: no miss is cached). */
    suspend fun image(client: OkHttpClient, url: String, what: String): ByteArray? = fetch(client, request(url, "image/*")) { r ->
        when {
            r.code == 404 -> null
            !r.isSuccessful -> throw IOException("$what HTTP ${r.code}")
            else -> r.body.bytes().takeIf { it.isNotEmpty() }
        }
    }

    internal val json = Json { ignoreUnknownKeys = true }

    /** Lucene phrase with escaping. */
    fun phrase(s: String) = "\"" + s.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * Which Cover Art Archive thumbnail to fetch (its sizes are 250, 500 and 1200 px): 1200 px when Now playing's cover
 * slot is wider than 500 px on this screen (density above 2.0: 360 dpi and denser phones), else 500 px.
 * Pure, unit-tested ([forDensity]).
 */
object CoverArtSize {
    /** Now playing's cover in dp (the 360 × 640 dp design; AmbientModel's cover). */
    const val NOW_PLAYING_COVER_DP = 248
    const val SMALL = 500
    const val LARGE = 1200

    /** [slotPx]: the largest cover slot in pixels. A 500 px image covers it unless it is wider. */
    fun forSlot(slotPx: Int): Int = if (slotPx > SMALL) LARGE else SMALL

    fun forDensity(density: Float): Int = forSlot((NOW_PLAYING_COVER_DP * density).toInt())
}

/**
 * MusicBrainz **release-group** search by album artist + album, then the Cover Art Archive's front image of
 * the best groups (one image per album, survives reissues). Only the two names are sent. The User-Agent identifies
 * the app and its project URL (the contact MusicBrainz asks for): never an e-mail.
 * Queries carry the base names ("Rumours (Remastered)" -> "Rumours") and a group is fetched only when its own title
 * and artist credit pass [CoverMatch]. When the strict query finds nothing that passes, the alias
 * route looks the artist up by name, alias or sort name (romanised names, Hangul/Latin pairs) and searches its groups
 * by release-group or release title (pseudo-releases carry transliterations).
 */
class MusicBrainzCoverSource(
    private val client: OkHttpClient = OnlineHttp.defaultClient(),
    /** Shared with track identification (same host, one request per second in total). */
    private val limiter: RateLimiter = RateLimiter(),
    private val musicBrainzBase: String = "https://musicbrainz.org",
    private val coverArtBase: String = "https://coverartarchive.org",
    /** The thumbnail size asked for ([CoverArtSize]): 500 or 1200 px. */
    private val coverSize: () -> Int = { CoverArtSize.SMALL }
) : OnlineCoverSource {
    override suspend fun frontCover(artist: String, album: String): ByteArray? = withContext(Dispatchers.IO) {
        val baseAlbum = CoverMatch.baseTitle(album)
        val groups = search(luceneQuery(CoverMatch.baseArtist(artist), baseAlbum), "release-group search")
        val passing = CoverMatch.ranked(artist, album, groups, { it.credit }, { it.title })
        if (passing.isNotEmpty()) return@withContext fetchFirst(passing)
        // Alias route: only after a strict miss.
        val arid = artistByAlias(CoverMatch.baseArtist(artist)) ?: return@withContext null
        val byArtist =
            search(
                "arid:$arid AND (releasegroup:${OnlineHttp.phrase(baseAlbum)} OR release:${OnlineHttp.phrase(baseAlbum)})",
                "alias group search"
            )
                .filter { !CoverMatch.versionRejected(album, it.title) && aliasAlbumSim(album, it) >= CoverMatch.ALBUM_MIN }
                .sortedByDescending { aliasAlbumSim(album, it) }
        fetchFirst(byArtist)
    }

    /**
     * The artist is fixed by its MBID on the alias route, so a group matches when its own title or one of its releases'
     * titles does: pseudo-releases carry the transliteration ("Muzai Moratorium" for 無罪モラトリアム).
     */
    private fun aliasAlbumSim(album: String, g: RgCandidate): Double =
        (listOf(g.title) + g.releaseTitles).maxOf { CoverMatch.albumSim(album, it) }

    private suspend fun fetchFirst(groups: List<RgCandidate>): ByteArray? {
        for (g in groups.take(MAX_FETCHES)) fetchFront(g.id)?.let { return it }
        return null
    }

    private suspend fun search(query: String, what: String): List<RgCandidate> =
        get("release-group", query, what)?.let(::parseReleaseGroups).orEmpty()

    /** The top artist with score >= 90 whose name, alias or sort name matches. */
    private suspend fun artistByAlias(name: String): String? {
        val p = OnlineHttp.phrase(name)
        val body = get("artist", "artist:$p OR alias:$p OR sortname:$p", "artist alias search") ?: return null
        return OnlineHttp.json
            .decodeFromString<ArtistResponse>(body)
            .artists
            .firstOrNull()
            ?.takeIf { it.score >= MIN_ARTIST_SCORE }
            ?.id
    }

    private suspend fun get(entity: String, query: String, what: String): String? {
        val url =
            "$musicBrainzBase/ws/2/$entity/"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("limit", "10")
                .addQueryParameter("fmt", "json")
                .build()
        return limiter.run {
            Log.i(TAG, "online request: musicbrainz $what")
            OnlineHttp.fetch(client, OnlineHttp.request(url.toString(), "application/json")) { r ->
                if (r.code == 404) return@run null
                if (!r.isSuccessful) throw IOException("MusicBrainz HTTP ${r.code}")
                r.body.string()
            }
        }
    }

    private suspend fun fetchFront(mbid: String): ByteArray? = limiter.run {
        val size = coverSize()
        Log.i(TAG, "online request: musicbrainz front cover ($size px)")
        OnlineHttp.image(client, frontUrl(coverArtBase, mbid, size), "Cover Art Archive")
    }

    @Serializable
    private data class SearchResponse(@SerialName("release-groups") val releaseGroups: List<ReleaseGroup> = emptyList())

    @Serializable
    private data class ReleaseGroup(
        val id: String,
        val score: Int = 0,
        val title: String = "",
        @SerialName("primary-type") val primaryType: String? = null,
        @SerialName("artist-credit") val artistCredit: List<Credit> = emptyList(),
        val releases: List<Release> = emptyList()
    )

    @Serializable
    private data class Release(val title: String = "")

    @Serializable
    private data class Credit(val name: String = "", val joinphrase: String = "")

    @Serializable
    private data class ArtistResponse(val artists: List<MbArtist> = emptyList())

    @Serializable
    private data class MbArtist(val id: String, val score: Int = 0)

    /** A release group as the match rules see it: its title and the full artist credit ("A & B"). */
    data class RgCandidate(val id: String, val title: String, val credit: String, val releaseTitles: List<String> = emptyList())

    companion object {
        private const val TAG = "SeionArt"
        val USER_AGENT get() = OnlineHttp.USER_AGENT

        /** Cover Art Archive fetches per route (keeps each lookup's request count bounded). */
        private const val MAX_FETCHES = 2
        private const val MIN_ARTIST_SCORE = 90

        fun defaultClient(): OkHttpClient = OnlineHttp.defaultClient()

        /** The release group's front thumbnail: `front-500` or `front-1200` (any other size falls back to 500). */
        fun frontUrl(base: String, mbid: String, size: Int): String =
            "$base/release-group/$mbid/front-${if (size == CoverArtSize.LARGE) CoverArtSize.LARGE else CoverArtSize.SMALL}"

        /** `releasegroup:"…" AND artist:"…"` with Lucene phrase escaping. */
        fun luceneQuery(artist: String, album: String): String =
            "releasegroup:${OnlineHttp.phrase(album)} AND artist:${OnlineHttp.phrase(artist)}"

        /** The groups in the source's order: albums before singles/EPs/others, then best score. */
        fun parseReleaseGroups(body: String): List<RgCandidate> = OnlineHttp.json
            .decodeFromString<SearchResponse>(body)
            .releaseGroups
            .sortedWith(
                compareByDescending<ReleaseGroup> {
                    it.primaryType.equals("Album", ignoreCase = true)
                }.thenByDescending { it.score }
            ).distinctBy { it.id }
            .map { g ->
                RgCandidate(
                    g.id,
                    g.title,
                    g.artistCredit.joinToString("") {
                        it.name + it.joinphrase
                    },
                    g.releases.map { it.title }.distinct()
                )
            }
    }
}
