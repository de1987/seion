package org.seion.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.widget.Toast
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import org.seion.BuildConfig
import org.seion.R
import org.seion.art.FindCoversStatus
import org.seion.art.MissingCoversWorker
import org.seion.library.ScanDay
import org.seion.library.ScanTime
import org.seion.library.listFolder
import org.seion.settings.AppLanguage
import org.seion.settings.BackgroundStyle
import org.seion.settings.LocaleContext
import org.seion.settings.UiLanguage
import org.seion.settings.languageStore
import org.seion.ui.Screen
import org.seion.ui.ambient.Ambient
import org.seion.ui.ambient.AmbientRepository
import org.seion.ui.ambient.CoverGeometry
import org.seion.ui.ambient.LocalAmbient
import org.seion.ui.ambient.NowPlayingBackground
import org.seion.ui.components.Breadcrumbs
import org.seion.ui.components.CoverBitmap
import org.seion.ui.components.EmptyState
import org.seion.ui.components.IconBtn
import org.seion.ui.components.LocalContainer
import org.seion.ui.components.LocalReduceMotion
import org.seion.ui.components.Pill
import org.seion.ui.library.BrowseScaffold
import org.seion.ui.library.FolderRow
import org.seion.ui.library.LocalBrowse
import org.seion.ui.library.bottomPadding
import org.seion.ui.library.rememberEntryListState
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionType

/** The label of each background style in the dialog and under "Background style". */
internal val BackgroundStyle.label: Int get() = when (this) {
    BackgroundStyle.AMBIENT -> R.string.background_ambient
    BackgroundStyle.CLASSIC -> R.string.background_classic
    BackgroundStyle.ONYX -> R.string.background_onyx
}

private val BackgroundStyle.description: Int get() = when (this) {
    BackgroundStyle.AMBIENT -> R.string.background_ambient_description
    BackgroundStyle.CLASSIC -> R.string.background_classic_description
    BackgroundStyle.ONYX -> R.string.background_onyx_description
}

/**
 * The background style choices (the dialog's options, in [BackgroundStyle]'s order): radio, name, what it does and a
 * live preview of Now playing in that style with the current cover ([LocalAmbient]; still, never drifting).
 */
@Composable
fun BackgroundStyleOptions(selected: BackgroundStyle, ambient: Ambient? = LocalAmbient.current, onSelect: (BackgroundStyle) -> Unit) {
    Column {
        for (style in BackgroundStyle.entries) {
            DialogOption(
                stringResource(style.label),
                stringResource(style.description),
                selected == style,
                "set_bg_${style.name.lowercase()}",
                onClick = { onSelect(style) }
            ) { BackgroundPreview(style, ambient) }
        }
    }
}

/**
 * A 40 × 64 dp Now playing in [style]: the real background composable at thumbnail size, with the cover where Now
 * playing puts it (scaled); without a track, the no-cover look.
 */
@Composable
internal fun BackgroundPreview(style: BackgroundStyle, ambient: Ambient?) {
    val geometry = CoverGeometry(left = 9.dp, top = 12.dp, size = 22.dp)
    Box(
        Modifier
            .size(40.dp, 64.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SeionColors.Page)
            .clearAndSetSemantics { }
            .testTag("set_bg_preview_${style.name.lowercase()}")
    ) {
        NowPlayingBackground(ambient, style, geometry, drifting = false, statusBarHeight = 0.dp)
        // The no-cover ambient's bitmap is its field, never shown: the tile is drawn, as on Now playing.
        val cover = ambient?.cover?.takeIf { ambient.key != AmbientRepository.NO_COVER_KEY }
        CoverBitmap(cover, Modifier.offset(geometry.left, geometry.top).size(geometry.size), corner = 2.dp)
    }
}

@Composable
private fun BackgroundStyleDialog(selected: BackgroundStyle, onSelect: (BackgroundStyle) -> Unit, onDismiss: () -> Unit) {
    SettingsDialog(
        stringResource(R.string.background_style),
        stringResource(R.string.background_style_dialog_description),
        stringResource(R.string.cancel),
        "set_bg_dialog",
        onDismiss
    ) {
        BackgroundStyleOptions(selected) {
            onSelect(it)
            onDismiss()
        }
    }
}

/** Where covers come from, as the Album art row says it: "Embedded · folder images · online on Wi-Fi". */
@Composable
internal fun albumArtSummary(folderImages: Boolean, online: Boolean, wifiOnly: Boolean): String = listOfNotNull(
    stringResource(R.string.art_summary_embedded),
    if (folderImages) stringResource(R.string.art_summary_folder) else null,
    if (online) stringResource(if (wifiOnly) R.string.art_summary_online_wifi else R.string.art_summary_online) else null
).joinToString(" · ")

/** "Last scan today 09:12 · 5052 songs" (or "Scanning…", or what Rescan does before the first scan). */
@Composable
private fun rescanSubtitle(scanning: Boolean, lastScanAt: Long?, songs: Int): String {
    if (scanning) return stringResource(R.string.scanning)
    if (lastScanAt == null) return stringResource(R.string.rescan_now_summary)
    val context = LocalContext.current
    val locale = LocalResources.current.configuration.locales[0]
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(lastScanAt).atZone(zone)
    val hm = DateTimeFormatter.ofPattern(
        DateFormat.getBestDateTimePattern(locale, if (DateFormat.is24HourFormat(context)) "Hm" else "hm"),
        locale
    )
    val whenText =
        when (ScanTime.day(lastScanAt, System.currentTimeMillis(), zone)) {
            ScanDay.TODAY -> stringResource(R.string.last_scan_today, hm.format(time))
            ScanDay.YESTERDAY -> stringResource(R.string.last_scan_yesterday, hm.format(time))
            ScanDay.EARLIER ->
                stringResource(R.string.last_scan_on, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(time))
        }
    return whenText + " · " + pluralStringResource(R.plurals.n_songs, songs, songs)
}

/**
 * Settings: Library, Now playing, Album art (a sub-screen), Playback, Language and About, each
 * row with its icon on the 54 dp keyline. Background style and Language open a dialog.
 */
@Composable
fun SettingsScreen() {
    val c = LocalContainer.current
    val ui = LocalContext.current
    val b = LocalBrowse.current
    val scope = rememberCoroutineScope()
    val s by c.settingsState.collectAsStateWithLifecycle()
    val scanning by c.scans.isScanning.collectAsStateWithLifecycle(false)
    val lastScanAt by c.scanStamp.lastScanAt.collectAsStateWithLifecycle()
    val songs by remember(s.minDurationMs) { c.db.tracks().songCount(s.minDurationMs) }.collectAsStateWithLifecycle(0)
    val repo = c.settings
    val folders = c.storageRoots.effectiveFolders(s.musicFolders)
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    when (dialog) {
        DIALOG_BACKGROUND -> BackgroundStyleDialog(s.backgroundStyle, { scope.launch { repo.setBackgroundStyle(it) } }) { dialog = null }
        DIALOG_LANGUAGE -> LanguageDialog { dialog = null }
    }
    BrowseScaffold(stringResource(R.string.settings), showSearch = false) {
        LazyColumn(Modifier.fillMaxSize(), state = rememberEntryListState(), contentPadding = bottomPadding(12.dp)) {
            item { SettingsSection(stringResource(R.string.settings_library), first = true) }
            item {
                SettingRow(
                    stringResource(R.string.music_folders),
                    folders.joinToString(" · ") { c.storageRoots.labelFor(it, ui) }.ifEmpty { stringResource(R.string.no_music_folders) },
                    "set_folders",
                    icon = R.drawable.ic_folder,
                    onClick = { b.go(Screen.MusicFolders) }
                )
            }
            item {
                SettingRow(
                    stringResource(R.string.rescan_now),
                    rescanSubtitle(scanning, lastScanAt, songs),
                    "set_rescan",
                    icon = R.drawable.ic_set_refresh,
                    onClick = { b.rescan() }
                )
            }
            item { IdentifyAgainRow(enabled = s.onlineCovers) }
            item {
                SwitchRow(
                    stringResource(R.string.ignore_short),
                    stringResource(R.string.ignore_short_summary),
                    s.ignoreShortTracks,
                    "set_ignore_short",
                    icon = R.drawable.ic_set_timer
                ) {
                    scope.launch { repo.setIgnoreShortTracks(it) }
                }
            }
            item { SettingsSection(stringResource(R.string.settings_now_playing)) }
            item {
                SettingRow(
                    stringResource(R.string.background_style),
                    stringResource(s.backgroundStyle.label),
                    "set_background",
                    icon = R.drawable.ic_set_palette,
                    onClick = { dialog = DIALOG_BACKGROUND }
                )
            }
            item {
                SwitchRow(
                    stringResource(R.string.ambient_drift),
                    stringResource(R.string.ambient_drift_summary),
                    s.ambientDrift,
                    "set_drift",
                    enabled = s.backgroundStyle == BackgroundStyle.AMBIENT,
                    icon = R.drawable.ic_set_motion
                ) { scope.launch { repo.setAmbientDrift(it) } }
            }
            item {
                // Spectrum band: off by default; unavailable while the system removes animations.
                val reduce = LocalReduceMotion.current
                SwitchRow(
                    stringResource(R.string.spectrum),
                    stringResource(if (reduce) R.string.spectrum_reduced_motion else R.string.spectrum_summary),
                    s.spectrum && !reduce,
                    "set_spectrum",
                    enabled = !reduce,
                    icon = R.drawable.ic_set_wave
                ) { scope.launch { repo.setSpectrum(it) } }
            }
            item { SettingsSection(stringResource(R.string.settings_album_art)) }
            item {
                SettingRow(
                    stringResource(R.string.settings_album_art),
                    albumArtSummary(s.useFolderImages, s.onlineCovers, s.onlineWifiOnly),
                    "set_album_art",
                    icon = R.drawable.ic_set_image,
                    onClick = { b.go(Screen.AlbumArtSettings) }
                )
            }
            // Playback: the Sound screen (CrystalX+, RenovaPure, gapless) and the fixed headphones rule.
            item { SettingsSection(stringResource(R.string.settings_playback)) }
            item {
                SettingRow(
                    stringResource(
                        R.string.settings_sound_row,
                        stringResource(R.string.crystalx_name),
                        stringResource(R.string.renovapure_name)
                    ),
                    stringResource(R.string.settings_sound_row_summary),
                    "set_sound",
                    icon = R.drawable.ic_set_sliders,
                    onClick = { b.go(Screen.Sound) }
                )
            }
            item {
                SettingRow(
                    stringResource(R.string.pause_on_disconnect),
                    stringResource(R.string.pause_on_disconnect_summary),
                    "set_noisy",
                    icon = R.drawable.ic_set_headphones,
                    onClick = null
                ) {
                    Icon(painterResource(R.drawable.ic_lock), null, tint = SeionColors.Faint, modifier = Modifier.size(18.dp))
                }
            }
            item { SettingsSection(stringResource(R.string.settings_language)) }
            item { LanguageRow { dialog = DIALOG_LANGUAGE } }
            item { SettingsSection(stringResource(R.string.settings_about)) }
            item {
                SettingRow(
                    stringResource(R.string.about_brand),
                    stringResource(
                        R.string.about_version_line,
                        stringResource(R.string.about_brand_meaning),
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                    ),
                    "set_brand",
                    icon = R.drawable.ic_set_info,
                    onClick = null
                )
            }
            item {
                SettingRow(
                    stringResource(R.string.licenses),
                    stringResource(R.string.licenses_summary),
                    "set_licenses",
                    icon = R.drawable.ic_set_doc,
                    onClick = { b.go(Screen.Licenses) }
                )
            }
            item {
                val context = LocalContext.current
                SettingRow(
                    stringResource(R.string.source_code),
                    stringResource(R.string.source_code_summary, BuildConfig.PROJECT_URL),
                    "set_source",
                    icon = R.drawable.ic_set_code,
                    onClick = {
                        if (!openUrl(context, BuildConfig.PROJECT_URL)) {
                            Toast.makeText(context, R.string.no_browser, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

/** Opens [url] in the browser ([Intent.ACTION_VIEW]); false, and nothing else happens, when no app can open it. */
internal fun openUrl(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private const val DIALOG_BACKGROUND = "background"
private const val DIALOG_LANGUAGE = "language"

/**
 * "Identify untagged tracks again": the tracks looked up without a match go back to pending. Enabled
 * only with the online switch on and something to retry.
 */
@Composable
private fun IdentifyAgainRow(enabled: Boolean) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val pending by remember { c.db.tracks().notIdentifiedCount() }.collectAsStateWithLifecycle(0)
    SettingRow(
        stringResource(R.string.identify_again),
        if (pending > 0) pluralStringResource(R.plurals.identify_pending, pending, pending) else stringResource(R.string.identify_none),
        "set_identify_again",
        icon = R.drawable.ic_set_identify,
        enabled = enabled && pending > 0,
        onClick = { scope.launch { c.identifyAgain() } }
    )
}

/**
 * "Find missing covers again": forgets the remembered online misses and searches again, in the background, for every
 * album that still has no cover. Enabled only with the online switch on; a tap while a run is in progress stops it.
 */
@Composable
internal fun FindCoversAgain(enabled: Boolean) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by remember { MissingCoversWorker.status(context) }.collectAsStateWithLifecycle(FindCoversStatus.Idle)
    FindCoversAgainRow(
        enabled,
        status,
        onStart = {
            scope.launch { c.findCoversAgain() }
            Toast.makeText(context, R.string.find_covers_started, Toast.LENGTH_SHORT).show()
        },
        onStop = { MissingCoversWorker.stop(context) }
    )
}

/**
 * The row itself (stateless, tested): the subtitle says what a run does, how far it got (with a progress bar under
 * it), or how it ended. The row is the only control: a tap starts a run, a tap while one runs stops it (TalkBack
 * announces "Stop searching"); there is no separate stop button.
 */
@Composable
internal fun FindCoversAgainRow(enabled: Boolean, status: FindCoversStatus, onStart: () -> Unit, onStop: () -> Unit) {
    val running = status is FindCoversStatus.Running
    val stopLabel = stringResource(R.string.find_covers_stop_action)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = if (running) stopLabel else null,
                onClick = if (running) onStop else onStart
            ).padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("set_find_covers_again"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(R.drawable.ic_set_cover_search),
            null,
            tint = if (enabled) SettingsIconTint else SeionColors.Faint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.find_covers_again),
                style = SeionType.RowTitle,
                color = if (enabled) SeionColors.Ink else SeionColors.Faint
            )
            Text(findCoversSubtitle(status), style = SettingsSub, modifier = Modifier.padding(top = 2.dp))
            if (status is FindCoversStatus.Running) {
                val description = stringResource(R.string.find_covers_progress_description)
                val bar =
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .semantics { contentDescription = description }
                        .testTag("find_covers_progress")
                if (status.total > 0) {
                    val fraction = status.processed.toFloat() / status.total
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = bar,
                        color = SeionColors.Accent,
                        trackColor = SeionColors.Line,
                        drawStopIndicator = {}
                    )
                } else {
                    LinearProgressIndicator(modifier = bar, color = SeionColors.Accent, trackColor = SeionColors.Line)
                }
            }
        }
    }
}

@Composable
private fun findCoversSubtitle(status: FindCoversStatus): String {
    @Composable
    fun summary(prefix: Int, found: Int, missing: Int): String {
        val parts = mutableListOf(stringResource(prefix))
        parts +=
            if (found >
                0
            ) {
                pluralStringResource(R.plurals.find_covers_found, found, found)
            } else {
                stringResource(R.string.find_covers_none_found)
            }
        if (missing > 0) parts += pluralStringResource(R.plurals.find_covers_missing, missing, missing)
        return parts.joinToString(" · ")
    }
    return when (status) {
        FindCoversStatus.Idle -> stringResource(R.string.find_covers_again_summary)
        is FindCoversStatus.Running ->
            if (status.total < 0) {
                stringResource(R.string.find_covers_running)
            } else {
                stringResource(
                    R.string.find_covers_progress,
                    pluralStringResource(R.plurals.find_covers_progress_albums, status.total, status.processed, status.total),
                    if (status.found > 0) {
                        pluralStringResource(R.plurals.find_covers_progress_found, status.found, status.found)
                    } else {
                        stringResource(R.string.find_covers_progress_none_found)
                    }
                )
            }
        is FindCoversStatus.Done -> summary(R.string.find_covers_done, status.found, status.missing)
        is FindCoversStatus.Stopped -> summary(R.string.find_covers_stopped, status.found, status.missing)
    }
}

/**
 * Follow system / English / Português (Brasil): the store's value, re-read on every resume so a change
 * made in the system's per-app language setting shows here too.
 */
@Composable
private fun rememberLanguageChoice(): Pair<AppLanguage, (AppLanguage) -> Unit> {
    val context = LocalContext.current
    val store = remember(context) { languageStore(context) }
    var choice by remember { mutableStateOf(store.get()) }
    LifecycleResumeEffect(store) {
        choice = store.get()
        onPauseOrDispose { }
    }
    return choice to { value ->
        if (value != choice) {
            choice = value
            store.set(value)
            // 33+: the system persists the per-app locale and recreates the activity itself; 31-32 recreate here.
            if (Build.VERSION.SDK_INT < 33) context.findActivity()?.recreate()
        }
    }
}

/** "System default (English)": what follow-the-system resolves to right now. */
@Composable
private fun systemLanguageLabel(): String {
    val context = LocalContext.current
    val resolved = UiLanguage.resolve(AppLanguage.SYSTEM, LocaleContext.systemPrimary(context))
    val systemName = stringResource(if (resolved == UiLanguage.PT_BR) R.string.language_pt_br else R.string.language_english)
    return stringResource(R.string.language_follow_system_current, systemName)
}

@Composable
private fun AppLanguage.label(): String = when (this) {
    AppLanguage.SYSTEM -> systemLanguageLabel()
    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
    AppLanguage.PORTUGUESE_BR -> stringResource(R.string.language_pt_br)
}

/** Settings › Language: the current choice; opens [LanguageDialog]. */
@Composable
private fun LanguageRow(onClick: () -> Unit) {
    val (choice, _) = rememberLanguageChoice()
    SettingRow(
        stringResource(R.string.settings_language),
        choice.label(),
        "set_language",
        icon = R.drawable.ic_set_language,
        onClick = onClick
    )
}

/** The three choices as radio options (tags `set_lang_system`, `set_lang_en`, `set_lang_pt`); a pick applies at once. */
@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val (choice, pick) = rememberLanguageChoice()
    SettingsDialog(stringResource(R.string.settings_language), null, stringResource(R.string.cancel), "set_lang_dialog", onDismiss) {
        for ((value, tag) in listOf(
            AppLanguage.SYSTEM to "set_lang_system",
            AppLanguage.ENGLISH to "set_lang_en",
            AppLanguage.PORTUGUESE_BR to "set_lang_pt"
        )) {
            DialogOption(value.label(), null, choice == value, tag, onClick = {
                onDismiss()
                pick(value)
            })
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The scan roots: remove one, add one with the folder picker, or go back to the defaults. */
@Composable
fun MusicFoldersScreen() {
    val c = LocalContainer.current
    val ui = LocalContext.current
    val b = LocalBrowse.current
    val scope = rememberCoroutineScope()
    val s by c.settingsState.collectAsStateWithLifecycle()
    val folders = c.storageRoots.effectiveFolders(s.musicFolders)
    BrowseScaffold(stringResource(R.string.music_folders), showSearch = false) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomPadding()) {
            if (s.musicFolders ==
                null
            ) {
                item {
                    Text(stringResource(R.string.default_folders_note), style = SeionType.RowSub, modifier = Modifier.padding(16.dp))
                }
            }
            itemsIndexed(folders) { i, f ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.storageRoots.labelFor(f, ui), style = SeionType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(f, style = SeionType.RowSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconBtn(
                        org.seion.R.drawable.ic_close,
                        stringResource(R.string.remove_folder),
                        "folder_remove_$i",
                        tint = SeionColors.Muted
                    ) {
                        scope.launch { c.settings.setMusicFolders(folders - f) }
                    }
                }
            }
            item {
                Row(Modifier.padding(16.dp)) {
                    Pill(stringResource(R.string.add_folder), "folders_add", solid = true, icon = R.drawable.ic_add) {
                        b.go(Screen.FolderPicker(null))
                    }
                    Spacer(Modifier.width(8.dp))
                    if (s.musicFolders != null) {
                        Pill(stringResource(R.string.reset_defaults), "folders_reset") {
                            scope.launch { c.settings.setMusicFolders(c.storageRoots.defaultFolders()) }
                        }
                    }
                }
            }
        }
    }
}

/** Picks a folder on the real file tree: volumes first, then subfolders, "Use this folder" adds it. */
@Composable
fun FolderPickerScreen(path: String?) {
    val c = LocalContainer.current
    val ui = LocalContext.current
    val b = LocalBrowse.current
    val scope = rememberCoroutineScope()
    val s by c.settingsState.collectAsStateWithLifecycle()
    val volumes = c.storageRoots.volumes(ui)
    BrowseScaffold(stringResource(R.string.choose_folder), showSearch = false) {
        if (path == null) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomPadding()) {
                itemsIndexed(volumes) { i, v ->
                    FolderRow(
                        v.label,
                        v.dir.absolutePath,
                        if (v.removable) R.drawable.ic_sd else R.drawable.ic_storage,
                        "picker_volume_$i"
                    ) {
                        b.go(Screen.FolderPicker(v.dir.absolutePath))
                    }
                }
            }
            return@BrowseScaffold
        }
        val vol = volumes.filter { path.startsWith(it.dir.absolutePath) }.maxByOrNull { it.dir.absolutePath.length }
        val root = vol?.dir?.absolutePath ?: path
        val rel = path.removePrefix(root).trim('/').split('/').filter { it.isNotEmpty() }
        Breadcrumbs(listOf(vol?.label ?: root) + rel) { i ->
            val target = if (i == 0) root else root.trimEnd('/') + "/" + rel.take(i).joinToString("/")
            if (!b.vm.popTo { it is Screen.FolderPicker && it.path == target }) b.vm.replaceTop(Screen.FolderPicker(target))
        }
        val dirs = androidx.compose.runtime.remember(path) { listFolder(File(path)).folders }
        Box(Modifier.weight(1f)) {
            if (dirs.isEmpty()) EmptyState(R.drawable.ic_folder, stringResource(R.string.no_subfolders), null)
            LazyColumn(Modifier.fillMaxSize()) {
                items(dirs, key = { it.absolutePath }) { d ->
                    FolderRow(d.name, null, R.drawable.ic_folder, "picker_dir_${d.name}") { b.go(Screen.FolderPicker(d.absolutePath)) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(
                SeionColors.Panel
            ).padding(bottomPadding(12.dp)).padding(top = 12.dp, start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(path, style = SeionType.RowSub, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Pill(stringResource(R.string.use_this_folder), "picker_use", solid = true) {
                scope.launch {
                    val current = c.storageRoots.effectiveFolders(s.musicFolders)
                    c.settings.setMusicFolders((current + path).distinct())
                    b.vm.popTo { it == Screen.MusicFolders } || b.vm.pop()
                }
            }
        }
    }
}

/**
 * Every bundled third-party component with its licence, checked against `releaseRuntimeClasspath` (each artifact's
 * POM, or the licence file inside it). The FFmpeg decoder is listed only when it is built in.
 */
internal fun licenses(hasFfmpeg: Boolean = BuildConfig.HAS_FFMPEG): List<Pair<Int, Int>> = listOfNotNull(
    R.string.license_androidx to R.string.license_apache,
    R.string.license_flatbuffers to R.string.license_apache,
    R.string.license_kotlin to R.string.license_apache,
    R.string.license_okhttp to R.string.license_apache,
    R.string.license_guava to R.string.license_apache,
    R.string.license_annotations to R.string.license_apache,
    R.string.license_checker to R.string.license_mit,
    R.string.license_protobuf to R.string.license_bsd3,
    R.string.license_psl to R.string.license_mpl2,
    if (hasFfmpeg) R.string.license_ffmpeg to R.string.license_gpl3 else null,
    R.string.license_roboto to R.string.license_ofl
)

/**
 * The attribution notices of NOTICE files shipped by the bundled Apache-2.0 components (Apache License 2.0, section
 * 4(d)), copied from the artifacts: `androidx.concurrent:concurrent-futures-ktx` carries kotlinx.coroutines' NOTICE.
 */
internal val apacheNotices: List<Int> = listOf(R.string.license_notice_kotlinx_coroutines)

/** A full licence text, one row per licence: [title] names it, [users] the components under it, [raw] the text. */
internal class LicenseText(@StringRes val title: Int, @StringRes val users: Int, @RawRes val raw: Int, val tag: String)

/**
 * The full texts the licences above ask to ship with the app, verbatim; the MIT and BSD texts carry the component's own
 * copyright line. GPL-3.0 is always there: it is Seion's own licence.
 */
internal fun licenseTexts(hasFfmpeg: Boolean = BuildConfig.HAS_FFMPEG): List<LicenseText> = listOfNotNull(
    LicenseText(R.string.license_apache, R.string.license_apache_users, R.raw.apache_2_0, "license_apache"),
    LicenseText(R.string.license_mit, R.string.license_mit_users, R.raw.mit_checker_qual, "license_mit"),
    LicenseText(R.string.license_bsd3, R.string.license_bsd3_users, R.raw.bsd_3_clause_protobuf, "license_bsd3"),
    LicenseText(R.string.license_mpl2, R.string.license_mpl2_users, R.raw.mpl_2_0, "license_mpl2"),
    LicenseText(
        R.string.license_gpl3,
        if (hasFfmpeg) R.string.license_gpl3_users_with_ffmpeg else R.string.license_gpl3_users,
        R.raw.gpl_3_0,
        "license_gpl3"
    ),
    LicenseText(R.string.license_ofl, R.string.license_roboto, R.raw.ofl_roboto, "license_roboto")
)

/** A licence row that shows the full text on demand. */
@Composable
private fun LicenseTextRow(license: LicenseText) {
    val res = LocalResources.current
    var open by remember { mutableStateOf(false) }
    val text = remember(open) { if (open) res.openRawResource(license.raw).bufferedReader().use { it.readText() } else null }
    Column(Modifier.fillMaxWidth()) {
        SettingRow(stringResource(license.title), stringResource(license.users), license.tag, onClick = { open = !open })
        if (text != null) Text(text, style = SeionType.RowSub, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** A small line of the licences screen: where a component's source code is. */
@Composable
private fun SmallNote(text: String) {
    Text(text, style = SeionType.RowSub, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

/** The licences screen's app-bar title: short, so it fits beside the bar's icons in every language. */
internal val LICENSES_TITLE = R.string.licenses_title

@Composable
fun LicensesScreen() {
    BrowseScaffold(stringResource(LICENSES_TITLE), showSearch = false) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomPadding()) {
            item { Text(stringResource(R.string.licenses_app), style = SeionType.Body, modifier = Modifier.padding(16.dp)) }
            item {
                Text(
                    stringResource(R.string.licenses_source, BuildConfig.PROJECT_URL),
                    style = SeionType.Body,
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("licenses_source")
                )
            }
            item { Text(stringResource(R.string.licenses_intro), style = SeionType.Body, modifier = Modifier.padding(16.dp)) }
            items(licenses()) { (name, license) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(stringResource(name), style = SeionType.RowTitle)
                    Text(stringResource(license), style = SeionType.RowSub, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (BuildConfig.HAS_FFMPEG) {
                item { SmallNote(stringResource(R.string.license_ffmpeg_source, BuildConfig.FFMPEG_DECODER_VERSION)) }
            }
            item { SmallNote(stringResource(R.string.license_psl_source)) }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.license_notice_title), style = SeionType.RowSub)
                    apacheNotices.forEach { Text(stringResource(it), style = SeionType.RowSub, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
            items(licenseTexts()) { LicenseTextRow(it) }
            item { Text(stringResource(R.string.covers_notice), style = SeionType.RowSub, modifier = Modifier.padding(16.dp)) }
        }
    }
}
