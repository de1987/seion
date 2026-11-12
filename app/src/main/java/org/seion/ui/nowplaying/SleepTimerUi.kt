package org.seion.ui.nowplaying

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.seion.R
import org.seion.playback.SleepTimer
import org.seion.playback.SleepTimerState
import org.seion.ui.settings.DialogOption
import org.seion.ui.settings.SettingsDialog
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionType

/** The time left on the timer's countdown (ms, −1 when there is none), re-read every second while shown. */
@Composable
internal fun rememberSleepRemaining(state: SleepTimerState): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.deadline) {
        while (state.deadline != SleepTimer.NONE) {
            now = SystemClock.elapsedRealtime()
            val left = state.deadline - now
            if (left <= 0) break
            delay((left - 1) % 1_000L + 1) // on the next whole second of the countdown
        }
    }
    return if (state.deadline == SleepTimer.NONE) -1 else maxOf(0L, state.deadline - now)
}

/** "m:ss" (or "h:mm:ss") for the indicator. */
internal fun clockText(ms: Long): String {
    val s = (ms + 999) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** The ⋮ entry's label: "Sleep timer", or "Sleep timer · 23 min left" / "· End of song" while it runs. */
@Composable
internal fun sleepTimerMenuLabel(state: SleepTimerState, remainingMs: Long): String {
    val name = stringResource(R.string.sleep_timer)
    return when {
        state.endOfSong -> name + " · " + stringResource(R.string.sleep_timer_end_of_song_short)
        remainingMs >= 0 -> {
            val m = SleepTimer.minutesLeft(remainingMs)
            name + " · " + pluralStringResource(R.plurals.sleep_timer_left, m, m)
        }
        else -> name
    }
}

/**
 * Now playing's indicator while the timer runs: a moon and the time left ("23:41", or "End of song"), left of the
 * action row; a tap opens the timer's dialog.
 */
@Composable
internal fun SleepTimerIndicator(state: SleepTimerState, remainingMs: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val text = if (state.endOfSong) stringResource(R.string.sleep_timer_end_of_song_short) else clockText(remainingMs.coerceAtLeast(0))
    val spoken =
        if (state.endOfSong) {
            stringResource(R.string.sleep_timer_end_of_song_short)
        } else {
            val m = SleepTimer.minutesLeft(remainingMs.coerceAtLeast(0))
            pluralStringResource(R.plurals.sleep_timer_left, m, m)
        }
    val description = stringResource(R.string.sleep_timer_indicator, spoken)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 12.dp)
            .testTag("np_sleep_indicator"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(R.drawable.ic_sleep_timer), null, tint = SeionColors.Secondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = SeionColors.Secondary, fontSize = 13.sp, lineHeight = 16.sp)
    }
}

/**
 * The timer's dialog (the settings dialog style): the presets and "End of current song"; while it runs, also "+10 min"
 * and "Turn off the timer". A pick applies at once and closes.
 */
@Composable
internal fun SleepTimerDialog(
    state: SleepTimerState,
    remainingMs: Long,
    onSet: (Int) -> Unit,
    onExtend: () -> Unit,
    onDismiss: () -> Unit
) {
    val description =
        when {
            state.endOfSong -> stringResource(R.string.sleep_timer_pauses_at_end)
            remainingMs >= 0 -> {
                val m = SleepTimer.minutesLeft(remainingMs)
                pluralStringResource(R.plurals.sleep_timer_pauses_in, m, m)
            }
            else -> stringResource(R.string.sleep_timer_description)
        }
    SettingsDialog(stringResource(R.string.sleep_timer), description, stringResource(R.string.cancel), "sleep_dialog", onDismiss) {
        if (state.active) {
            if (!state.endOfSong) {
                ActionRow(stringResource(R.string.sleep_timer_extend, SleepTimer.EXTEND_MINUTES), "sleep_extend") {
                    onExtend()
                    onDismiss()
                }
            }
            ActionRow(stringResource(R.string.sleep_timer_off), "sleep_off") {
                onSet(SleepTimer.OFF)
                onDismiss()
            }
        }
        for (minutes in SleepTimer.PRESETS) {
            DialogOption(pluralStringResource(R.plurals.sleep_timer_minutes, minutes, minutes), null, false, "sleep_$minutes", onClick = {
                onSet(minutes)
                onDismiss()
            })
        }
        DialogOption(stringResource(R.string.sleep_timer_end_of_song), null, state.endOfSong, "sleep_end_of_song", onClick = {
            onSet(SleepTimer.END_OF_SONG)
            onDismiss()
        })
    }
}

/** A dialog row that acts (no radio), on the radios' text keyline. */
@Composable
private fun ActionRow(title: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 56.dp, end = 22.dp, top = 10.dp, bottom = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = SeionType.RowTitle)
    }
}
