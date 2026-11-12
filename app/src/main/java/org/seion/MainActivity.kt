package org.seion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.ContextCompat
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.seion.settings.LocaleContext
import org.seion.ui.ReducedMotionMonitor
import org.seion.ui.SeionRoot
import org.seion.ui.SeionViewModel
import org.seion.ui.components.LocalContainer
import org.seion.ui.components.LocalReduceMotion
import org.seion.ui.onboarding.PermissionScreen
import org.seion.ui.onboarding.hasFullFileAccess
import org.seion.ui.theme.SeionColors
import org.seion.ui.theme.SeionTheme

class MainActivity : ComponentActivity() {
    private val vm: SeionViewModel by viewModels {
        viewModelFactory {
            initializer {
                val c = (application as SeionApp).container
                SeionViewModel(createSavedStateHandle(), c.navStore, c.navEnv)
            }
        }
    }
    private var granted by mutableStateOf(false)
    private val motion by lazy { ReducedMotionMonitor.of(this) }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(LocaleContext.wrap(newBase))

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        val container = (application as SeionApp).container
        handleIntent(intent, recreated = savedInstanceState != null)
        setContent {
            SeionTheme {
                CompositionLocalProvider(LocalContainer provides container, LocalReduceMotion provides motion.reduced.value) {
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        if (granted) {
                            RuntimePermissions()
                            LaunchedEffect(Unit) { container.scans.scan(force = false) }
                            // A cold start reads the saved screens first (a few ms): the bare page until then, so the
                            // library home never flashes before the screen you were on.
                            if (vm.ready) SeionRoot(vm) else Box(Modifier.fillMaxSize().background(SeionColors.Page))
                        } else {
                            PermissionScreen()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // "Remove animations" is observed while visible: the band, the drift and the Settings row follow a change live.
        motion.start()
        (application as SeionApp).container.player.connect()
    }

    override fun onResume() {
        super.onResume()
        granted = hasFullFileAccess()
        // "Remove animations": Compose already zeroes finite animations; infinite ones (drift, level bars) stop here.
        motion.refresh()
    }

    override fun onStop() {
        // Leaving the screen (Home, Recents, a swipe away may follow): the browse state goes to disk now.
        vm.flush()
        motion.stop()
        (application as SeionApp).container.player.disconnect()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The media panel's tap opens Seion on now playing (singleTop: the running activity gets onNewIntent). */
    private fun handleIntent(intent: Intent?, recreated: Boolean = false) {
        if (opensNowPlaying(intent?.action, recreated)) vm.requestExpand()
    }

    companion object {
        const val ACTION_OPEN_NOW_PLAYING = "org.seion.OPEN_NOW_PLAYING"

        /**
         * Whether an intent delivered to the activity opens now playing. A re-created activity (font size, language,
         * process restore) gets its original launch intent again; the screen it had is restored instead.
         */
        fun opensNowPlaying(action: String?, recreated: Boolean): Boolean = !recreated && action == ACTION_OPEN_NOW_PLAYING
    }
}

/**
 * Only POST_NOTIFICATIONS (API 33+), asked once ever: all-files access already lets Seion read every audio file through
 * java.io on 31+ (incl. 33+, where READ_MEDIA_AUDIO would only add MediaStore access Seion doesn't use), so the extra
 * "music and audio" prompt is not shown. The permissions stay declared in the manifest.
 */
@androidx.compose.runtime.Composable
private fun RuntimePermissions() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = org.seion.ui.components.LocalContainer.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (container.settings.current().notificationsAsked) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return@LaunchedEffect
        }
        container.settings.markNotificationsAsked()
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
