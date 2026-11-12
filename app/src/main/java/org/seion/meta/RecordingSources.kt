package org.seion.meta

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.seion.art.CoverMatch
import org.seion.art.OnlineHttp
import org.seion.art.RateLimiter

/** Recordings for a parsed artist + title + duration; [IOException] on a transient failure. */
interface RecordingSource {
    val name: String

    suspend fun search(artist: String, title: String, durationMs: Long): List<RecordingCandidate>
}

/**
 * MusicBrainz recording search: `recording:"T" AND artist:"A" AND dur:[ms−3000 TO ms+3000]`, limit 25, with the base
 * names. Shares the 1 req/s limiter with the cover chain (same host). Only the artist, title and length are sent.
 */
class MusicBrainzRecordingSource(
    private val client: OkHttpClient = OnlineHttp.defaultClient(),
    private val limiter: RateLimiter,
    private val base: String = "https://musicbrainz.org"
) : RecordingSource {
    override val name = "musicbrainz"

    override suspend fun search(artist: String, title: String, durationMs: Long): List<RecordingCandidate> = withContext(Dispatchers.IO) {
        val query =
            "recording:${OnlineHttp.phrase(CoverMatch.baseTitle(title))} AND artist:${OnlineHttp.phrase(CoverMatch.baseArtist(artist))}" +
                " AND dur:[${durationMs - 3_000} TO ${durationMs + 3_000}]"
        val url =
            "$base/ws/2/recording/"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("limit", "25")
                .addQueryParameter("fmt", "json")
                .build()
        val body =
            limiter.run {
                Log.i(TAG, "online request: musicbrainz recording search")
                OnlineHttp.fetch(client, OnlineHttp.request(url.toString(), "application/json")) { r ->
                    if (r.code == 404) return@run null
                    if (!r.isSuccessful) throw IOException("MusicBrainz HTTP ${r.code}")
                    r.body.string()
                }
            } ?: return@withContext emptyList()
        OnlineHttp.json.decodeFromString<Response>(body).recordings.map { r ->
            RecordingCandidate(
                artist = r.artistCredit.joinToString("") { it.name + it.joinphrase },
                title = r.title,
                albums = r.releases.map { it.title },
                durationMs = r.length,
                extra = (
                    listOf(r.disambiguation) + r.releases.take(5).flatMap {
                        it.releaseGroup?.secondaryTypes.orEmpty()
                    }
                    ).joinToString(" "),
                id = r.id
            )
        }
    }

    @Serializable
    private data class Response(val recordings: List<Recording> = emptyList())

    @Serializable
    private data class Recording(
        val id: String,
        val title: String = "",
        val length: Long? = null,
        val disambiguation: String = "",
        @SerialName("artist-credit") val artistCredit: List<Credit> = emptyList(),
        val releases: List<Release> = emptyList()
    )

    @Serializable
    private data class Credit(val name: String = "", val joinphrase: String = "")

    @Serializable
    private data class Release(val title: String = "", @SerialName("release-group") val releaseGroup: ReleaseGroup? = null)

    @Serializable
    private data class ReleaseGroup(@SerialName("secondary-types") val secondaryTypes: List<String> = emptyList())

    private companion object {
        const val TAG = "SeionMeta"
    }
}
