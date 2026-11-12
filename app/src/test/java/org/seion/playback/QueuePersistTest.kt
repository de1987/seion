package org.seion.playback

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The service's last save, made right before its scope is cancelled, must reach the disk. */
class QueuePersistTest {
    @get:Rule val tmp = TemporaryFolder()

    private val before = SavedQueue(listOf("/m/a.flac", "/m/b.flac"), index = 0, positionMs = 135_339)
    private val seeked = before.copy(positionMs = 50_464)

    @Test fun `the save made as the service closes is written although the scope is cancelled right after`() {
        val store = QueueStore(File(tmp.root, "queue.json")).apply { save(before) }
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val persister = QueuePersister(store, scope, io = dispatcher)

        // onDestroy: the final save, then the scope goes (the order PlaybackService uses).
        persister.saveNow(seeked)
        scope.cancel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(seeked, store.load())
    }

    @Test fun `routine saves still go through the scope`() {
        val store = QueueStore(File(tmp.root, "queue.json")).apply { save(before) }
        val dispatcher = StandardTestDispatcher()
        val persister = QueuePersister(store, CoroutineScope(SupervisorJob() + dispatcher), io = dispatcher)

        persister.save(seeked)
        assertEquals(before, store.load()) // not written on the caller's thread
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(seeked, store.load())
    }

    @Test fun `a truncated or corrupt queue file reads as no saved queue, never a crash`() {
        val file = File(tmp.root, "queue.json")
        val store = QueueStore(file)
        file.writeText("{\"paths\":[\"/m/a.flac\",\"/m/b")
        assertEquals(null, store.load())
        file.writeText("")
        assertEquals(null, store.load())
        file.writeBytes(byteArrayOf(0, 1, 2, -1))
        assertEquals(null, store.load())
    }

    @Test fun `a failed write is reported, not timed as if it had worked`() {
        val dir = File(tmp.root, "gone")
        val persister = QueuePersister(QueueStore(File(dir, "queue.json")), CoroutineScope(SupervisorJob() + StandardTestDispatcher()))
        assertEquals(null, persister.saveNow(seeked)) // the parent directory does not exist
    }
}
