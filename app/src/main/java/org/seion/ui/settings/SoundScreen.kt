package org.seion.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.seion.R
import org.seion.playback.dsp.ReplayGain
import org.seion.ui.components.LocalContainer
import org.seion.ui.components.LocalReduceMotion
import org.seion.ui.components.SectionLabel
import org.seion.ui.library.BrowseScaffold
import org.seion.ui.library.bottomPadding
import org.seion.ui.library.rememberEntryListState
import org.seion.ui.theme.Roboto
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionType

/**
 * The two sound technologies. The string resources hold the names shown to the user and their descriptions; the DSP
 * classes are `CrystalX`, `CutoffDetector` and `RenovaPure`.
 */
enum class SoundBrand(
    @StringRes val nameRes: Int,
    @StringRes val summaryRes: Int,
    @StringRes val aboutTitleRes: Int,
    @StringRes val aboutBodyRes: Int,
    @DrawableRes val emblemRes: Int,
    /** Test tag of the card's switch row; `_switch`, `_info` and `_emblem` are derived from it. */
    val tag: String,
    /** The part of the wordmark set in the family violet (the technology's mark). */
    val accentSuffix: String
) {
    CRYSTALX(
        R.string.crystalx_name,
        R.string.crystalx_summary,
        R.string.crystalx_about_title,
        R.string.crystalx_about_body,
        R.drawable.ic_brand_crystalx,
        "sound_crystalx",
        "X+"
    ),
    RENOVAPURE(
        R.string.renovapure_name,
        R.string.renovapure_summary,
        R.string.renovapure_about_title,
        R.string.renovapure_about_body,
        R.drawable.ic_brand_renovapure,
        "sound_renovapure",
        "Pure"
    )
}

/** The emblem's opacity as drawn (1 when on; dimmed, never hidden, when off). Exposed for the tests. */
val EmblemAlpha = SemanticsPropertyKey<Float>("EmblemAlpha")
private var SemanticsPropertyReceiver.emblemAlpha by EmblemAlpha

/** Off: the emblem keeps its shape but drops to this opacity and most of its colour (a subtle change). */
internal const val EMBLEM_OFF_ALPHA = 0.55f
private const val EMBLEM_OFF_SATURATION = 0.35f

/** The family violet of the launcher icon's first echo (#AFAAE8), used for the wordmark's mark. */
private val BrandViolet = Color(0xFFAFAAE8)

private val Wordmark = TextStyle(fontFamily = Roboto, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp, color = SeionColors.Ink)

@Composable
fun SoundScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val s by c.settingsState.collectAsStateWithLifecycle()
    val repo = c.settings
    // rememberSaveable: the open dialog survives an activity re-creation, like the rest of the screen's state.
    var about by rememberSaveable { mutableStateOf<String?>(null) }
    SoundBrand.entries.firstOrNull { it.name == about }?.let { brand ->
        AlertDialog(
            onDismissRequest = { about = null },
            title = { Text(stringResource(brand.aboutTitleRes)) },
            // The full story is long at large text sizes: it scrolls inside the dialog instead of being cut.
            text = {
                Text(
                    stringResource(brand.aboutBodyRes),
                    modifier = Modifier.verticalScroll(rememberScrollState()).testTag("${brand.tag}_info_text")
                )
            },
            confirmButton = {
                // A dialog is its own window: expose its test tag as a resource id there too (adb_ui.py).
                TextButton(
                    onClick = { about = null },
                    modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("${brand.tag}_info_ok")
                ) {
                    Text(stringResource(R.string.sound_about_ok))
                }
            }
        )
    }
    BrowseScaffold(stringResource(R.string.sound), showSearch = false) {
        LazyColumn(Modifier.fillMaxSize(), state = rememberEntryListState(), contentPadding = bottomPadding()) {
            item {
                SoundCard(
                    SoundBrand.CRYSTALX,
                    checked = s.crystalX,
                    onChange = { scope.launch { repo.setCrystalX(it) } },
                    onInfo = { about = SoundBrand.CRYSTALX.name },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
                )
            }
            item {
                SoundCard(
                    SoundBrand.RENOVAPURE,
                    checked = s.renovaPure,
                    onChange = { scope.launch { repo.setRenovaPure(it) } },
                    onInfo = { about = SoundBrand.RENOVAPURE.name },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp)
                )
            }
            item {
                Text(
                    stringResource(R.string.sound_bypass_note),
                    style = SeionType.RowSub,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            replayGainItems(s.replayGain, s.replayGainAlbum, s.replayGainPreampDb, repo, scope)
            item { SectionLabel(stringResource(R.string.settings_playback)) }
            item {
                SwitchRow(stringResource(R.string.gapless), stringResource(R.string.gapless_summary), s.gapless, "set_gapless") {
                    scope.launch { repo.setGapless(it) }
                }
            }
        }
    }
}

/**
 * One technology: the emblem and wordmark lock-up with its switch (the whole row toggles), then the description and an
 * info button for the full story. Stateless; the emblem dims when the effect is off.
 */
@Composable
internal fun SoundCard(
    brand: SoundBrand,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reduce = LocalReduceMotion.current
    val alpha by animateFloatAsState(
        if (checked) 1f else EMBLEM_OFF_ALPHA,
        if (reduce) snap() else tween(220),
        label = "emblemAlpha"
    )
    val saturation = EMBLEM_OFF_SATURATION + (1f - EMBLEM_OFF_SATURATION) * ((alpha - EMBLEM_OFF_ALPHA) / (1f - EMBLEM_OFF_ALPHA))
    val name = stringResource(brand.nameRes)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SeionColors.Panel)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .toggleable(checked, role = Role.Switch, onValueChange = onChange)
                .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)
                .testTag(brand.tag),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painterResource(brand.emblemRes),
                contentDescription = null,
                colorFilter = if (saturation <
                    0.999f
                ) {
                    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
                } else {
                    null
                },
                modifier = Modifier
                    .size(48.dp)
                    .alpha(alpha)
                    .semantics { emblemAlpha = alpha }
                    .testTag("${brand.tag}_emblem")
            )
            Spacer(Modifier.width(12.dp))
            BasicText(
                buildAnnotatedString {
                    val accentAt = name.lastIndexOf(brand.accentSuffix).takeIf { it > 0 } ?: name.length
                    val seion = if (name.startsWith("Seion ")) "Seion " else ""
                    withStyle(SpanStyle(fontWeight = FontWeight.Light, color = SeionColors.Secondary)) { append(seion) }
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(name.substring(seion.length, accentAt)) }
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = BrandViolet)) { append(name.substring(accentAt)) }
                },
                style = Wordmark,
                maxLines = 1,
                // A lock-up stays on one line next to its emblem: it shrinks to fit rather than wrap or end in "…".
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 19.sp, stepSize = 0.5.sp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = SeionColors.Accent, checkedThumbColor = Color(0xFF1A1A1A)),
                modifier = Modifier.testTag("${brand.tag}_switch")
            )
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Text(
                stringResource(brand.summaryRes),
                style = SeionType.RowSub.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SeionColors.Secondary),
                modifier = Modifier.weight(1f).padding(top = 6.dp, bottom = 6.dp)
            )
            IconButton(onClick = onInfo, modifier = Modifier.testTag("${brand.tag}_info")) {
                Icon(
                    painterResource(R.drawable.ic_info),
                    contentDescription = stringResource(brand.aboutTitleRes),
                    tint = SeionColors.Muted
                )
            }
        }
    }
}

/**
 * ReplayGain: the switch, then while it is on the mode (Album, the default, or Track) and the
 * pre-amp (−6…+6 dB, whole steps), and a note on untagged songs and the limiter.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.replayGainItems(
    on: Boolean,
    album: Boolean,
    preampDb: Int,
    repo: org.seion.settings.SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    item { SectionLabel(stringResource(R.string.replaygain_section)) }
    item {
        SwitchRow(stringResource(R.string.replaygain), stringResource(R.string.replaygain_summary), on, "sound_replaygain") {
            scope.launch { repo.setReplayGain(it) }
        }
    }
    if (on) {
        item {
            DialogOption(
                stringResource(R.string.replaygain_album),
                stringResource(R.string.replaygain_album_summary),
                album,
                "sound_replaygain_album",
                onClick = { scope.launch { repo.setReplayGainAlbum(true) } }
            )
        }
        item {
            DialogOption(
                stringResource(R.string.replaygain_track),
                stringResource(R.string.replaygain_track_summary),
                !album,
                "sound_replaygain_track",
                onClick = { scope.launch { repo.setReplayGainAlbum(false) } }
            )
        }
        item { PreampRow(preampDb) { db -> scope.launch { repo.setReplayGainPreamp(db) } } }
    }
    item {
        Text(
            stringResource(R.string.replaygain_note),
            style = SeionType.RowSub,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/** "+2 dB" (a minus sign for cuts, "0 dB" at zero). */
internal fun signedDb(db: Int): String = when {
    db > 0 -> "+$db"
    db < 0 -> "\u2212${-db}"
    else -> "0"
}

/** The pre-amp: title and value, and a slider with one stop per dB; the value is saved when the finger lifts. */
@Composable
private fun PreampRow(preampDb: Int, onChange: (Int) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging?.let { kotlin.math.round(it).toInt() } ?: preampDb
    val value = stringResource(R.string.replaygain_preamp_value, signedDb(shown))
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.replaygain_preamp), style = SeionType.RowTitle, modifier = Modifier.weight(1f))
            Text(value, style = SeionType.RowSub, modifier = Modifier.testTag("sound_replaygain_preamp_value"))
        }
        val title = stringResource(R.string.replaygain_preamp)
        Slider(
            value = dragging ?: preampDb.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onChange(kotlin.math.round(it).toInt()) }
                dragging = null
            },
            valueRange = ReplayGain.PREAMP_MIN_DB.toFloat()..ReplayGain.PREAMP_MAX_DB.toFloat(),
            steps = ReplayGain.PREAMP_MAX_DB - ReplayGain.PREAMP_MIN_DB - 1,
            colors = SliderDefaults.colors(thumbColor = SeionColors.Accent, activeTrackColor = SeionColors.Accent),
            modifier = Modifier
                .semantics { contentDescription = title }
                .testTag("sound_replaygain_preamp")
        )
    }
}
