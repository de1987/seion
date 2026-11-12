package org.seion.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.seion.R
import org.seion.library.ArtistNames

/*
 * Artist lines. The tag is split by [ArtistNames] and
 * shown whole, joined with ", ", on one line: Now playing and the mini-player roll a line that doesn't fit
 * ([ScrollingText]); rows, album cards and the album header end in "…" (lists don't move). TalkBack always reads
 * everyone ("Ana Lima, Bruno Souza, Carla Dias and Davi Rocha").
 */

@Composable
private fun rememberArtistNames(raw: String?): List<String> = remember(raw) { ArtistNames.split(raw) }

/** Everyone, localised: "Ana Lima, Bruno Souza, Carla Dias and Davi Rocha"; "Unknown artist" when there is no one. */
@Composable
private fun spokenArtists(names: List<String>): String {
    val res = LocalResources.current
    val unknown = stringResource(R.string.unknown_artist)
    return remember(names, res, unknown) {
        if (names.isEmpty()) unknown else ArtistNames.spoken(names) { a, b -> res.getString(R.string.artists_spoken_last, a, b) }
    }
}

/** The shown line: everyone joined ("A, B, C"), or "Unknown artist". */
@Composable
private fun shownArtists(names: List<String>): String {
    val unknown = stringResource(R.string.unknown_artist)
    return remember(names, unknown) { if (names.isEmpty()) unknown else ArtistNames.display(names) }
}

/**
 * A row subtitle: the shown [text] ("A, B, C · Album") and what TalkBack reads for it ([description], null when that is
 * the text itself).
 */
class RowSubtitle(val text: String, val description: String?)

/**
 * The subtitle of a row whose first part is an artist tag ([artist] null: no artist part), followed by [rest]
 * (album, …; nulls skipped).
 */
@Composable
fun artistSubtitle(artist: String?, vararg rest: String?): RowSubtitle {
    val names = rememberArtistNames(artist)
    val spoken = spokenArtists(names)
    val others = rest.filterNotNull()
    val shown = if (artist == null) {
        null
    } else if (names.isEmpty()) {
        spoken
    } else {
        ArtistNames.display(names)
    }
    val description = if (names.size > 1) listOf(spoken).plus(others).joinToString(", ") else null
    return RowSubtitle(listOfNotNull(shown).plus(others).joinToString(" · "), description)
}

/**
 * Now playing's artist line: everyone on one line, rolling when it doesn't fit ([ScrollingText], restarted when it
 * becomes [active] or [restartKey] changes). With two or more artists a tap lists them all; TalkBack reads the list with "and". The line is
 * one node tagged [tag]; the text inside it is tagged "[tag]_text".
 */
@Composable
fun NowPlayingArtistLine(
    raw: String?,
    style: TextStyle,
    tag: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    restartKey: Any? = null
) {
    val names = rememberArtistNames(raw)
    val spoken = spokenArtists(names)
    val shown = shownArtists(names)
    var showAll by rememberSaveable(raw) { mutableStateOf(false) }
    val showAllLabel = stringResource(R.string.artists_show_all)
    Box(modifier.fillMaxWidth()) {
        ScrollingText(
            shown,
            Modifier
                .fillMaxWidth()
                // A short line: only the text is the tap target (and the ripple fits it), not the blank space beside it.
                .wrapContentWidth()
                .then(
                    if (names.size > 1) {
                        Modifier.clickable(onClickLabel = showAllLabel, role = Role.Button) { showAll = true }
                    } else {
                        Modifier
                    }
                ).semantics(mergeDescendants = true) { contentDescription = spoken }
                .testTag(tag),
            style = style,
            textAlign = TextAlign.Center,
            active = active,
            restartKey = restartKey,
            // The text itself (it moves while the line rolls).
            textModifier = Modifier.testTag("${tag}_text")
        )
    }
    if (showAll) AllArtistsDialog(names) { showAll = false }
}

/** Every artist of the track, one per line (opened from Now playing's artist line). */
@Composable
private fun AllArtistsDialog(names: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.artists)) },
        // A long list scrolls inside the dialog instead of being cut.
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .semantics { testTagsAsResourceId = true }
                    .testTag("np_artists_list")
            ) {
                names.forEach { Text(it, modifier = Modifier.padding(vertical = 6.dp)) }
            }
        },
        confirmButton = {
            // A dialog is its own window: expose its test tag as a resource id there too (adb_ui.py).
            TextButton(onClick = onDismiss, modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("np_artists_close")) {
                Text(stringResource(R.string.artists_all_close))
            }
        }
    )
}

/**
 * The mini-player's artist line: everyone, rolling when it doesn't fit, start-aligned (it rests at the column start, like
 * the title). TalkBack reads everyone as the line's text, and the line merges into the bar like a plain text (no
 * semantics boundary of its own).
 */
@Composable
fun MiniArtistLine(
    raw: String?,
    style: TextStyle,
    tag: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    restartKey: Any? = null
) {
    val names = rememberArtistNames(raw)
    val spoken = spokenArtists(names)
    ScrollingText(
        shownArtists(names),
        modifier,
        style = style,
        active = active,
        restartKey = restartKey,
        textModifier = Modifier.clearAndSetSemantics {
            text = AnnotatedString(spoken)
            testTag = tag
        },
        edges = ScrollEdges.StartAligned
    )
}

/**
 * A compact artist line (album cards and the album header): everyone on one line, cut with "…" (lists don't move).
 * TalkBack reads everyone, as the line's text, and the line merges into its parent (card) like a plain text: no
 * semantics boundary of its own.
 */
@Composable
fun CompactArtistLine(raw: String?, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current, tag: String? = null) {
    val names = rememberArtistNames(raw)
    val spoken = spokenArtists(names)
    Text(
        shownArtists(names),
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clearAndSetSemantics {
            text = AnnotatedString(spoken)
            if (tag != null) testTag = tag
        }
    )
}
