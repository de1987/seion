package org.seion.playback

import androidx.media3.common.MediaItem
import org.seion.art.ArtUris
import org.seion.art.CoverOverrides
import org.seion.library.TrackEntity

/** Re-versions a queue item's artwork URI when its cover arrives online, so the session reloads it. */
object ArtworkRefresh {
    /** The same item with `#v=<version>` on its artwork URI when it is the track [hitKey] names, else null. */
    fun refreshed(item: MediaItem, hitKey: String, version: Int): MediaItem? {
        val uri = item.mediaMetadata.artworkUri ?: return null
        if (ArtUris.fromUri(uri)?.cacheKey != hitKey) return null
        val meta = item.mediaMetadata.buildUpon().setArtworkUri(ArtUris.withVersion(uri, version)).build()
        return item.buildUpon().setMediaMetadata(meta).build()
    }

    /** The same item re-versioned when its cover's [CoverOverrides.key] is in [keys] (a chosen cover set or removed). */
    fun refreshedForChosen(item: MediaItem, keys: Set<String>, version: Int): MediaItem? {
        val uri = item.mediaMetadata.artworkUri ?: return null
        val key = ArtUris.fromUri(uri)?.let(CoverOverrides::keyOf) ?: return null
        if (key !in keys) return null
        val meta = item.mediaMetadata.buildUpon().setArtworkUri(ArtUris.withVersion(uri, version)).build()
        return item.buildUpon().setMediaMetadata(meta).build()
    }

    /**
     * The same item with [t]'s metadata (title, artist, album and the cover names in the artwork URI) when it plays
     * that file, else null: an identified track shows its new names, and its cover is looked up under them. The
     * playable part (URI, configuration) is kept, so a metadata-only replace never interrupts playback.
     */
    fun identified(item: MediaItem, t: TrackEntity): MediaItem? {
        if (item.mediaId != t.path) return null
        return item.buildUpon().setMediaMetadata(MediaItems.fromTrack(t).mediaMetadata).build()
    }
}
