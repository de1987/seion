package org.seion.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A headset/Bluetooth Play after Seion was closed resumes with the app restore's rule (`onPlaybackResumption`). */
class QueueResumeTest {
    private val saved = SavedQueue(listOf("/a", "/b", "/c", "/d"), index = 3, positionMs = 42_000)

    @Test fun `a missing file before the current track keeps the same track and position`() {
        // Saved on /c (index 2); /b gone: still /c, now at index 1 (not /d, the old index, at /c's position).
        val s = saved.copy(index = 2)
        assertEquals(RestorePlan(listOf("/a", "/c", "/d"), 1, 42_000), QueueRestore.plan(s) { it != "/b" })
    }

    @Test fun `the current track gone starts the next existing one from 0, as the app restore does`() {
        val s = saved.copy(index = 1)
        assertEquals(RestorePlan(listOf("/a", "/c", "/d"), 1, 0), QueueRestore.plan(s) { it != "/b" })
    }

    @Test fun `nothing readable resumes nothing`() {
        assertNull(QueueRestore.plan(saved) { false })
    }
}

/** The receiver's gate: start a foreground service for a media key only when it can really play. */
class QueueResumeGateTest {
    private val saved = SavedQueue(listOf("/a", "/b"), index = 1, positionMs = 1_000)

    @Test fun `no file access starts nothing and reads nothing`() {
        var loaded = false
        assertFalse(
            QueueResume.mayStart({ false }, {
                loaded = true
                saved
            }) { true }
        )
        assertFalse(loaded)
    }

    @Test fun `no saved queue starts nothing`() {
        assertFalse(QueueResume.mayStart({ true }, { null }) { true })
    }

    @Test fun `a saved queue whose files are all gone starts nothing`() {
        assertFalse(QueueResume.mayStart({ true }, { saved }) { false })
    }

    @Test fun `a saved queue with a readable file starts, checking files only until the first one exists`() {
        val checked = ArrayList<String>()
        assertTrue(
            QueueResume.mayStart({ true }, { saved }) {
                checked += it
                true
            }
        )
        assertEquals(listOf("/a"), checked)
    }
}
