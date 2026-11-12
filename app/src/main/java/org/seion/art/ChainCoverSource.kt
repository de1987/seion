package org.seion.art

import android.util.Log
import java.io.IOException

/** A cover and the [NamedSource.name] of the source that supplied it (null: not known). */
class SourcedCover(val bytes: ByteArray, val source: String?)

/** A source that can say which of its sources supplied the cover ([ChainCoverSource]). */
interface SourcedCoverSource : OnlineCoverSource {
    suspend fun sourcedCover(artist: String, album: String): SourcedCover?

    override suspend fun frontCover(artist: String, album: String): ByteArray? = sourcedCover(artist, album)?.bytes
}

/** One source in the chain with its own back-off (its rate limiter lives inside the source). */
class NamedSource(val name: String, val source: OnlineCoverSource, val backoff: Backoff = Backoff())

/**
 * Tries the sources in order and returns the first image whose candidate passed [CoverMatch].
 * A miss (null, cached for 30 days) only when every source answered definitively; a source skipped for its back-off
 * or failing transiently makes the whole lookup transient ([IOException]: the resolver backs off, no miss is cached).
 * A [LookupCancelled] (the online switch went off) ends the lookup at once, with no back-off.
 */
class ChainCoverSource(private val sources: List<NamedSource>) : SourcedCoverSource {
    override suspend fun sourcedCover(artist: String, album: String): SourcedCover? {
        var transient = false
        for (s in sources) {
            if (!s.backoff.allowed()) {
                Log.d(TAG, "source ${s.name}: skipped (backoff)")
                transient = true
                continue
            }
            try {
                val bytes = s.source.frontCover(artist, album)
                s.backoff.success()
                if (bytes != null) {
                    Log.i(TAG, "source ${s.name}: cover found")
                    return SourcedCover(bytes, s.name)
                }
                Log.i(TAG, "source ${s.name}: none")
            } catch (e: LookupCancelled) {
                // The online switch went off: no answer, no back-off, and no further source is asked.
                throw e
            } catch (e: IOException) {
                s.backoff.failure()
                transient = true
                Log.w(TAG, "source ${s.name}: failed (${e.message}); backing off ${s.backoff.currentDelayMs / 1000} s")
            } catch (_: kotlinx.serialization.SerializationException) {
                s.backoff.failure()
                transient = true
                Log.w(TAG, "source ${s.name}: unexpected response")
            }
        }
        if (transient) throw IOException("no cover yet; a source was unavailable")
        return null
    }

    private companion object {
        const val TAG = "SeionArt"
    }
}
