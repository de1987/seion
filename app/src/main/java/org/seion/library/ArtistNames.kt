package org.seion.library

import java.util.Locale

/**
 * Splits an artist tag into artists and formats them for display. Pure.
 *
 * The scanner stores the platform's single artist string (MediaMetadataRetriever joins nothing for us), so the list is
 * recovered from the separators taggers write. The rules are conservative, because band names contain the same
 * characters ("Simon & Garfunkel", "Earth, Wind & Fire", "AC/DC", "Tyler, the Creator", "Maiara e Maraisa"):
 *
 * 0. **Whitespace** of any kind (tabs, newlines, no-break and thin spaces, the ideographic space) is collapsed to one
 *    space first, so every pattern below matches single spaces only (linear time, no backtracking on long runs, and
 *    the same result on the JVM and on Android's ICU, whose `\s` differ).
 * 1. **Strong separators always split**: `;` (also the full-width `；`), a NUL (ID3v2.4 / Vorbis multi-value), a
 *    slash with spaces (` / `), and a featuring marker: `feat.`, `ft.`, `featuring` (any case), bare `feat`/`ft` in
 *    lower case, bare `Feat`/`Ft` only when a capitalised name follows ("Little Feat" stays), and `w/`. A bracketed
 *    group "(feat. X)" anywhere becomes its own name; a bracket left unbalanced by a marker is dropped.
 * 2. **A bare slash** splits a segment only when one of its parts has two or more words and every part has at least
 *    three characters: "Artist A/Artist B" splits; "AC/DC", "AC/DC Tribute Band" and "Love/Hate" don't.
 * 3. **Weak separators**: commas (`, `) and joiners (` & `, ` and `, ` e `, ` x `, ` × `).
 *    - A piece glues back to the name before it when it starts with a lower-case function word ("Tyler, the Creator",
 *      "Sly & the Family Stone"), or with "The" after a joiner ("Nick Cave & The Bad Seeds").
 *    - **Duos**: two pieces joined by a joiner, with no comma between them, are one name when either piece is a single
 *      word ("Maiara e Maraisa", "Zé Neto e Cristiano", "Hall & Oates", "Mumford & Sons"); two multi-word pieces are two
 *      people ("Bruno Souza e Carla Dias"). Three or more pieces chained by joiners are a list ("A & B & C").
 *    - The split is kept when there are **three or more pieces with at least one multi-word piece** (counted before the
 *      duos are glued), or when **every comma-separated group is a duo** ("Jorge e Mateus, Henrique e Juliano").
 *      Otherwise the segment stays whole: single-word comma bands ("Earth, Wind & Fire", "Crosby, Stills, Nash &
 *      Young", "Peter, Paul and Mary") and any two names ("Ana Lima & Bruno Souza", shown as written).
 *    The rule runs per strong segment, so "Simon & Garfunkel feat. Ana Lima" keeps the duo whole.
 * 4. Names are trimmed of spaces and stray `,`/`&` at their ends, empty ones dropped, duplicates removed ignoring case
 *    (first spelling wins), order kept. `<unknown>` (MediaStore's placeholder) counts as no artist.
 *
 * Not handled: commas without a space ("A,B,C") and CJK list marks such as `、` (kept as one name).
 */
object ArtistNames {
    // Every whitespace and invisible space, collapsed to one ' ' before matching (rule 0).
    private val SPACES = Regex("""[\s\u00A0\u1680\u2000-\u200B\u202F\u205F\u3000\uFEFF]+""")
    private const val MARKER = """(?:(?i:feat\.|ft\.|featuring)|feat|ft|Feat(?= \p{Lu})|Ft(?= \p{Lu})|w/)"""

    // "(feat. X)" / "[ft. X]" anywhere: X becomes its own name.
    private val BRACKETED = Regex("""[(\[] ?$MARKER ([^()\[\]]*)[)\]]""")
    private val STRONG = Regex(""" ?[;；\u0000] ?| / | (?:[(\[] ?)?$MARKER """)
    private val WEAK = Regex(""", | (?:&|and|e|x|×) """)
    private val EDGES = Regex("""^[ ,&×]+|[ ,&×]+$""")

    /** Lower-case words that continue a name rather than start one ("Tyler, the Creator"). */
    private val FUNCTION_WORDS =
        setOf(
            "the", "a", "an", "and", "of", "his", "her", "their", "o", "os", "as", "do", "da", "dos", "das", "e", "y",
            "la", "le", "les", "el", "los", "las", "de", "del", "di", "von", "van"
        )

    fun split(raw: String?): List<String> {
        val text = raw?.replace(SPACES, " ")?.trim().orEmpty()
        if (text.isEmpty() || text.equals("<unknown>", ignoreCase = true)) return emptyList()
        val names = text.replace(BRACKETED, ";$1;")
            .split(STRONG)
            .map(::clean)
            .flatMap(::splitBareSlash)
            .flatMap(::splitWeak)
            .map(::clean)
            .filter { it.isNotEmpty() }
        val seen = HashSet<String>()
        return names.filter { seen.add(it.lowercase(Locale.ROOT)) }
    }

    /** Trims spaces and stray separators, and drops a bracket a marker left unbalanced: "Daft Punk (" / "Williams)". */
    private fun clean(s: String): String {
        var t = s.replace(EDGES, "")
        for ((open, close) in listOf('(' to ')', '[' to ']')) {
            if (t.endsWith(close) && t.count { it == open } < t.count { it == close }) t = t.dropLast(1).trim()
            if (t.endsWith(open)) t = t.dropLast(1).trim()
            if (t.startsWith(open) && t.count { it == open } > t.count { it == close }) t = t.drop(1).trim()
        }
        return t.replace(EDGES, "")
    }

    private fun words(s: String) = s.trim().split(' ').count { it.isNotEmpty() }

    private fun splitBareSlash(segment: String): List<String> {
        if ('/' !in segment) return listOf(segment)
        val parts = segment.split('/').map { it.trim() }
        return if (parts.any { words(it) >= 2 } && parts.all { it.length >= 3 }) parts else listOf(segment)
    }

    /** One piece of a segment as a range of it, and whether a comma (not a joiner) comes before it. */
    private class Piece(var range: IntRange, val afterComma: Boolean)

    private fun splitWeak(segment: String): List<String> {
        val pieces = mutableListOf<Piece>()
        var start = 0
        var afterComma = false
        fun piece(end: Int) {
            val text = segment.substring(start, end).trim()
            val first = text.substringBefore(' ')
            val glue = pieces.isNotEmpty() &&
                (first in FUNCTION_WORDS || (!afterComma && first.equals("the", ignoreCase = true)))
            if (glue) {
                pieces.last().range = pieces.last().range.first until end
            } else {
                pieces += Piece(start until end, afterComma)
            }
        }
        for (m in WEAK.findAll(segment)) {
            piece(m.range.first)
            start = m.range.last + 1
            afterComma = m.value == ", "
        }
        piece(segment.length)

        fun text(p: Piece) = segment.substring(p.range).trim()
        if (pieces.size < 2) return listOf(segment)
        // Comma-separated groups; inside a group the pieces are chained by joiners.
        val groups = mutableListOf<MutableList<Piece>>()
        for (p in pieces) if (groups.isEmpty() || p.afterComma) groups += mutableListOf(p) else groups.last() += p
        val names = groups.flatMap { g ->
            val duo = g.size == 2 && g.any { words(text(it)) == 1 }
            if (duo) listOf(segment.substring(g[0].range.first..g[1].range.last).trim()) else g.map(::text)
        }
        val list = pieces.size >= 3 && pieces.any { words(text(it)) >= 2 }
        val duos = groups.size >= 2 && groups.all { it.size >= 2 }
        return if (list || duos) names else listOf(segment)
    }

    /** The shown line: everyone, joined with ", " ("Ana Lima, Bruno Souza, Carla Dias, Davi Rocha"). */
    fun display(names: List<String>): String = names.joinToString(", ")

    /** Everyone, for TalkBack and the full list: "A, B, C and D"; [lastJoin] is the localised "%1$s and %2$s". */
    fun spoken(names: List<String>, lastJoin: (String, String) -> String): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> lastJoin(names.dropLast(1).joinToString(", "), names.last())
    }
}
