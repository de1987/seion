package org.seion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAndFormatTest {
    @Test fun `durations use mm-ss, and h-mm-ss from one hour`() {
        assertEquals("00:00", Format.duration(0))
        assertEquals("00:00", Format.duration(-5))
        assertEquals("00:03", Format.duration(3_000))
        assertEquals("00:36", Format.duration(36_400))
        assertEquals("04:30", Format.duration(270_000))
        assertEquals("12:00", Format.duration(720_000))
        assertEquals("59:59", Format.duration(3_599_999))
        assertEquals("1:00:00", Format.duration(3_600_000))
        assertEquals("1:02:03", Format.duration(3_723_000))
        assertEquals("10:00:00", Format.duration(36_000_000))
    }

    @Test fun `total minutes round and split hours`() {
        assertEquals(0L to 38L, Format.totalMinutes(38 * 60_000L + 10_000))
        assertEquals(1L to 12L, Format.totalMinutes(72 * 60_000L))
    }

    @Test fun `back stack push, pop and pop to a folder level`() {
        val vm = SeionViewModel()
        assertFalse(vm.pop())
        vm.push(Screen.FolderRoots)
        vm.push(Screen.Folder("/r", "/r"))
        vm.push(Screen.Folder("/r", "/r/a"))
        vm.push(Screen.Folder("/r", "/r/a/b"))
        vm.push(Screen.Folder("/r", "/r/a/b")) // same screen twice is ignored
        assertEquals(5, vm.stack.size)
        assertEquals(4, vm.top.depth)
        assertTrue(vm.popTo { it == Screen.Folder("/r", "/r") })
        assertEquals(Screen.Folder("/r", "/r"), vm.top.screen)
        assertFalse(vm.popTo { it == Screen.Settings })
        vm.replaceTop(Screen.Folder("/r", "/r/z"))
        assertEquals(2, vm.top.depth)
        vm.home()
        assertEquals(Screen.Home, vm.top.screen)
    }

    @Test fun `library button returns to the last library screen, not Settings`() {
        val vm = SeionViewModel()
        vm.push(Screen.FolderRoots)
        vm.push(Screen.Folder("/r", "/r/a"))
        vm.push(Screen.Settings) // now playing ⋮ → Settings
        vm.push(Screen.MusicFolders)
        vm.showLibrary()
        assertEquals(Screen.Folder("/r", "/r/a"), vm.top.screen)
        vm.push(Screen.Queue)
        vm.showLibrary()
        assertEquals(Screen.Folder("/r", "/r/a"), vm.top.screen)
    }

    @Test fun `library button with only settings on the stack lands on home`() {
        val vm = SeionViewModel()
        vm.push(Screen.Settings)
        vm.showLibrary()
        assertEquals(Screen.Home, vm.top.screen)
    }

    @Test fun `settings screens are not library screens`() {
        assertFalse(Screen.Settings.isLibrary)
        assertFalse(Screen.Queue.isLibrary)
        assertFalse(Screen.FolderPicker(null).isLibrary)
        assertTrue(Screen.Search.isLibrary)
        assertTrue(Screen.Album("k").isLibrary)
        assertTrue(Screen.Licenses.isSettings)
        assertFalse(Screen.Queue.isSettings)
        // Settings › Album art is a settings screen: the library button leaves it, ⋮ hides "Settings" there.
        assertTrue(Screen.AlbumArtSettings.isSettings)
        assertFalse(Screen.AlbumArtSettings.isLibrary)
    }

    @Test fun `sound is its own screen, neither library nor settings`() {
        assertFalse(Screen.Sound.isLibrary)
        assertFalse(Screen.Sound.isSettings)
    }

    @Test fun `library button from sound returns to the last library screen`() {
        val vm = SeionViewModel()
        vm.push(Screen.Albums)
        vm.push(Screen.Album("k"))
        vm.push(Screen.Sound) // ⋮ → Sound
        vm.showLibrary()
        assertEquals(Screen.Album("k"), vm.top.screen)
        // Through the Settings row: Settings → Sound, both popped.
        vm.push(Screen.Settings)
        vm.push(Screen.Sound)
        vm.showLibrary()
        assertEquals(Screen.Album("k"), vm.top.screen)
    }

    @Test fun `back from sound returns to the screen it was opened on`() {
        val vm = SeionViewModel()
        vm.push(Screen.Artists)
        vm.push(Screen.Sound)
        vm.push(Screen.Sound) // the menu entry again is a no-op
        assertEquals(3, vm.stack.size)
        assertTrue(vm.pop())
        assertEquals(Screen.Artists, vm.top.screen)
    }

    @Test fun `browse menu offers sound right after settings, each hidden on its own screen`() {
        assertEquals(listOf(MenuItem.SETTINGS, MenuItem.SOUND, MenuItem.RESCAN), browseMenu(Screen.Home))
        assertEquals(listOf(MenuItem.SETTINGS, MenuItem.SOUND, MenuItem.RESCAN), browseMenu(Screen.Album("k")))
        assertEquals(listOf(MenuItem.SETTINGS, MenuItem.SOUND, MenuItem.RESCAN), browseMenu(Screen.Queue))
        assertEquals(listOf(MenuItem.SETTINGS, MenuItem.RESCAN), browseMenu(Screen.Sound))
        for (s in listOf(Screen.Settings, Screen.MusicFolders, Screen.FolderPicker("/x"), Screen.Licenses, Screen.AlbumArtSettings)) {
            assertEquals(s.toString(), listOf(MenuItem.SOUND, MenuItem.RESCAN), browseMenu(s))
        }
    }

    @Test fun `now playing menu offers sound right after settings`() {
        assertEquals(
            listOf(MenuItem.ADD_TO_PLAYLIST, MenuItem.QUEUE, MenuItem.SLEEP_TIMER, MenuItem.SETTINGS, MenuItem.SOUND),
            NOW_PLAYING_MENU
        )
    }

    @Test fun `expand requests are counted`() {
        val vm = SeionViewModel()
        vm.requestExpand()
        vm.requestExpand()
        assertEquals(2, vm.expandRequest.value)
    }
}
