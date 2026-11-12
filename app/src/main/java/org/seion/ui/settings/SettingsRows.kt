package org.seion.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.seion.ui.theme.LocalAccent
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionType

/*
 * The settings layout: rows 58 dp, a 24 dp icon slot at 16 dp so every
 * title starts on the 54 dp keyline, section labels in the cover's accent colour on that keyline.
 */

/** Where a settings row's text starts: 16 dp margin + the 24 dp icon slot + 14 dp gap. */
internal val SETTINGS_KEYLINE: Dp = 54.dp

/** Row icons: a light grey (#C9CACF), stroked like the app bar's icons. */
internal val SettingsIconTint = Color(0xFFC9CACF)

/** A settings row's second line (12.5 sp, 1.38 line height). */
internal val SettingsSub = SeionType.RowSub.copy(fontSize = 12.5.sp, lineHeight = 17.sp)

/**
 * A section label ("LIBRARY"): 12 sp medium capitals, 0.07 em apart, in the accent of the current cover (always light:
 * `accentFrom` keeps its lightness at 0.68–0.82, about 10:1 or more on the page), on the keyline. [first]: the
 * label at the top of a screen (6 dp above instead of 18).
 */
@Composable
internal fun SettingsSection(text: String, first: Boolean = false) {
    Text(
        text.uppercase(),
        color = LocalAccent.current,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.84.sp,
        modifier = Modifier.fillMaxWidth().padding(start = SETTINGS_KEYLINE, end = 16.dp, top = if (first) 6.dp else 18.dp, bottom = 4.dp)
    )
}

/** The 24 dp icon slot (empty when [icon] is null, so the text still starts on the keyline). */
@Composable
private fun IconSlot(@DrawableRes icon: Int?, enabled: Boolean) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = if (enabled) SettingsIconTint else SeionColors.Faint, modifier = Modifier.size(24.dp))
        }
    }
    Spacer(Modifier.width(14.dp))
}

/** Title and second line of a row. */
@Composable
private fun RowText(title: String, subtitle: String?, enabled: Boolean, keyline: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = SeionType.RowTitle, color = if (enabled) SeionColors.Ink else SeionColors.Faint)
        if (subtitle !=
            null
        ) {
            Text(subtitle, style = if (keyline) SettingsSub else SeionType.RowSub, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * A settings row that opens something (or does it): icon, title, second line. With [icon] null and [keyline] false it
 * is a plain row (the licences, the font licence). [onClick] null: information only (TalkBack reads it, nothing
 * to press).
 */
@Composable
internal fun SettingRow(
    title: String,
    subtitle: String?,
    tag: String,
    onClick: (() -> Unit)?,
    @DrawableRes icon: Int? = null,
    keyline: Boolean = icon != null,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (keyline) 58.dp else 56.dp)
            .then(
                if (onClick !=
                    null
                ) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (keyline) IconSlot(icon, enabled)
        RowText(title, subtitle, enabled, keyline, Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A switch row: the whole row toggles. */
@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    tag: String,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    onChange: (Boolean) -> Unit
) {
    val keyline = icon != null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (keyline) 58.dp else 56.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (keyline) IconSlot(icon, enabled)
        RowText(title, subtitle, enabled, keyline, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = SeionColors.Accent, checkedThumbColor = Color(0xFF1A1A1A)),
            modifier = Modifier.testTag("${tag}_switch")
        )
    }
}

/**
 * The settings dialog: #212329, 22 dp corners, a title (19 sp), an optional line under it, the [options], and
 * Cancel. Its test tags are exposed as resource ids (a dialog is its own window).
 */
@Composable
internal fun SettingsDialog(
    title: String,
    description: String?,
    cancel: String,
    tag: String,
    onDismiss: () -> Unit,
    options: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF212329))
                .verticalScroll(rememberScrollState())
                .padding(top = 22.dp, bottom = 8.dp)
                .semantics { testTagsAsResourceId = true }
                .testTag(tag)
        ) {
            Text(
                title,
                color = SeionColors.Ink,
                fontSize = 19.sp,
                lineHeight = 25.sp,
                modifier = Modifier.padding(horizontal = 22.dp).padding(bottom = 6.dp)
            )
            if (description != null) {
                Text(description, style = SettingsSub, modifier = Modifier.padding(horizontal = 22.dp).padding(bottom = 10.dp))
            }
            options()
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    cancel,
                    color = LocalAccent.current,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("${tag}_cancel")
                )
            }
        }
    }
}

/** The settings radio button: a 20 dp ring, the accent with a 10 dp dot when selected. */
@Composable
internal fun SettingsRadio(selected: Boolean) {
    val accent = LocalAccent.current
    Box(
        Modifier
            .size(20.dp)
            .border(2.dp, if (selected) accent else Color(0xFF8D8F96), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
    }
}

/** One choice of a [SettingsDialog]: radio, title, an optional second line, an optional preview on the right. */
@Composable
internal fun DialogOption(
    title: String,
    subtitle: String?,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    preview: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRadio(selected)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = SeionType.RowTitle)
            if (subtitle != null) Text(subtitle, style = SeionType.RowSub, modifier = Modifier.padding(top = 2.dp))
        }
        if (preview != null) {
            Spacer(Modifier.width(14.dp))
            preview()
        }
    }
}
