package org.seion.ui.onboarding

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import org.seion.R
import org.seion.ui.components.Pill
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionType

fun hasFullFileAccess(): Boolean = Environment.isExternalStorageManager()

/** Opens the system "All files access" page for Seion (falls back to the generic list). */
fun openAllFilesAccess(context: Context) {
    val direct =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(direct) }.onFailure {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** First run (and whenever the grant is missing): why full file access, and a button straight to the grant. */
@Composable
fun PermissionScreen() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp).testTag("permission_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher_foreground), null, modifier = Modifier.size(120.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.permission_title), color = SeionColors.Ink, fontSize = 22.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.permission_body), style = SeionType.Body, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Pill(stringResource(R.string.permission_grant), "permission_grant", solid = true) { openAllFilesAccess(context) }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.permission_note), style = SeionType.RowSub, textAlign = TextAlign.Center)
    }
}
