package org.seion.ui.settings

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.seion.R
import org.seion.art.OnlineCoverCache
import org.seion.ui.components.LocalContainer
import org.seion.ui.library.BrowseScaffold
import org.seion.ui.library.bottomPadding
import org.seion.ui.library.rememberEntryListState
import org.seion.ui.theme.LocalAccent
import org.seion.ui.theme.SeionColors

/**
 * Settings › Album art: the lookup order as a small card, then folder images, online
 * lookups, Wi-Fi only, "Find missing covers again" and the cover cache with its size.
 */
@Composable
fun AlbumArtSettingsScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val s by c.settingsState.collectAsStateWithLifecycle()
    val gen by c.art.generationFlow.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val stats by produceState<OnlineCoverCache.Stats?>(null, refresh, gen) { value = withContext(Dispatchers.IO) { c.onlineCache.stats() } }
    val repo = c.settings
    BrowseScaffold(stringResource(R.string.settings_album_art), showSearch = false) {
        LazyColumn(Modifier.fillMaxSize(), state = rememberEntryListState(), contentPadding = bottomPadding(12.dp)) {
            item { LookupOrderCard(s.useFolderImages, s.onlineCovers, s.onlineWifiOnly) }
            item {
                SwitchRow(
                    stringResource(R.string.use_folder_images),
                    stringResource(R.string.use_folder_images_summary),
                    s.useFolderImages,
                    "set_folder_images",
                    icon = org.seion.R.drawable.ic_folder
                ) { scope.launch { repo.setUseFolderImages(it) } }
            }
            item {
                SwitchRow(
                    stringResource(R.string.online_covers),
                    stringResource(R.string.online_covers_short),
                    s.onlineCovers,
                    "set_online",
                    icon = R.drawable.ic_set_cloud
                ) { scope.launch { repo.setOnlineCovers(it) } }
            }
            item {
                SwitchRow(
                    stringResource(R.string.wifi_only),
                    stringResource(R.string.wifi_only_summary),
                    s.onlineWifiOnly,
                    "set_wifi_only",
                    enabled = s.onlineCovers,
                    icon = R.drawable.ic_set_wifi
                ) { scope.launch { repo.setOnlineWifiOnly(it) } }
            }
            item { FindCoversAgain(enabled = s.onlineCovers) }
            item {
                val st = stats
                SettingRow(
                    stringResource(R.string.clear_cover_cache),
                    cacheSubtitle(st),
                    "set_clear_cache",
                    icon = R.drawable.ic_set_trash,
                    enabled = st == null || st.entries > 0,
                    onClick = {
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { c.clearOnlineCovers() }
                            refresh++
                            Toast.makeText(context, res.getQuantityString(R.plurals.cache_cleared, n, n), Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            // The privacy line under the rows: exactly what is sent, and that nothing is while the switch is off.
            item {
                Text(
                    stringResource(R.string.online_covers_summary),
                    color = Color(0xFF8D8F96),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(start = SETTINGS_KEYLINE, end = 16.dp, top = 12.dp, bottom = 18.dp).testTag("art_privacy")
                )
            }
        }
    }
}

/** "38 covers · 12.4 MB", "Only “not found” results", or "Empty". */
@Composable
private fun cacheSubtitle(stats: OnlineCoverCache.Stats?): String {
    val context = LocalContext.current
    return when {
        stats == null -> stringResource(R.string.clear_cover_cache_summary)
        stats.entries == 0 -> stringResource(R.string.cache_empty)
        stats.covers == 0 -> stringResource(R.string.cache_only_misses)
        else -> pluralStringResource(R.plurals.n_cached_covers, stats.covers, stats.covers) + " · " +
            Formatter.formatShortFileSize(context, stats.bytes)
    }
}

/**
 * The order Seion looks for a cover in (AlbumArtResolver): a cover the user chose, the file, the folder, online,
 * then the no-cover tile. A step that is switched off is greyed; no source is named (no third-party names in the UI).
 */
@Composable
internal fun LookupOrderCard(folderImages: Boolean, online: Boolean, wifiOnly: Boolean) {
    val onlineDetail =
        if (!online) {
            stringResource(R.string.lookup_online_off)
        } else {
            stringResource(if (wifiOnly) R.string.lookup_online_detail_wifi else R.string.lookup_online_detail)
        }
    val steps =
        listOf(
            Triple(stringResource(R.string.lookup_chosen), stringResource(R.string.lookup_chosen_detail), true),
            Triple(stringResource(R.string.lookup_file), stringResource(R.string.lookup_file_detail), true),
            Triple(stringResource(R.string.lookup_folder), stringResource(R.string.lookup_folder_detail), folderImages),
            Triple(stringResource(R.string.lookup_online), onlineDetail, online),
            Triple(stringResource(R.string.lookup_tile), stringResource(R.string.lookup_tile_detail), false)
        )
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 2.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x0DFFFFFF))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("art_lookup_order")
    ) {
        Text(stringResource(R.string.lookup_intro), color = SeionColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(8.dp))
        steps.forEachIndexed { i, (title, detail, on) ->
            LookupStep(i + 1, title, detail, on, last = i == steps.lastIndex)
        }
    }
}

/** One numbered step: an 18 dp disc (accent when the step is on, grey when not), a line down to the next step. */
@Composable
private fun LookupStep(n: Int, title: String, detail: String, on: Boolean, last: Boolean) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(18.dp).fillMaxHeight()) {
            if (!last) {
                Box(
                    Modifier.padding(top = 18.dp).align(Alignment.TopCenter).width(1.dp).fillMaxHeight().background(Color(0x2EFFFFFF))
                )
            }
            Box(
                Modifier.size(18.dp).clip(CircleShape).background(if (on) LocalAccent.current else Color(0xFF4A4C52)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    n.toString(),
                    color = if (on) Color(0xFF111111) else Color(0xFFDDDDDD),
                    fontSize = 10.5.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 9.dp)) {
            Text(title, color = SeionColors.Ink, fontSize = 13.sp, lineHeight = 17.5.sp)
            Text(detail, color = SeionColors.Muted, fontSize = 11.5.sp, lineHeight = 15.5.sp)
        }
    }
}
