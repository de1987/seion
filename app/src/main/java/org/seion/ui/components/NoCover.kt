package org.seion.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import org.seion.R

/**
 * The "no cover" placeholder: the launcher icon in miniature. The tile is the icon's ink [Tile]; in the centre a
 * smooth eighth note (`ic_no_cover_glyph`, design/no-cover/) painted with the icon's amber-to-coral gradient,
 * top-left to bottom-right across the mark (the gradient lives in the drawable: it is drawn untinted). No border, no
 * noise; the corner is the cover's own.
 */
object NoCoverColors {
    /** The tile: the launcher tile's ink (`seion_icon_tile`, also the splash icon background). */
    val Tile = Color(0xFF16171A)

    /** The mark's gradient stop 0 (top-left), the launcher disc's amber (7.21:1 or more on [Tile] with [Coral]). */
    val Amber = Color(0xFFFFC453)

    /** The mark's gradient stop 1 (bottom-right), the launcher disc's coral. */
    val Coral = Color(0xFFFF7F5C)

    /** The gradient's midpoint, the "swatch" the two colours below are derived from. */
    val Mid = Color(0xFFFFA258)

    /**
     * Shuffle/repeat accent when the current track has no art: [Mid] through `AmbientRepository.accentFrom`, as a warm
     * orange cover would get (NoCoverTest pins it to the rule). The mark's colours, not the ink: the UI stays warm.
     */
    val Accent = Color(0xFFEBA670)

    /** Classic background gradient start and the browse screens' faint top tint: [Mid] through `ClassicGradient.baseColour`. */
    val ClassicBase = Color(0xFFC25600)
}

/** The tile at a fixed [size]. */
@Composable
fun NoCoverTile(size: Dp, modifier: Modifier = Modifier, corner: Dp = 0.dp) = NoCoverTile(modifier.size(size), corner)

/** The tile filling [modifier]'s bounds; the glyph is drawn at the full tile size (its own viewport centres it). */
@Composable
fun NoCoverTile(modifier: Modifier = Modifier, corner: Dp = 0.dp) {
    val description = stringResource(R.string.no_cover)
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(NoCoverColors.Tile)
    ) {
        Image(
            painterResource(R.drawable.ic_no_cover_glyph),
            contentDescription = description,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * The tile as a bitmap, rendered once per process (at the first size asked for) and kept in memory: the ink tile with
 * the gradient note, as [NoCoverTile] draws it, for the media-session artwork. "Seion Infinite" without a cover is
 * `ui/ambient/NoCoverGlow` (the ink with a soft top glow), not this bitmap.
 */
object NoCoverArt {
    @Volatile private var cached: Bitmap? = null

    /** The tile with its mark: the media-session artwork (panel, lock screen, Bluetooth) of a track without art. */
    fun bitmap(context: Context, px: Int = 512): Bitmap = cached ?: synchronized(this) {
        cached ?: render(context, px).also { cached = it }
    }

    private fun render(context: Context, px: Int): Bitmap {
        val bmp = createBitmap(px, px).apply { eraseColor(NoCoverColors.Tile.toArgb()) }
        val canvas = Canvas(bmp)
        ContextCompat.getDrawable(context, R.drawable.ic_no_cover_glyph)?.mutate()?.apply {
            setBounds(0, 0, px, px)
            draw(canvas)
        }
        return bmp
    }
}
