package org.seion.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.AppContainer
import org.seion.ui.components.LocalContainer

/**
 * The Settings screen is longer than a screen, so after a real close it comes back at the row it was on, not just
 * at its top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestoredSettingsScrollTest {
    @get:Rule val compose = createComposeRule()

    private val container by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AppContainer(context)
    }

    private val tmp: File = Files.createTempDirectory("restore").toFile()

    private val env =
        object : NavRestoreEnv {
            override fun hasAccess() = true

            override suspend fun musicFolders() = listOf(tmp.absolutePath)

            override fun volumes() = listOf(tmp.absolutePath)

            override fun isDirectory(path: String) = File(path).isDirectory

            override suspend fun indexEmpty() = false

            override suspend fun hasAlbum(key: String) = true

            override suspend fun hasArtist(name: String?) = true

            override suspend fun hasGenre(name: String?) = true

            override suspend fun awaitScanDone() = Unit
        }

    private fun shown(tag: String) = runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    @Test fun `a restored Settings screen shows the same first row`() {
        val file = File(tmp, "nav.json")
        // Item 6 is the Background style row (after Library's label and its 4 rows, and Now playing's label).
        file.writeText(
            NavStore.encode(
                SavedNav(
                    listOf(NavEntry(0, Screen.Home, 0), NavEntry(1, Screen.Settings, 1)),
                    nextId = 2,
                    nowPlaying = false,
                    scroll = mapOf(1L to ScrollPos(6, 0))
                )
            )
        )
        val vm = SeionViewModel(SavedStateHandle(), NavStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO)), env)

        compose.setContent { CompositionLocalProvider(LocalContainer provides container) { if (vm.ready) SeionRoot(vm) } }
        // The fetch first: it runs the main looper, where the restored stack arrives (RestoredFolderScrollTest.awaitRestored).
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("set_background").fetchSemanticsNodes().isNotEmpty() && vm.ready }
        compose.waitForIdle()

        assertTrue("the saved first row is on screen", shown("set_background"))
        assertFalse("the rows above it are scrolled off", shown("set_folders"))
        assertFalse("the rows above it are scrolled off", shown("set_ignore_short"))
    }
}
