package org.seion.library

/** A file the scanner found on disk. */
data class FoundFile(val path: String, val lastModified: Long, val sizeBytes: Long)

/** What one scan must do: read tags for [toRead] (new or changed files) and drop [toRemove] from the index. */
data class ScanPlan(val toRead: List<FoundFile>, val toRemove: List<String>, val unchanged: Int)

/**
 * The incremental rule (pure, unit-tested): a file is re-read only when it is new or its lastModified changed;
 * indexed paths that are no longer found (deleted, moved, or outside the configured roots) are removed.
 */
object ScanPlanner {
    fun plan(indexed: Map<String, Long>, found: List<FoundFile>): ScanPlan {
        val seen = HashSet<String>(found.size)
        val toRead = ArrayList<FoundFile>()
        var unchanged = 0
        for (f in found) {
            if (!seen.add(f.path)) continue
            val stamp = indexed[f.path]
            if (stamp == null || stamp != f.lastModified) toRead += f else unchanged++
        }
        val toRemove = indexed.keys.filter { it !in seen }
        return ScanPlan(toRead, toRemove.sorted(), unchanged)
    }
}
