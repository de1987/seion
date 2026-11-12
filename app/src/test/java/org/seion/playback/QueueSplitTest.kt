package org.seion.playback

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Saving battery: the paths are written only when the queue changes (and on pause, stop, close); while playing,
 * only the current track and position go to a small file of their own every few seconds. A kill restores from both;
 * a queue.json written in the older single-file format (no position file) restores as it was.
 */
class QueueSplitTest {
    @get:Rule val tmp = TemporaryFolder()

    private val paths = (1..400).map { "/storage/emulated/0/Music/Album $it/Track $it.flac" }
    private val full = SavedQueue(paths, index = 3, positionMs = 12_000)

    private fun store() = QueueStore(File(tmp.root, "queue.json"))

    @Test fun `a position saved while playing is restored over the last full save`() {
        val store = store().apply { save(full) }
        assertTrue(store.savePosition(SavedPosition(paths[4], index = 4, positionMs = 61_000)))
        assertEquals(full.copy(index = 4, positionMs = 61_000), store.load())
    }

    @Test fun `saving the position never rewrites the paths`() {
        val store = store().apply { save(full) }
        val queueFile = File(tmp.root, "queue.json")
        val before = queueFile.readBytes()
        store.savePosition(SavedPosition(paths[3], index = 3, positionMs = 20_000))
        assertArrayEquals(before, queueFile.readBytes())
        val written = tmp.root.listFiles().orEmpty().filter { it.name != "queue.json" && !it.name.endsWith(".tmp") }
        assertEquals(1, written.size)
        assertTrue("the position file stays small: ${written.single().length()} B", written.single().length() < 1_000)
    }

    @Test fun `a full save also writes the position, so an older position never wins`() {
        val store = store().apply { save(full) }
        store.savePosition(SavedPosition(paths[3], index = 3, positionMs = 90_000))
        val paused = full.copy(index = 7, positionMs = 2_000)
        store.save(paused)
        assertEquals(paused, store.load())
    }

    @Test fun `a position that does not match the saved paths is ignored`() {
        val store = store().apply { save(full) }
        // The queue changed (a new list) after the position was written, and its full save never landed.
        store.savePosition(SavedPosition("/elsewhere/Other.flac", index = 4, positionMs = 30_000))
        assertEquals(full, store.load())
    }

    @Test fun `a single-file queue without a position file restores as it was`() {
        val file = File(tmp.root, "queue.json")
        // The older single-file format (kotlinx.serialization, SavedQueue(paths, index, positionMs)).
        file.writeText(
            """{"paths":["/storage/emulated/0/Music/A/01.flac","/storage/emulated/0/Music/A/02.flac"],"index":1,"positionMs":135339}"""
        )
        assertEquals(
            SavedQueue(listOf("/storage/emulated/0/Music/A/01.flac", "/storage/emulated/0/Music/A/02.flac"), 1, 135_339),
            QueueStore(file).load()
        )
    }

    @Test fun `a single-file queue plus a position file restores both`() {
        val file = File(tmp.root, "queue.json")
        file.writeText("""{"paths":["/m/a.flac","/m/b.flac","/m/c.flac"],"index":0,"positionMs":1000}""")
        val store = QueueStore(file)
        store.savePosition(SavedPosition("/m/c.flac", index = 2, positionMs = 44_000))
        assertEquals(SavedQueue(listOf("/m/a.flac", "/m/b.flac", "/m/c.flac"), 2, 44_000), store.load())
    }

    @Test fun `a corrupt position file falls back to the full save`() {
        val store = store().apply { save(full) }
        tmp.root.listFiles().orEmpty().filter { it.name != "queue.json" }.forEach { it.writeText("{\"path\":\"/m") }
        assertEquals(full, store.load())
    }

    @Test fun `no queue file means no saved queue, whatever the position file says`() {
        val store = store()
        store.savePosition(SavedPosition("/m/a.flac", index = 0, positionMs = 5_000))
        assertEquals(null, store.load())
    }

    @Test fun `the position is taken from a queue the way the service saves it`() {
        assertEquals(SavedPosition(paths[3], 3, 12_000), QueueSplit.position(full))
        assertEquals(null, QueueSplit.position(full.copy(index = -1)))
        assertEquals(full.copy(index = 5, positionMs = 1), QueueSplit.merge(full, SavedPosition(paths[5], 5, 1)))
        assertEquals(full, QueueSplit.merge(full, null))
    }

    @Test fun `the persister writes positions off the caller's thread`() {
        val store = store().apply { save(full) }
        val dispatcher = StandardTestDispatcher()
        val persister = QueuePersister(store, CoroutineScope(SupervisorJob() + dispatcher), io = dispatcher)
        persister.savePosition(SavedPosition(paths[3], 3, 50_000))
        assertEquals(full, store.load())
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(full.copy(positionMs = 50_000), store.load())
    }

    // The paths in queue.json can be older than the position (a queue change, then a kill inside the
    // 1 s settle). The position's track is looked up in the saved paths rather than dropped for the stale paused place.
    private val abcde = listOf("/m/a.flac", "/m/b.flac", "/m/c.flac", "/m/d.flac", "/m/e.flac")

    @Test fun `a song removed before the current one, then a kill, restores the playing song, not the old paused place`() {
        // Paused on d (index 3, full save), played on to e (index 4), the first song removed: e is index 3 now.
        val saved = SavedQueue(abcde, index = 3, positionMs = 10_000)
        assertEquals(SavedQueue(abcde, 4, 95_000), QueueSplit.merge(saved, SavedPosition("/m/e.flac", 3, 95_000)))
    }

    @Test fun `a song inserted before the current one, then a kill, restores the playing song`() {
        val saved = SavedQueue(abcde, index = 1, positionMs = 10_000)
        // c was index 2; an insert at the top made it index 3 in the queue that was never written.
        assertEquals(SavedQueue(abcde, 2, 40_000), QueueSplit.merge(saved, SavedPosition("/m/c.flac", 3, 40_000)))
    }

    @Test fun `a reorder, then a kill, restores the playing song`() {
        val saved = SavedQueue(abcde, index = 0, positionMs = 1_000)
        // a dragged to the end while playing: the position says index 4, the saved paths still have it first.
        assertEquals(SavedQueue(abcde, 0, 70_000), QueueSplit.merge(saved, SavedPosition("/m/a.flac", 4, 70_000)))
    }

    @Test fun `with the song queued twice, the occurrence nearest the saved index wins`() {
        val dup = listOf("/m/x.flac", "/m/a.flac", "/m/y.flac", "/m/z.flac", "/m/a.flac", "/m/w.flac")
        val saved = SavedQueue(dup, index = 0, positionMs = 0)
        assertEquals(4, QueueSplit.merge(saved, SavedPosition("/m/a.flac", 5, 1)).index)
        assertEquals(1, QueueSplit.merge(saved, SavedPosition("/m/a.flac", 2, 1)).index)
        assertEquals(4, QueueSplit.merge(saved, SavedPosition("/m/a.flac", 999, 1)).index)
    }

    @Test fun `a position whose song is not in the saved paths keeps the full save`() {
        val saved = SavedQueue(abcde, index = 2, positionMs = 5_000)
        assertEquals(saved, QueueSplit.merge(saved, SavedPosition("/m/new list.flac", 0, 30_000)))
    }

    @Test fun `a failed queue write leaves the position file alone`() {
        val store = store().apply { save(full) }
        store.savePosition(SavedPosition(paths[3], 3, 77_000))
        // A directory where the queue's temporary file goes: only the queue write fails, the position write could work.
        val blocker = File(tmp.root, "queue.json.tmp").apply { mkdir() }
        assertEquals(false, store.save(SavedQueue(listOf("/m/other.flac"), 0, 1_000)))
        blocker.delete()
        assertEquals(full.copy(positionMs = 77_000), store.load())
    }

    // A cover found online, a chosen cover or a new shuffle order changes the timeline but not the
    // paths; those must not rewrite the whole queue file.
    @Test fun `a full save with the same paths writes only the position`() {
        val store = store()
        val dispatcher = StandardTestDispatcher()
        val persister = QueuePersister(store, CoroutineScope(SupervisorJob() + dispatcher), io = dispatcher)
        val queueFile = File(tmp.root, "queue.json")
        persister.save(full)
        dispatcher.scheduler.advanceUntilIdle()
        val written = queueFile.lastModified()
        queueFile.setLastModified(written - 60_000)
        persister.save(full.copy(index = 5, positionMs = 3_000)) // same paths: the position only
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(written - 60_000, queueFile.lastModified())
        assertEquals(full.copy(index = 5, positionMs = 3_000), store.load())
        persister.save(full.copy(paths = paths + "/m/new.flac", index = 0, positionMs = 0)) // changed: the paths too
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(paths.size + 1, store.load()?.paths?.size)
    }

    @Test fun `paths restored as they were saved count as written`() {
        val store = store().apply { save(full) }
        val dispatcher = StandardTestDispatcher()
        val persister = QueuePersister(store, CoroutineScope(SupervisorJob() + dispatcher), io = dispatcher)
        val queueFile = File(tmp.root, "queue.json")
        queueFile.setLastModified(queueFile.lastModified() - 60_000)
        val stamp = queueFile.lastModified()
        persister.restored(full.paths)
        persister.save(full.copy(positionMs = 9_000))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(stamp, queueFile.lastModified())
        assertEquals(full.copy(positionMs = 9_000), store.load())
    }

    @Test fun `a failed full save is tried again next time`() {
        val store = store()
        val dispatcher = StandardTestDispatcher()
        val persister = QueuePersister(store, CoroutineScope(SupervisorJob() + dispatcher), io = dispatcher)
        val blocker = File(tmp.root, "queue.json.tmp").apply { mkdir() }
        persister.save(full)
        dispatcher.scheduler.advanceUntilIdle()
        blocker.delete()
        persister.save(full)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(full, store.load())
    }
}
