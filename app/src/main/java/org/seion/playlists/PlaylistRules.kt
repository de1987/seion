package org.seion.playlists

/** Playlist names (pure, unit-tested). */
object PlaylistNames {
    /** The longest name accepted (the field stops there too). */
    const val MAX_LENGTH = 100

    enum class Problem { EMPTY, TOO_LONG, DUPLICATE }

    private val SPACES = Regex("\\s+")

    /** What is stored: trimmed, with runs of white space (tabs, new lines from a paste) folded into one space. */
    fun clean(raw: String): String = raw.trim().replace(SPACES, " ")

    /**
     * Why [raw] can't be used, or null when it can. [others] are the names of the *other* playlists (a rename passes the
     * list without its own name, so changing only the case is allowed). Names are compared ignoring case.
     */
    fun problem(raw: String, others: Collection<String>): Problem? {
        val name = clean(raw)
        return when {
            name.isEmpty() -> Problem.EMPTY
            name.length > MAX_LENGTH -> Problem.TOO_LONG
            others.any { it.equals(name, ignoreCase = true) } -> Problem.DUPLICATE
            else -> null
        }
    }

    /** The name New playlist suggests: [format] (`Playlist %d`) with the smallest N ≥ 1 that is free (ignoring case). */
    fun suggest(existing: Collection<String>, format: (Int) -> String): String {
        val taken = existing.map { it.lowercase() }.toSet()
        var n = 1
        while (format(n).lowercase() in taken) n++
        return format(n)
    }

    /** An imported file's name made free: "Name", else "Name (2)", "Name (3)", …; never longer than [MAX_LENGTH]. */
    fun unique(raw: String, existing: Collection<String>, fallback: String): String {
        val base = clean(raw).ifEmpty { fallback }.take(MAX_LENGTH)
        val taken = existing.map { it.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var n = 2
        while (true) {
            val suffix = " ($n)"
            val candidate = base.take(MAX_LENGTH - suffix.length).trimEnd() + suffix
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }
}

/** How the Playlists screen is ordered (persisted in the settings). */
enum class PlaylistSort {
    /** A–Z ignoring case and a leading "The " (the library's sort rule). */
    NAME,

    /** The most recently created or changed first. */
    RECENT
}

/** Pure list rules of playlists (unit-tested). */
object PlaylistRules {
    /** The result of adding [toAdd] (in order); [skipped] songs were already in the playlist or twice in the batch. */
    data class AddPlan<T>(val toAdd: List<T>, val skipped: Int)

    /**
     * Adding to a playlist skips a song that is already in it (adding an album twice must not double
     * it), and a song that appears twice in the same batch; what is skipped is reported, never silent.
     */
    fun <T> planAdd(existingPaths: Collection<String>, incoming: List<T>, path: (T) -> String): AddPlan<T> {
        val seen = HashSet<String>(existingPaths)
        val out = ArrayList<T>(incoming.size)
        for (item in incoming) if (seen.add(path(item))) out += item
        return AddPlan(out, incoming.size - out.size)
    }

    /** Moves the item at [from] to [to] (the others shift); out-of-range or equal positions change nothing. */
    fun <T> move(items: List<T>, from: Int, to: Int): List<T> {
        if (from !in items.indices || to !in items.indices || from == to) return items
        val m = items.toMutableList()
        m.add(to, m.removeAt(from))
        return m
    }

    /** The library's sort key for names: a leading "The " is ignored. */
    fun sortKey(name: String): String = if (name.length > 4 && name.startsWith("the ", ignoreCase = true)) name.substring(4) else name

    /** [rows] in [sort] order; ties keep the creation order (id). */
    fun <T> sorted(rows: List<T>, sort: PlaylistSort, name: (T) -> String, changedAt: (T) -> Long, id: (T) -> Long): List<T> = when (sort) {
        PlaylistSort.NAME ->
            rows.sortedWith(
                compareBy<T, String>(String.CASE_INSENSITIVE_ORDER) { sortKey(name(it)) }.thenBy { name(it) }.thenBy(id)
            )
        PlaylistSort.RECENT -> rows.sortedWith(compareByDescending(changedAt).thenByDescending(id))
    }

    /**
     * The covers of a playlist's mosaic: the first item of each of the first four different albums, in playlist order.
     * Fewer than four albums show one cover (the first song's); an empty list shows the playlist glyph.
     */
    fun <T> mosaic(items: List<T>, albumKey: (T) -> String): List<T> {
        val firsts = ArrayList<T>(4)
        val keys = HashSet<String>()
        for (item in items) {
            if (keys.add(albumKey(item))) firsts += item
            if (firsts.size == 4) return firsts
        }
        return firsts.take(1)
    }

    /**
     * What Play / Shuffle / a tap start: the playable songs only (a missing file is skipped), and where in that list the
     * tapped row [tapped] is. A tap on an unavailable row starts nothing (null).
     */
    fun <T> playable(items: List<T>, tapped: Int, available: (T) -> Boolean): Pair<List<T>, Int>? {
        if (tapped !in items.indices) return null
        if (!available(items[tapped])) return null
        val list = items.filter(available)
        val start = items.subList(0, tapped).count(available)
        return list to start
    }
}
