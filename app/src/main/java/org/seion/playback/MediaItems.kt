package org.seion.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File
import org.seion.art.ArtRef
import org.seion.art.ArtUris
import org.seion.library.AudioFiles
import org.seion.library.TrackEntity

/** Track/file -> Media3 MediaItem. The media id is the absolute file path. */
object MediaItems {
    const val EXTRA_FOLDER = "seion.folder"

    fun fromTrack(t: TrackEntity): MediaItem = build(
        path = t.path,
        title = t.title,
        artist = t.artist,
        album = t.albumTitle,
        artRef = ArtRef(t.path, t.folder, t.albumArtist ?: t.artist, t.album),
        durationMs = t.durationMs
    )

    /** A file the library does not know (yet): the file name is the title. */
    fun fromFile(f: File): MediaItem = build(
        path = f.absolutePath,
        title = AudioFiles.titleFromFileName(f.name),
        artist = null,
        album = null,
        artRef = ArtRef(f.absolutePath, f.parent, null, null),
        durationMs = 0
    )

    /** A playlist entry whose file the library doesn't know (yet): the names saved with the entry. */
    fun fromSnapshot(path: String, title: String, artist: String?, album: String?, albumArtist: String?, durationMs: Long): MediaItem =
        build(
            path = path,
            title = title,
            artist = artist,
            album = album,
            artRef = ArtRef(path, File(path).parent, albumArtist ?: artist, album),
            durationMs = durationMs
        )

    private fun build(path: String, title: String, artist: String?, album: String?, artRef: ArtRef, durationMs: Long): MediaItem {
        val meta =
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(artRef.artist)
                .setArtworkUri(ArtUris.toUri(artRef))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .apply { if (durationMs > 0) setDurationMs(durationMs) }
                .setExtras(Bundle().apply { putString(EXTRA_FOLDER, artRef.folder) })
                .build()
        return MediaItem
            .Builder()
            .setMediaId(path)
            .setUri(Uri.fromFile(File(path)))
            .setMediaMetadata(meta)
            .build()
    }

    /** Session side: a MediaItem that arrives without its local URI gets it back from the media id (a path). */
    fun withUri(item: MediaItem): MediaItem = if (item.localConfiguration != null || item.mediaId.isEmpty()) {
        item
    } else {
        item.buildUpon().setUri(Uri.fromFile(File(item.mediaId))).build()
    }
}
