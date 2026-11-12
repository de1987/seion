package org.seion.ui.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import org.seion.R
import org.seion.playlists.PlaylistNames
import org.seion.ui.theme.SeionColors

/**
 * New playlist / Rename playlist: one field, prefilled and selected (typing replaces it), checked as you type
 * ([PlaylistNames.problem]: required, at most 100 characters, not the name of another playlist ignoring case). The
 * confirm button is off while the name can't be used; Enter confirms.
 */
@Composable
fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    others: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val problem = PlaylistNames.problem(value.text, others)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val confirm = { if (problem == null) onConfirm(PlaylistNames.clean(value.text)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // A dialog is its own window: expose its test tags as resource ids there too (adb_ui.py).
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("name_dialog"),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.playlist_name)) },
                isError = problem != null && value.text.isNotEmpty(),
                supportingText = {
                    val msg =
                        when (problem) {
                            PlaylistNames.Problem.EMPTY -> stringResource(R.string.name_empty)
                            PlaylistNames.Problem.TOO_LONG -> pluralStringResource(
                                R.plurals.name_too_long,
                                PlaylistNames.MAX_LENGTH,
                                PlaylistNames.MAX_LENGTH
                            )
                            PlaylistNames.Problem.DUPLICATE -> stringResource(R.string.name_taken)
                            null -> null
                        }
                    if (msg != null) Text(msg, modifier = Modifier.testTag("name_error"))
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SeionColors.Accent,
                    focusedLabelColor = SeionColors.Accent,
                    cursorColor = SeionColors.Accent
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("name_input")
            )
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = problem == null, modifier = Modifier.testTag("name_ok")) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("name_cancel")) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** Delete “Name”? — the songs stay on the device (a playlist only refers to files). */
@Composable
fun DeletePlaylistDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("delete_dialog"),
        title = { Text(stringResource(R.string.delete_playlist_title, name)) },
        text = { Text(stringResource(R.string.delete_playlist_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("delete_ok")) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("delete_cancel")) { Text(stringResource(R.string.cancel)) }
        }
    )
}
