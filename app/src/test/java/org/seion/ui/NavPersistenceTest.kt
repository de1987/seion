package org.seion.ui

import androidx.lifecycle.SavedStateHandle
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Closing Seion for real (swiped away from Recents, a restart, an update) and coming back
 * continues where you were: the same folder or album, its scroll position, Back through the same screens, and now
 * playing open if it was. The saved state alone (ScreenRestoreTest) only survives the process being killed in
 * the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavPersistenceTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val dir: File = Files.createTempDirectory("nav").toFile()
    private val file = File(dir, "nav.json")
    private val root = "/storage/emulated/0/Music"

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** The library as a restore sees it: folders that exist, what the index holds, the scan. */
    private class FakeEnv(
        var access: Boolean = true,
        var roots: List<String> = listOf("/storage/emulated/0/Music"),
        val dirs: MutableSet<String> = mutableSetOf(),
        val albums: MutableSet<String> = mutableSetOf(),
        val artists: MutableSet<String?> = mutableSetOf(),
        val genres: MutableSet<String?> = mutableSetOf(),
        var empty: Boolean = false,
        val playlists: MutableSet<Long> = mutableSetOf()
    ) : NavRestoreEnv {
        val scanDone = CompletableDeferred<Unit>()

        override fun hasAccess() = access

        override suspend fun musicFolders() = roots

        override fun volumes() = listOf("/storage/emulated/0")

        override fun isDirectory(path: String) = path in dirs

        override suspend fun indexEmpty() = empty

        override suspend fun hasAlbum(key: String) = key in albums

        override suspend fun hasArtist(name: String?) = name in artists

        override suspend fun hasGenre(name: String?) = name in genres

        override suspend fun hasPlaylist(id: Long) = id in playlists

        override suspend fun awaitScanDone() = scanDone.await()
    }

    private fun env() = FakeEnv(dirs = mutableSetOf(root, "$root/Aurora Vale", "$root/Aurora Vale/Glass Harbour"))

    private fun store() = NavStore(file, scope, dispatcher)

    private fun idle() = dispatcher.scheduler.advanceUntilIdle()

    /** A new process after a real close: nothing in the saved state, only the file. */
    private fun coldStart(env: NavRestoreEnv = env()) = SeionViewModel(SavedStateHandle(), store(), env, dispatcher).also { idle() }

    private fun nav(vararg screens: Screen, scroll: Map<Long, ScrollPos> = emptyMap(), nowPlaying: Boolean = false) =
        SavedNav(screens.mapIndexed { i, s -> NavEntry(i.toLong(), s, i) }, screens.size.toLong(), nowPlaying, scroll)

    @Test fun `the file keeps the stack, now playing and every list's scroll position`() {
        val saved =
            nav(
                Screen.Home,
                Screen.FolderRoots,
                Screen.Folder(root, root),
                Screen.Folder(root, "$root/Aurora Vale/ação \"x\""),
                scroll = mapOf(1L to ScrollPos(0, 0), 2L to ScrollPos(14, 37), 3L to ScrollPos(3, 0)),
                nowPlaying = true
            )
        assertEquals(saved, NavStore.decode(NavStore.encode(saved)))
    }

    @Test fun `a playlist page comes back, and a deleted playlist is trimmed to the Playlists screen`() {
        file.writeText(NavStore.encode(nav(Screen.Home, Screen.Playlists, Screen.Playlist(7))))
        val kept = coldStart(env().apply { playlists += 7L })
        assertEquals(listOf(Screen.Home, Screen.Playlists, Screen.Playlist(7)), kept.stack.map { it.screen })

        file.writeText(NavStore.encode(nav(Screen.Home, Screen.Playlists, Screen.Playlist(7))))
        val trimmed = coldStart(env())
        assertEquals(listOf(Screen.Home, Screen.Playlists), trimmed.stack.map { it.screen })
    }

    @Test fun `an unreadable, older or foreign file starts fresh instead of crashing`() {
        assertNull(NavStore.decode(null))
        assertNull(NavStore.decode(""))
        assertNull(NavStore.decode("{not json"))
        assertNull(NavStore.decode("""{"version":1,"nav":{"stack":[{"id":0,"screen":{"type":"nope"},"depth":0}]}}"""))
        // The saved-state JSON without the file's version, and a future version.
        assertNull(NavStore.decode(SavedNav.encode(nav(Screen.Home, Screen.Albums))))
        assertNull(NavStore.decode(NavStore.encode(nav(Screen.Home)).replace("\"version\":1", "\"version\":2")))
        // A stack that doesn't start at the library home.
        assertNull(NavStore.decode(NavStore.encode(nav(Screen.Albums, Screen.Home))))

        file.writeText("{not json")
        val vm = coldStart()
        assertTrue(vm.ready)
        assertEquals(listOf(Screen.Home), vm.stack.map { it.screen })
        assertFalse(vm.nowPlaying)
    }

    @Test fun `a real close comes back to the same folder, scroll position and now playing`() {
        val first = SeionViewModel(SavedStateHandle(), store(), env(), dispatcher)
        idle()
        first.push(Screen.FolderRoots)
        first.push(Screen.Folder(root, root))
        first.push(Screen.Folder(root, "$root/Aurora Vale"))
        first.setScroll(first.top.id, ScrollPos(12, 40))
        first.nowPlaying = true
        idle()

        val vm = coldStart()

        assertTrue(vm.ready)
        assertEquals(first.stack.toList(), vm.stack.toList())
        assertTrue("now playing stays open", vm.nowPlaying)
        assertEquals(ScrollPos(12, 40), vm.scrollOf(vm.top.id))
        // Back walks up the folders, and a new screen never reuses a restored id.
        assertTrue(vm.pop())
        assertEquals(Screen.Folder(root, root), vm.top.screen)
        vm.push(Screen.Albums)
        assertFalse(vm.top.id in first.stack.map { it.id })
    }

    @Test fun `leaving the screen writes at once, before the debounce`() {
        val first = SeionViewModel(SavedStateHandle(), store(), env(), dispatcher)
        idle()
        first.push(Screen.Albums)
        first.push(Screen.Album("k"))
        first.flush()
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(Screen.Home, Screen.Albums, Screen.Album("k")), store().load()?.stack?.map { it.screen })
    }

    @Test fun `the saved state wins over the file`() {
        file.writeText(NavStore.encode(nav(Screen.Home, Screen.Albums, Screen.Album("k"))))
        val handle = SavedStateHandle(mapOf("seion.nav" to SavedNav.encode(nav(Screen.Home, Screen.Genres))))
        val vm = SeionViewModel(handle, store(), env(), dispatcher)
        assertTrue("no wait when the saved state has the screens", vm.ready)
        idle()
        assertEquals(listOf(Screen.Home, Screen.Genres), vm.stack.map { it.screen })
    }

    @Test fun `a renamed folder lands on its nearest valid parent`() {
        file.writeText(
            NavStore.encode(
                nav(
                    Screen.Home,
                    Screen.FolderRoots,
                    Screen.Folder(root, root),
                    Screen.Folder(root, "$root/Aurora Vale"),
                    Screen.Folder(root, "$root/Aurora Vale/Glass Harbour"),
                    scroll = mapOf(2L to ScrollPos(5, 0), 4L to ScrollPos(9, 0)),
                    nowPlaying = true
                )
            )
        )
        val env = env().apply {
            dirs.remove("$root/Aurora Vale")
            dirs.remove("$root/Aurora Vale/Glass Harbour")
        }
        val vm = coldStart(env)
        assertEquals(listOf(Screen.Home, Screen.FolderRoots, Screen.Folder(root, root)), vm.stack.map { it.screen })
        assertEquals(ScrollPos(5, 0), vm.scrollOf(2))
        assertNull(vm.scrollOf(4))
        assertTrue(vm.nowPlaying)
    }

    @Test fun `the stack is trimmed at its first screen that is gone`() = runTest {
        val e = env().apply {
            albums += "a|b"
            artists += "Aurora Vale"
            genres += null
        }
        suspend fun kept(vararg s: Screen) = NavRestore.restore(nav(*s), e)?.nav?.stack?.map { it.screen }

        // An album no longer in the library: its parents stay, the folder above it goes too.
        assertEquals(
            listOf(Screen.Home, Screen.Albums),
            kept(Screen.Home, Screen.Albums, Screen.Album("gone"), Screen.Folder(root, root))
        )
        assertEquals(
            listOf(Screen.Home, Screen.Artists, Screen.Artist("Aurora Vale"), Screen.Album("a|b")),
            kept(Screen.Home, Screen.Artists, Screen.Artist("Aurora Vale"), Screen.Album("a|b"))
        )
        assertEquals(listOf(Screen.Home, Screen.Genres), kept(Screen.Home, Screen.Genres, Screen.Genre("Jazz")))
        assertEquals(listOf(Screen.Home, Screen.Genres, Screen.Genre(null)), kept(Screen.Home, Screen.Genres, Screen.Genre(null)))
        // A music folder removed from the list: its folders go.
        e.roots = listOf("/storage/0000-0000")
        assertEquals(listOf(Screen.Home, Screen.FolderRoots), kept(Screen.Home, Screen.FolderRoots, Screen.Folder(root, root)))
    }

    @Test fun `a folder must sit inside its music folder, or above it on the same volume`() {
        val dirs = setOf(root, "$root/a", "/storage/emulated/0", "/storage/emulated/0/Download", "/", "/storage")
        fun valid(path: String) = NavRestore.folderValid(root, path, listOf(root), listOf("/storage/emulated/0"), dirs::contains)
        assertTrue(valid(root))
        assertTrue(valid("$root/a"))
        // The breadcrumbs open the volume above the music folder.
        assertTrue(valid("/storage/emulated/0"))
        assertFalse(valid("/storage/emulated/0/Download"))
        assertFalse(valid("/storage"))
        assertFalse(valid("/"))
        assertFalse(valid("$root/../Download"))
        assertFalse(valid("$root/missing"))
        assertFalse(NavRestore.folderValid(root, root, listOf("/storage/0000-0000"), emptyList(), dirs::contains))
    }

    @Test fun `search, the folder picker and the licences are not restored`() = runTest {
        val e = env().apply { albums += "a|b" }
        val restored =
            NavRestore.restore(
                nav(
                    Screen.Home,
                    Screen.Search,
                    Screen.Album("a|b"),
                    Screen.Settings,
                    Screen.MusicFolders,
                    Screen.FolderPicker("/x"),
                    Screen.FolderPicker("/x/y"),
                    scroll = mapOf(2L to ScrollPos(4, 0), 1L to ScrollPos(2, 0))
                ),
                e
            )!!.nav
        // The album opened from a search result stays; Back from it goes to the library home.
        assertEquals(listOf(Screen.Home, Screen.Album("a|b"), Screen.Settings, Screen.MusicFolders), restored.stack.map { it.screen })
        assertEquals(listOf(0, 1, 2, 3), restored.stack.map { it.depth })
        assertEquals(mapOf(2L to ScrollPos(4, 0)), restored.scroll)
        assertEquals(
            listOf(Screen.Home, Screen.Settings),
            NavRestore.restore(nav(Screen.Home, Screen.Settings, Screen.Licenses), e)!!.nav.stack.map { it.screen }
        )
        // Queue, Sound and Settings are cheap and stay.
        assertEquals(
            listOf(Screen.Home, Screen.Queue, Screen.Sound),
            NavRestore.restore(nav(Screen.Home, Screen.Queue, Screen.Sound), e)!!.nav.stack.map { it.screen }
        )
    }

    @Test fun `without file access Seion starts at the library home`() {
        file.writeText(NavStore.encode(nav(Screen.Home, Screen.FolderRoots, Screen.Folder(root, root), nowPlaying = true)))
        val vm = coldStart(env().apply { access = false })
        assertTrue(vm.ready)
        assertEquals(listOf(Screen.Home), vm.stack.map { it.screen })
        assertFalse(vm.nowPlaying)
    }

    @Test fun `no file at all is a first start`() {
        val vm = coldStart()
        assertTrue(vm.ready)
        assertEquals(listOf(Screen.Home), vm.stack.map { it.screen })
    }

    @Test fun `while the index is being rebuilt an album is kept, and checked once the scan is done`() {
        file.writeText(NavStore.encode(nav(Screen.Home, Screen.Albums, Screen.Album("gone"))))
        val env = env().apply { empty = true }
        val vm = coldStart(env)
        assertEquals(
            "not dropped before the library is known",
            listOf(Screen.Home, Screen.Albums, Screen.Album("gone")),
            vm.stack.map {
                it.screen
            }
        )

        env.empty = false
        env.scanDone.complete(Unit)
        idle()
        assertEquals(listOf(Screen.Home, Screen.Albums), vm.stack.map { it.screen })
    }

    @Test fun `an album that the scan confirms stays`() {
        file.writeText(NavStore.encode(nav(Screen.Home, Screen.Albums, Screen.Album("a|b"))))
        val env = env().apply { empty = true }
        val vm = coldStart(env)
        env.albums += "a|b"
        env.empty = false
        env.scanDone.complete(Unit)
        idle()
        assertEquals(listOf(Screen.Home, Screen.Albums, Screen.Album("a|b")), vm.stack.map { it.screen })
    }

    @Test fun `the late check never undoes what the user did meanwhile`() {
        file.writeText(NavStore.encode(nav(Screen.Home, Screen.Albums, Screen.Album("gone"))))
        val env = env().apply { empty = true }
        val vm = coldStart(env)
        vm.push(Screen.Sound)
        env.empty = false
        env.scanDone.complete(Unit)
        idle()
        assertEquals(listOf(Screen.Home, Screen.Albums, Screen.Album("gone"), Screen.Sound), vm.stack.map { it.screen })
    }

    @Test fun `a popped screen's scroll position is forgotten`() {
        val vm = SeionViewModel(SavedStateHandle(), store(), env(), dispatcher)
        idle()
        vm.push(Screen.Songs)
        val songs = vm.top.id
        vm.setScroll(songs, ScrollPos(30, 2))
        vm.pop()
        vm.push(Screen.Albums)
        idle()
        assertNull(vm.scrollOf(songs))
        assertTrue(store().load()!!.scroll.isEmpty())
    }

    @Test fun `a restored album that the start scan removes falls back to its parent instead of an empty page`() {
        // The index still had the album at the cold start (the last scan's snapshot); its files went while Seion was
        // closed, so the scan at start removes it. Keeping the restored page would leave it empty.
        file.writeText(NavStore.encode(nav(Screen.Home, Screen.Artists, Screen.Artist("Aurora Vale"), Screen.Album("gone"))))
        val env = env().apply {
            albums += "gone"
            artists += "Aurora Vale"
        }
        val vm = coldStart(env)
        assertEquals(4, vm.stack.size)

        env.albums -= "gone"
        env.scanDone.complete(Unit)
        idle()
        assertEquals(listOf(Screen.Home, Screen.Artists, Screen.Artist("Aurora Vale")), vm.stack.map { it.screen })
    }

    @Test fun `the start scan's check leaves a restored album that is still there, and never undoes a navigation`() {
        file.writeText(NavStore.encode(nav(Screen.Home, Screen.Albums, Screen.Album("kept"), Screen.Album("gone"))))
        val env = env().apply { albums += setOf("kept", "gone") }
        val vm = coldStart(env)
        vm.push(Screen.Queue)
        env.albums -= "gone"
        env.scanDone.complete(Unit)
        idle()
        assertEquals(
            listOf(Screen.Home, Screen.Albums, Screen.Album("kept"), Screen.Album("gone"), Screen.Queue),
            vm.stack.map {
                it.screen
            }
        )

        file.writeText(NavStore.encode(nav(Screen.Home, Screen.Albums, Screen.Album("kept"))))
        val env2 = env().apply { albums += "kept" }
        val vm2 = coldStart(env2)
        env2.scanDone.complete(Unit)
        idle()
        assertEquals(listOf(Screen.Home, Screen.Albums, Screen.Album("kept")), vm2.stack.map { it.screen })
    }
}
