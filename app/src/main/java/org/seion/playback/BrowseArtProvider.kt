package org.seion.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.seion.BuildConfig
import org.seion.SeionApp
import org.seion.art.ArtRef
import org.seion.ui.components.NoCoverArt

/**
 * The covers of browse-tree items: `content://<package>.browseart/t/<track id>`, a
 * library row id and nothing else, so no file path ever reaches a browser. Android Auto, SystemUI's resumption card and
 * other browsers load artwork URIs themselves, and they can't read Seion's `seion-art:` URIs (those carry the path and
 * only Seion's own bitmap loader understands them).
 *
 * Not exported: the service grants read access for this authority, by prefix, to each browser that connects
 * ([grantTo]). Local art only (the file, the folder image, a cover found online earlier in this process); browsing never
 * starts an online lookup. A song without art gets the "no cover" tile, as on the media panel.
 */
class BrowseArtProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = if (trackId(uri) != null) MIME else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val id = trackId(uri) ?: throw FileNotFoundException("no such cover")
        val context = context ?: throw FileNotFoundException("no context")
        val bitmap = runBlocking { load(context, id) }
        return openPipeHelper(uri, MIME, null, bitmap) { output, _, _, _, bmp ->
            FileOutputStream(output.fileDescriptor).use { out ->
                // A browser that closes the stream early is not an error.
                runCatching { bmp?.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
                    .onFailure { Log.d(TAG, "cover stream closed early: ${it.message}") }
            }
        }
    }

    private suspend fun load(context: Context, trackId: Long): Bitmap {
        val container = (context.applicationContext as SeionApp).container
        val t = container.db.tracks().byId(trackId)
        val ref = t?.let { ArtRef(it.path, it.folder, it.albumArtist ?: it.artist, it.album) }
        val art = ref?.let { container.art.loadLocal(it, SIZE_PX) ?: container.art.loadIfFoundOnline(it, SIZE_PX) }
        return art ?: NoCoverArt.bitmap(context, NO_COVER_PX)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null

    companion object {
        private const val TAG = "SeionBrowse"
        private const val MIME = "image/jpeg"
        private const val JPEG_QUALITY = 90

        /** Cars and the resumption card draw browse art at about 128–480 px; one size for all keeps the LRU small. */
        const val SIZE_PX = 480
        private const val NO_COVER_PX = 512

        val AUTHORITY = "${BuildConfig.APPLICATION_ID}.browseart"

        fun uri(trackId: Long): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("t").appendPath(trackId.toString()).build()

        /** `t/<id>` -> id. */
        fun trackId(uri: Uri): Long? {
            if (uri.authority != AUTHORITY) return null
            val seg = uri.pathSegments
            return if (seg.size == 2 && seg[0] == "t") seg[1].toLongOrNull() else null
        }

        /**
         * Lets [packageName] (a browser that just connected: SystemUI, a car, a Bluetooth stack) read every cover of
         * this authority until the device restarts. Seion's own package needs no grant; an unknown package (a legacy
         * controller Media3 can't name) is skipped.
         */
        fun grantTo(context: Context, packageName: String) {
            if (packageName == context.packageName) return
            runCatching {
                context.grantUriPermission(
                    packageName,
                    Uri.Builder().scheme("content").authority(AUTHORITY).build(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }.onFailure { Log.d(TAG, "no cover grant for $packageName: ${it.message}") }
        }
    }
}
