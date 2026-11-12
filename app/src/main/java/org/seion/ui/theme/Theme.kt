package org.seion.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/** Seion palette: near-black page, white/grey text, muted gold accent. */
object SeionColors {
    val Page = Color(0xFF08090B)
    val Panel = Color(0xFF16181D)
    val Ink = Color(0xFFFFFFFF)
    val Secondary = Color(0xFFAFAFAF)
    val Muted = Color(0xFF9A9CA3)
    val Faint = Color(0xFF7D7F86)
    val Line = Color(0x29FFFFFF)
    val Tile = Color(0x0EFFFFFF)
    val SearchField = Color(0x14FFFFFF)
    val SeekTrack = Color(0xFF5A5A5A)
    val Inactive = Color(0xFF8E8E8E)
    val Accent = Color(0xFFE6D6A6)
}

/** Motion tokens: M3 durations and easings, defined once. */
object SeionMotion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Emphasized = Standard
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Mini-player -> now playing (container transform), and back. */
    const val EXPAND_MS = 450
    const val COLLAPSE_MS = 350

    /** Shared axis X drill-down: 30 dp slide; outgoing gone by 105 ms, incoming fades in from 105 ms. */
    const val NAV_MS = 300
    const val NAV_FADE_OUT_MS = 105
    const val NAV_SLIDE_DP = 30

    /** Rise of the now-playing content while it expands. */
    const val EXPAND_RISE_DP = 48
    const val AMBIENT_MS = 700
    const val COVER_MS = 400

    /**
     * Cover swap on Next/Previous: the ¼-width slide with standard easing and an 80 ms linear fade of the incoming
     * and outgoing covers, so most of the slide happens while the cover is visible (an EmphasizedDecelerate
     * slide with a 200 ms fade spends its motion while the cover is still transparent and reads as a cross-fade).
     */
    val CoverSlideEasing: Easing = Standard
    const val COVER_FADE_MS = 80
    val CoverFadeEasing: Easing = LinearEasing
    const val PLAY_PAUSE_MS = 200

    /** Art that arrives for the track on screen (tile -> cover): M3 medium2 cross-fade, in place. */
    const val ART_FADE_MS = 300

    /** A list/grid thumbnail that arrives after first composition (M3 short3). */
    const val THUMB_FADE_MS = 150
}

/**
 * Type scale: Roboto everywhere; title,
 * artist and album Light 300; times and the index Medium 500.
 */
object SeionType {
    // 1.4 em line boxes centred on the line, never trimmed: descenders (g, j, p, y) always fit.
    val NpTitle =
        TextStyle(
            fontFamily = Roboto,
            fontSize = 26.sp,
            lineHeight = 36.4.sp,
            fontWeight = FontWeight.Light,
            color = SeionColors.Ink,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        )
    val NpSecondary =
        TextStyle(
            fontFamily = Roboto,
            fontSize = 16.9.sp,
            lineHeight = 23.66.sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            fontWeight = FontWeight.Light,
            letterSpacing = 0.27.sp,
            color = SeionColors.Secondary
        )
    val NpTime =
        TextStyle(fontFamily = Roboto, fontSize = 14.8.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = SeionColors.Ink)
    val BarTitle = TextStyle(fontFamily = Roboto, fontSize = 20.sp, lineHeight = 27.sp, color = SeionColors.Ink)
    val RowTitle = TextStyle(fontFamily = Roboto, fontSize = 15.sp, lineHeight = 20.sp, color = SeionColors.Ink)
    val RowSub = TextStyle(fontFamily = Roboto, fontSize = 12.sp, lineHeight = 16.sp, color = SeionColors.Muted)
    val RowMeta = TextStyle(fontFamily = Roboto, fontSize = 12.5.sp, lineHeight = 16.sp, color = SeionColors.Muted)
    val Section = TextStyle(fontFamily = Roboto, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.1.sp, color = SeionColors.Faint)
    val Body = TextStyle(fontFamily = Roboto, fontSize = 14.sp, lineHeight = 20.sp, color = SeionColors.Secondary)
}

/** The accent of the current cover (light-vibrant swatch), or the default gold. */
val LocalAccent = staticCompositionLocalOf { SeionColors.Accent }

@Composable
fun SeionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
        darkColorScheme(
            primary = SeionColors.Accent,
            onPrimary = Color(0xFF111111),
            background = SeionColors.Page,
            surface = SeionColors.Panel,
            onSurface = SeionColors.Ink,
            onBackground = SeionColors.Ink,
            surfaceContainer = SeionColors.Panel,
            surfaceContainerHigh = Color(0xFF1E2127)
        ),
        typography = Typography().withFamily(Roboto),
        content = content
    )
}

/** The cover swap's motion as a number, pure so the "it reads as a slide" rule is unit-tested. */
object CoverMotion {
    /**
     * ∫ alpha(t)·|d offset/dt| dt over the swap, in widths: how much of the ¼-width slide happens while the incoming
     * cover is visible. A slide whose motion is spent while the cover is still transparent reads as a cross-fade.
     */
    fun visibleMotion(
        slideEasing: Easing = SeionMotion.CoverSlideEasing,
        slideMs: Int = SeionMotion.COVER_MS,
        fadeEasing: Easing = SeionMotion.CoverFadeEasing,
        fadeMs: Int = SeionMotion.COVER_FADE_MS,
        steps: Int = 4000
    ): Double {
        var sum = 0.0
        var lastOffset = 0.25
        for (i in 1..steps) {
            val t = slideMs * i.toDouble() / steps
            val offset = 0.25 * (1 - slideEasing.transform((t / slideMs).toFloat()))
            val tm = t - slideMs * 0.5 / steps
            val alpha = fadeEasing.transform((tm / fadeMs).coerceIn(0.0, 1.0).toFloat())
            sum += alpha * kotlin.math.abs(lastOffset - offset)
            lastOffset = offset
        }
        return sum
    }
}
