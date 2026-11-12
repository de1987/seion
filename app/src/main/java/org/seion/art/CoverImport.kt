package org.seion.art

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A picture picked with the system photo picker, turned into a chosen cover ([CoverOverrides]): decoded once (the
 * platform applies the photo's rotation), scaled so its short side is at most [MAX_PX], cut to the centred square
 * (every cover slot is square) and stored as JPEG. The picker grants read access to that one picture: no storage
 * permission.
 */
object CoverImport {
    /** As large as the biggest online cover Seion asks for (the Cover Art Archive's 1200 px). */
    const val MAX_PX = CoverArtSize.LARGE
    private const val JPEG_QUALITY = 92

    /** The scaled size and the centred square inside it: `[width, height, left, top, side]` (pure, unit-tested). */
    fun squareCrop(width: Int, height: Int, maxPx: Int = MAX_PX): IntArray {
        val shortSide = min(width, height).coerceAtLeast(1)
        val scale = if (shortSide > maxPx) maxPx.toDouble() / shortSide else 1.0
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        val side = min(w, h)
        return intArrayOf(w, h, (w - side) / 2, (h - side) / 2, side)
    }

    /** The picture at [uri] as a square JPEG; throws on an unreadable or undecodable picture. */
    fun read(resolver: ContentResolver, uri: Uri): ByteArray {
        val bmp =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val (w, h, left, top, side) = squareCrop(info.size.width, info.size.height).toList()
                decoder.setTargetSize(w, h)
                decoder.crop = Rect(left, top, left + side, top + side)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        return ByteArrayOutputStream().use { out ->
            check(bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) { "cannot encode the picture" }
            bmp.recycle()
            out.toByteArray()
        }
    }
}
