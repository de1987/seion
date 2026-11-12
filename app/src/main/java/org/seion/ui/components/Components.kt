package org.seion.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filter
import org.seion.AppContainer
import org.seion.R
import org.seion.art.ArtRef
import org.seion.library.TrackEntity
import org.seion.ui.Format
import org.seion.ui.motion.SteppedMotion
import org.seion.ui.motion.rememberSteppedClock
import org.seion.ui.theme.LocalAccent
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionMotion
import org.seion.ui.theme.SeionType

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("no container") }

/** System "remove animations" (animator duration scale 0): infinite motion (drift, level bars) stays still. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * True while Now playing covers the browse stack (the stack stays composed underneath): its decorations stop moving
 * there, since every redraw under the overlay still costs a full frame.
 */
val LocalCoveredByNowPlaying = compositionLocalOf { false }

/** A cover bitmap for [ref] at about [size], reloaded when cached art is invalidated. */
@Composable
fun rememberCover(ref: ArtRef?, size: Dp): ImageBitmap? {
    val c = LocalContainer.current
    val gen by c.art.generationFlow.collectAsStateWithLifecycle()
    val px = with(LocalDensity.current) { size.roundToPx() }.let {
        if (it <= 160) {
            160
        } else if (it <= 320) {
            320
        } else {
            720
        }
    }
    // Keyed by the whole ref: a track identified online keeps its key but gets names to look its cover up with.
    val cached = remember(ref, gen) { ref?.let { c.art.cached(it, px)?.asImageBitmap() } }
    val state =
        produceState(cached, ref, gen) {
            if (ref == null) {
                value = null
                return@produceState
            }
            value = c.art.load(ref, px)?.asImageBitmap()
            // A cover found online later (after an identification, "Find missing covers again") fades in here too.
            if (value == null) c.art.onlineHits.filter { it == ref.cacheKey }.collect { value = c.art.load(ref, px)?.asImageBitmap() }
        }
    return state.value
}

@Composable
fun Cover(ref: ArtRef?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 3.dp) {
    val bmp = rememberCover(ref, size)
    FadingCover(bmp, modifier.size(size), corner, SeionMotion.THUMB_FADE_MS)
}

/**
 * The tile until the art arrives, then a [fadeMs] cross-fade to it; a bitmap already there on first
 * composition shows at once. Reduced motion snaps.
 */
@Composable
fun FadingCover(bmp: ImageBitmap?, modifier: Modifier, corner: Dp, fadeMs: Int) {
    val reduce = LocalReduceMotion.current
    Crossfade(bmp, modifier, animationSpec = if (reduce) snap() else tween(fadeMs, easing = SeionMotion.Standard), label = "thumb") { b ->
        CoverBitmap(b, Modifier.fillMaxSize(), corner)
    }
}

@Composable
fun CoverBitmap(bmp: ImageBitmap?, modifier: Modifier, corner: Dp = 3.dp) {
    if (bmp == null) {
        NoCoverTile(modifier, corner)
        return
    }
    Box(modifier.clip(RoundedCornerShape(corner)).background(Color(0xFF1C1E24)), contentAlignment = Alignment.Center) {
        Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun IconBtn(
    @DrawableRes icon: Int,
    description: String,
    tag: String,
    modifier: Modifier = Modifier,
    tint: Color = SeionColors.Ink,
    iconSize: Dp = 24.dp,
    onClick: () -> Unit
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

data class MenuAction(val label: String, val tag: String, val onClick: () -> Unit)

@Composable
fun OverflowMenu(actions: List<MenuAction>, tag: String = "menu_more", description: String? = null, tint: Color = SeionColors.Ink) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconBtn(R.drawable.ic_more, description ?: stringResource(R.string.more_options), tag, tint = tint, iconSize = 20.dp) {
            open = true
        }
        // Popups are separate windows: expose the items' test tags there too (adb_ui.py).
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true }
        ) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label) },
                    onClick = {
                        open = false
                        a.onClick()
                    },
                    modifier = Modifier.testTag(a.tag)
                )
            }
        }
    }
}

/** The browse app bar: back, title (single line, ellipsized), action icons. 56 dp. */
@Composable
fun AppBar(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconBtn(R.drawable.ic_back, stringResource(R.string.back), "nav_back", onClick = onBack)
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(16.dp))
        }
        Text(
            title,
            style = SeionType.BarTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("bar_title")
        )
        actions()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = SeionType.Section, modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp))
}

@Composable
fun Pill(
    text: String,
    tag: String,
    modifier: Modifier = Modifier,
    solid: Boolean = false,
    @DrawableRes icon: Int? = null,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .then(if (solid) Modifier.background(Color(0xFFF1F1F1)) else Modifier.border(1.dp, Color(0x40FFFFFF), shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val fg = if (solid) Color(0xFF111111) else SeionColors.Ink
        if (icon != null) Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(14.dp))
        Text(text, color = fg, fontSize = 13.sp, fontWeight = if (solid) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
    }
}

/**
 * The list-header chip ("Edit order", "Title ▾"): 12 sp, light grey on a faint outline. 32 dp tall (M3's chip
 * height, the [Pill]'s) rather than a slimmer 26 dp, so it is a usable touch target. [description] replaces what
 * TalkBack reads (the text otherwise); [dropdown] adds the ▾ of a chip that opens a menu.
 */
@Composable
fun Chip(
    text: String,
    tag: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    dropdown: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            // Before clearAndSetSemantics: a tag after it in the chain would be cleared with the text.
            .testTag(tag)
            .heightIn(min = 32.dp)
            .clip(shape)
            .border(1.dp, Color(0x29FFFFFF), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .then(
                if (description != null) {
                    Modifier.clearAndSetSemantics {
                        contentDescription = description
                        role = Role.Button
                        onClick {
                            onClick()
                            true
                        }
                    }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (dropdown) "$text ▾" else text, color = Color(0xFFC9CACF), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
    }
}

/** "52 songs" on the left, an optional pill on the right. */
@Composable
fun ListHeader(text: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp).heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = SeionType.RowSub, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** The level bars' heights while still (paused, reduced motion, under Now playing). */
private val LEVEL_STILL = floatArrayOf(0.5f, 0.9f, 0.35f)

/**
 * Three animated level bars marking the playing row (still when paused, with reduced motion or under Now playing).
 * They step 8 times a second ([SteppedMotion.LEVEL_STEP_MS]; every vsync would keep a whole list at 60 fps).
 * The heights are read in the draw phase only: no recomposition or relayout per step (and no accessibility churn).
 */
@Composable
fun LevelBars(playing: Boolean, color: Color) {
    val reduce = LocalReduceMotion.current
    val animate = playing && !reduce && !LocalCoveredByNowPlaying.current
    val clock = rememberSteppedClock(animate, SteppedMotion.LEVEL_STEP_MS)
    androidx.compose.foundation.Canvas(Modifier.width(11.5.dp).height(12.dp)) {
        val w = 2.5.dp.toPx()
        val gap = 2.dp.toPx()
        val t = clock.longValue
        for (i in 0..2) {
            val h = size.height * (if (animate) SteppedMotion.levelHeight(t, i) else LEVEL_STILL[i])
            drawRect(
                color,
                topLeft = androidx.compose.ui.geometry.Offset(i * (w + gap), size.height - h),
                size = androidx.compose.ui.geometry.Size(w, h)
            )
        }
    }
}

/** A track row: cover (or number), title, subtitle, duration; the playing track is accented. */
@Composable
fun TrackRow(
    title: String,
    subtitle: String?,
    durationMs: Long,
    tag: String,
    modifier: Modifier = Modifier,
    artRef: ArtRef? = null,
    number: Int? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    /** The artist subtitle behind [subtitle] ([artistSubtitle]): what TalkBack reads for it (every artist). */
    subtitleArtists: RowSubtitle? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Touch and hold (Add to playlist); TalkBack offers it as the row's long-press action, [onLongClickLabel]. */
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    /** Null: nothing to tap (Edit order): the row is still one TalkBack stop, without a "double-tap" that does nothing. */
    onClick: (() -> Unit)?
) {
    val accent = LocalAccent.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (onClick !=
                    null
                ) {
                    Modifier.rowClick(onClick, onLongClick, onLongClickLabel)
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                }
            )
            .padding(start = if (leading != null) 4.dp else 16.dp, end = if (trailing != null) 4.dp else 16.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        if (number != null) {
            Text(
                number.toString(),
                style = SeionType.RowMeta,
                modifier = Modifier.width(26.dp).padding(end = 4.dp),
                maxLines = 1
            )
        } else if (artRef != null) {
            Cover(artRef, 40.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            // The whole column, not the text's own measured width: a right-to-left line (Arabic) can shape a little
            // wider than that measurement and was cut with "…" with room to spare.
            Text(
                title,
                style = SeionType.RowTitle,
                color = if (isCurrent) accent else SeionColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            if (!subtitle.isNullOrEmpty()) {
                // "A, B, C · Album", cut with "…" (rows don't scroll); TalkBack reads every artist (ArtistLine.kt) as
                // the line's text, so the row stays one stop that reads the title first.
                val spoken = subtitleArtists?.description
                Text(
                    subtitle,
                    style = SeionType.RowSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .then(if (spoken != null) Modifier.clearAndSetSemantics { text = AnnotatedString(spoken) } else Modifier)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (isCurrent) {
            LevelBars(isPlaying, accent)
            Spacer(Modifier.width(6.dp))
        }
        if (durationMs > 0) Text(Format.duration(durationMs), style = SeionType.RowMeta, maxLines = 1)
        trailing?.invoke()
    }
}

/** A row's tap, plus touch-and-hold when there is one (the same ripple and semantics either way). */
fun Modifier.rowClick(onClick: () -> Unit, onLongClick: (() -> Unit)?, onLongClickLabel: String?, enabled: Boolean = true): Modifier =
    if (onLongClick == null) {
        clickable(enabled = enabled, onClick = onClick)
    } else {
        combinedClickable(enabled = enabled, onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, onClick = onClick)
    }

@Composable
fun trackSubtitle(t: TrackEntity): RowSubtitle = artistSubtitle(t.artist ?: stringResource(R.string.unknown_artist), t.albumTitle)

@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp).testTag("empty_state"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(painterResource(icon), null, tint = SeionColors.Faint, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = SeionType.RowTitle, maxLines = 2)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = SeionType.Body)
        }
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/** Breadcrumbs under the app bar: `Internal › Music › Album`; the last crumb is current. */
@Composable
fun Breadcrumbs(crumbs: List<String>, onCrumb: (Int) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs.size) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).horizontalScroll(scroll).padding(horizontal = 12.dp).testTag("breadcrumbs"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        crumbs.forEachIndexed { i, c ->
            val current = i == crumbs.lastIndex
            Text(
                c,
                fontSize = 12.5.sp,
                color = if (current) SeionColors.Ink else SeionColors.Muted,
                fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                modifier =
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .then(if (current) Modifier.background(Color(0x17FFFFFF)) else Modifier)
                    .clickable(enabled = !current) { onCrumb(i) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .testTag("crumb_$i")
            )
            if (!current) Text("›", color = Color(0xFF5D5F66), fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 1.dp))
        }
    }
}
