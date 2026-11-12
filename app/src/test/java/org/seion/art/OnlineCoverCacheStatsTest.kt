package org.seion.art

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings › Album art › Clear online cover cache shows "38 covers · 12.4 MB": covers, every entry, bytes. */
class OnlineCoverCacheStatsTest {
    @Test fun `covers, entries and size, and an empty or missing folder is empty`() {
        val dir = Files.createTempDirectory("covers").toFile()
        val cache = OnlineCoverCache(dir)
        assertEquals(OnlineCoverCache.Stats(0, 0, 0), OnlineCoverCache(java.io.File(dir, "none")).stats())
        cache.putHit("Aurora Vale", "Glass Harbour", ByteArray(1000))
        cache.putHit("Kelp Choir", "Low Light", ByteArray(500))
        cache.putMiss("Nobody", "Nothing")
        val stats = cache.stats()
        assertEquals(2, stats.covers)
        assertEquals(3, stats.entries)
        assertEquals(1500L + java.io.File(dir, dir.list()!!.first { it.endsWith(".miss") }).length(), stats.bytes)
        cache.clear()
        assertEquals(OnlineCoverCache.Stats(0, 0, 0), cache.stats())
    }
}
