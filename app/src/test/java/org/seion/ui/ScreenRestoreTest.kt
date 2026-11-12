package org.seion.ui

import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.seion.playback.PlayerUiState

/**
 * Paused on now playing, left Seion for about a minute, came back: Seion must come
 * back to the screen that was open: now playing, or the exact library screen and folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ScreenRestoreTest {
    private val root = "/storage/emulated/0"

    /** What the system hands a recreated activity or a restarted process: the handle's saved values. */
    private fun restoredHandle(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

    @Test fun `a recreated activity or process comes back to the same folder and to now playing`() {
        val handle = SavedStateHandle()
        val vm = SeionViewModel(handle)
        vm.push(Screen.FolderRoots)
        vm.push(Screen.Folder(root, "$root/Music"))
        vm.push(Screen.Folder(root, "$root/Music/CoverQA"))
        vm.nowPlaying = true

        val restored = SeionViewModel(restoredHandle(handle))

        assertEquals(vm.stack.toList(), restored.stack.toList())
        assertTrue("now playing stays open", restored.nowPlaying)
        // Back still walks the restored folders, and new screens never reuse a restored entry's id.
        assertTrue(restored.pop())
        assertEquals(Screen.Folder(root, "$root/Music"), restored.top.screen)
        restored.push(Screen.Albums)
        assertFalse(restored.top.id in vm.stack.map { it.id })
    }

    @Test fun `closing now playing and going back are kept too`() {
        val handle = SavedStateHandle()
        val vm = SeionViewModel(handle)
        vm.nowPlaying = true
        vm.push(Screen.Albums)
        vm.push(Screen.Album("Aurora Vale|Glass Harbour"))
        vm.pop()
        vm.nowPlaying = false

        val restored = SeionViewModel(restoredHandle(handle))

        assertEquals(listOf(Screen.Home, Screen.Albums), restored.stack.map { it.screen })
        assertFalse(restored.nowPlaying)
    }

    @Test fun `every kind of screen survives the saved state`() {
        val screens =
            listOf(
                Screen.Home, Screen.Songs, Screen.Albums, Screen.Album("a|\"b\" ç"), Screen.Artists, Screen.Artist(null),
                Screen.Artist("Ölvis"), Screen.Genres, Screen.Genre(null), Screen.Genre("Jazz"), Screen.FolderRoots,
                Screen.Folder("/storage/0000-0000", "/storage/0000-0000/Música/ação"), Screen.Recent, Screen.Search,
                Screen.Queue, Screen.Settings, Screen.MusicFolders, Screen.FolderPicker(null), Screen.FolderPicker("/x"),
                Screen.Licenses, Screen.Sound
            )
        val nav = SavedNav(screens.mapIndexed { i, s -> NavEntry(i.toLong(), s, i) }, screens.size.toLong(), true)
        assertEquals(nav, SavedNav.decode(SavedNav.encode(nav)))
    }

    @Test fun `the Sound screen is kept like any other screen`() {
        val handle = SavedStateHandle()
        val vm = SeionViewModel(handle)
        vm.push(Screen.Albums)
        vm.push(Screen.Album("Aurora Vale|Glass Harbour"))
        vm.push(Screen.Sound)

        val restored = SeionViewModel(restoredHandle(handle))

        assertEquals(
            listOf(Screen.Home, Screen.Albums, Screen.Album("Aurora Vale|Glass Harbour"), Screen.Sound),
            restored.stack.map {
                it.screen
            }
        )
        assertTrue(restored.pop())
        assertEquals(Screen.Album("Aurora Vale|Glass Harbour"), restored.top.screen)
    }

    @Test fun `an unreadable saved value starts at the library home`() {
        assertNull(SavedNav.decode("{not json"))
        assertNull(SavedNav.decode(null))
        val vm = SeionViewModel(SavedStateHandle(mapOf("seion.nav" to "{not json")))
        assertEquals(listOf(Screen.Home), vm.stack.map { it.screen })
        assertFalse(vm.nowPlaying)
    }

    private val track = MediaItem.Builder().setMediaId("/Music/Aurora Vale/Glass Harbour/03 Salt Map.flac").build()

    @Test fun `coming back after the paused service was released does not close now playing`() {
        // Paused on now playing; Home releases the controller and the paused service; coming back connects a new
        // session that is empty until the saved queue is restored.
        val sequence =
            listOf(
                PlayerUiState(connected = true, current = track, settled = true),
                PlayerUiState(connected = false, current = track, settled = false),
                PlayerUiState(connected = true, current = null, settled = false),
                PlayerUiState(connected = true, current = track, settled = true)
            )
        sequence.forEach { assertFalse("closed now playing at $it", NowPlayingRule.queueGone(it)) }
    }

    @Test fun `now playing closes when the queue is really gone`() {
        assertTrue(NowPlayingRule.queueGone(PlayerUiState(connected = true, current = null, settled = true)))
    }

    @Test fun `the queue is known once the service answered and the controller shows its items`() {
        assertFalse(NowPlayingRule.settled(restoreAnswered = false, serviceItems = 0, controllerItems = 0))
        assertFalse(NowPlayingRule.settled(restoreAnswered = false, serviceItems = 3, controllerItems = 3))
        assertFalse(NowPlayingRule.settled(restoreAnswered = true, serviceItems = 3, controllerItems = 0))
        assertTrue(NowPlayingRule.settled(restoreAnswered = true, serviceItems = 3, controllerItems = 3))
        assertTrue(NowPlayingRule.settled(restoreAnswered = true, serviceItems = 0, controllerItems = 0))
    }
}
