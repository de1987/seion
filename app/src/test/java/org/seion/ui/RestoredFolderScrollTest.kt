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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.seion.AppContainer
import org.seion.library.TrackEntity
import org.seion.ui.components.LocalContainer

/** A real close in a scrolled list comes back with the same row at the top of the list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestoredFolderScrollTest {
    @get:Rule val compose = createComposeRule()

    private val container by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AppContainer(context)
    }

    private val tmp: File = Files.createTempDirectory("restore").toFile()
    private val root = File(tmp, "Music").apply { mkdirs() }.absolutePath

    private val env =
        object : NavRestoreEnv {
            override fun hasAccess() = true

            // The restore's checks finish after the first frame, as they do in a warm test JVM (where the flake showed):
            // the restored stack then arrives as a main-looper message while the test is already waiting.
            override suspend fun musicFolders(): List<String> {
                Thread.sleep(RESTORE_LAG_MS)
                return listOf(root)
            }

            override fun volumes() = listOf(tmp.absolutePath)

            override fun isDirectory(path: String) = File(path).isDirectory

            override suspend fun indexEmpty() = false

            override suspend fun hasAlbum(key: String) = true

            override suspend fun hasArtist(name: String?) = true

            override suspend fun hasGenre(name: String?) = true

            override suspend fun awaitScanDone() = Unit
        }

    private fun shown(tag: String) = runCatching { compose.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    /**
     * Waits until the restored screen shows [tag]. The node fetch comes first on purpose: it waits for idle, which runs
     * the main looper, and the restored stack reaches [SeionViewModel.ready] as a main-looper message. Compose's
     * waitUntil itself only advances the frame clock and sleeps, so `vm.ready && fetch` never ran the looper while
     * ready was false: whenever the restore finished after setContent's own idle (a warm JVM), the test timed out.
     */
    private fun awaitRestored(vm: SeionViewModel, tag: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() && vm.ready }

    @Test fun `a restored folder shows the same first visible row`() {
        repeat(40) { i -> File(root, "Track %02d.mp3".format(i)).writeText("") }
        val file = File(tmp, "nav.json")
        // Item 0 is the "Files" header, so row 20 is the 20th file (file_19), scrolled exactly to the top.
        file.writeText(
            NavStore.encode(
                SavedNav(
                    listOf(NavEntry(0, Screen.Home, 0), NavEntry(1, Screen.FolderRoots, 1), NavEntry(2, Screen.Folder(root, root), 2)),
                    nextId = 3,
                    nowPlaying = false,
                    scroll = mapOf(2L to ScrollPos(20, 0))
                )
            )
        )
        val store = NavStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val vm = SeionViewModel(SavedStateHandle(), store, env)

        compose.setContent { CompositionLocalProvider(LocalContainer provides container) { if (vm.ready) SeionRoot(vm) } }
        awaitRestored(vm, "file_19")
        compose.waitForIdle()

        assertTrue("the saved first row is on screen", shown("file_19"))
        assertFalse("the row above it is scrolled off", shown("file_18"))
        assertFalse("the list did not start at the top", shown("file_0"))
    }

    @Test fun `a restored album list waits for its rows, then shows the same first row`() {
        val key = "Aurora Vale|Glass Harbour"
        runBlocking {
            container.db.tracks().upsert(
                (1..40).map { n ->
                    TrackEntity(
                        path = "$root/Glass Harbour/$n.flac", folder = "$root/Glass Harbour", fileName = "$n.flac", title = "Song $n",
                        artist = "Aurora Vale", albumArtist = "Aurora Vale", album = "Glass Harbour", albumKey = key, genre = null,
                        year = 2024, trackNumber = n, discNumber = 1, durationMs = 200_000, lastModified = 0, sizeBytes = 0,
                        dateAdded = 0, hasEmbeddedArt = false
                    )
                }
            )
        }
        val file = File(tmp, "nav.json")
        // Item 0 is the album header, so row 20 is the 20th track (track_19). The rows arrive from Room after the
        // first frame, when the list has only its header: a plain restore forgets the row there.
        file.writeText(
            NavStore.encode(
                SavedNav(
                    listOf(NavEntry(0, Screen.Home, 0), NavEntry(1, Screen.Albums, 1), NavEntry(2, Screen.Album(key), 2)),
                    nextId = 3,
                    nowPlaying = false,
                    scroll = mapOf(2L to ScrollPos(20, 0))
                )
            )
        )
        val vm = SeionViewModel(SavedStateHandle(), NavStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO)), env)

        compose.setContent { CompositionLocalProvider(LocalContainer provides container) { if (vm.ready) SeionRoot(vm) } }
        awaitRestored(vm, "track_19")
        compose.waitForIdle()

        assertTrue("the saved first row is on screen", shown("track_19"))
        assertFalse("the row above it is scrolled off", shown("track_18"))
        assertFalse("the list did not start at the header", shown("album_title"))
    }

    private companion object {
        const val RESTORE_LAG_MS = 300L
    }
}
