package org.seion.art

import java.text.Normalizer
import java.util.Locale

/**
 * Whether an online candidate (artist credit + album title) is the album a tag names: both are normalised (case,
 * accents, punctuation, edition suffixes) and compared by similarity.
 * Pure, unit-tested. A wrong cover is worse than none: the thresholds are deliberately strict.
 */
object CoverMatch {
    enum class Script { HANGUL, KANA, HAN, LATIN, OTHER }

    const val ARTIST_MIN = 0.80
    const val ALBUM_MIN = 0.85
    const val CROSS_SCRIPT_ALBUM_MIN = 0.95
    const val CROSS_SCRIPT_MIN_LATIN = 12
    const val CROSS_SCRIPT_MIN_CJK = 2

    // Case-insensitive only: Android's ICU regex rejects the JVM-only Unicode-class flag (IcuRegexCompatTest).
    private const val U = "(?i)"
    private val BRACKETS = Regex("[(\\[{（［【〔「『<＜][^)\\]}）］】〕」』>＞]*[)\\]}）］】〕」』>＞]")
    private val DASH_SUFFIX =
        Regex(
            "$U\\s+[-–—]\\s+(single|ep|the\\s+\\d+[\\p{L}\\p{N}_]*\\s+album|\\d+[\\p{L}\\p{N}_]*\\s+(full\\s+|mini\\s+)?album.*|remaster.*|deluxe.*|ao vivo.*|live.*)$"
        )
    private val FEAT = Regex("$U\\s+(feat\\.?|ft\\.?|featuring|with|prod\\.?)\\s+.*$")
    private val SPLIT = Regex("$U\\s*(?:,|&|＆|/|;| and | e | x | with | feat\\.? | ft\\.? |、)\\s*")
    private val APOSTROPHES = Regex("[‘’'`´]")

    /** Candidate titles with these words are karaoke, covers or tributes: rejected unless the tag says so too. */
    private val VERSION_WORDS =
        Regex(
            "$U(karaoke|カラオケ|노래방|instrumental|\\binst\\.|\\bMR\\b|tribute|made famous|in the style of|originally performed|" +
                "\\bcovers?\\b|カバー|커버|原曲歌手|オリジナルアーティスト|lullaby|8-bit|music box|オルゴール|piano version)"
        )
    private val SINGLE_OR_EP = Regex("$U\\s+-\\s+(single|ep)$")

    private fun nfkc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC)

    /**
     * NFKC (full-width Latin, half-width kana), lower case, Latin diacritics dropped (kana dakuten kept), & -> "and",
     * apostrophes deleted, every other non-letter/digit -> one space.
     */
    fun fold(s: String): String {
        val decomposed = Normalizer.normalize(nfkc(s), Normalizer.Form.NFD)
        val kept = StringBuilder(decomposed.length)
        var lastBase = -1
        var i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            val mark = Character.getType(cp) == Character.NON_SPACING_MARK.toInt()
            if (mark && lastBase in 0 until 0x250) continue
            if (!mark) lastBase = cp
            kept.appendCodePoint(cp)
        }
        var t = Normalizer.normalize(kept, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        t = t.replace("&", " and ").replace("＋", "+")
        t = APOSTROPHES.replace(t, "")
        val out = StringBuilder(t.length)
        var space = false
        i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            val word =
                Character.isLetterOrDigit(cp) ||
                    cp == '_'.code ||
                    cp == '+'.code ||
                    type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt()
            if (word) {
                if (space && out.isNotEmpty()) out.append(' ')
                space = false
                out.appendCodePoint(cp)
            } else {
                space = true
            }
        }
        return out.toString()
    }

    /** The album title without bracketed segments and edition/format dash suffixes ("Rumours (Remastered)" -> "Rumours"). */
    fun baseTitle(s: String): String {
        val n = nfkc(s)
        val stripped = BRACKETS.replace(DASH_SUFFIX.replace(n, ""), "").trim()
        return stripped.ifEmpty { s }
    }

    /** The credit without a featuring/with/prod. tail. */
    fun baseArtist(s: String): String = FEAT.replace(nfkc(s), "").trim()

    fun artistParts(s: String): List<String> = SPLIT.split(baseArtist(s)).filter { it.isNotBlank() }

    /** Ratcliff/Obershelp (Python's difflib.SequenceMatcher.ratio) of the folded strings. */
    fun sim(a: String, b: String): Double {
        val fa = fold(a)
        val fb = fold(b)
        if (fa.isEmpty() || fb.isEmpty()) return 0.0
        if (fa == fb) return 1.0
        return ratio(fa, fb)
    }

    fun albumSim(tag: String, candidate: String): Double = maxOf(sim(tag, candidate), sim(baseTitle(tag), baseTitle(candidate)))

    fun artistSim(tag: String, candidate: String): Double {
        var best = maxOf(sim(tag, candidate), sim(baseArtist(tag), baseArtist(candidate)))
        val qs = artistParts(candidate)
        for (p in artistParts(tag)) for (q in qs) best = maxOf(best, 0.98 * sim(p, q))
        return best
    }

    /** Any Hangul -> HANGUL; else any kana -> KANA; else any CJK ideograph -> HAN; else any letter -> LATIN. */
    fun scriptOf(s: String): Script {
        var kana = false
        var han = false
        var latin = false
        var i = 0
        while (i < s.length) {
            val o = s.codePointAt(i)
            i += Character.charCount(o)
            when {
                o in 0xAC00..0xD7AF || o in 0x1100..0x11FF || o in 0x3130..0x318F -> return Script.HANGUL
                o in 0x3040..0x30FF || o in 0x31F0..0x31FF || o in 0xFF66..0xFF9D -> kana = true
                o in 0x4E00..0x9FFF || o in 0x3400..0x4DBF -> han = true
                Character.isLetter(o) -> latin = true
            }
        }
        return when {
            kana -> Script.KANA
            han -> Script.HAN
            latin -> Script.LATIN
            else -> Script.OTHER
        }
    }

    /** Kana, han and hangul count as one group. */
    fun isCjk(s: Script) = s == Script.KANA || s == Script.HAN || s == Script.HANGUL

    private fun group(s: String): Int = when (val sc = scriptOf(s)) {
        Script.LATIN -> 1
        Script.OTHER -> 0
        else -> if (isCjk(sc)) 2 else 0
    }

    /** One artist name Latin and the other CJK/Hangul. */
    fun crossScript(a: String, b: String): Boolean {
        val ga = group(a)
        val gb = group(b)
        return ga != 0 && gb != 0 && ga != gb
    }

    /** The candidate's title is a karaoke/cover/tribute/lullaby release and the tag's isn't. */
    fun versionRejected(tagAlbum: String, candidateAlbum: String): Boolean =
        VERSION_WORDS.containsMatchIn(candidateAlbum) && !VERSION_WORDS.containsMatchIn(tagAlbum)

    /** Whether the candidate passes the match rule; its score (albumSim + artistSim) when it does, else null. */
    fun score(tagArtist: String, tagAlbum: String, candidateArtist: String, candidateAlbum: String): Double? {
        if (versionRejected(tagAlbum, candidateAlbum)) return null
        val album = albumSim(tagAlbum, candidateAlbum)
        val artist = artistSim(tagArtist, candidateArtist)
        if (artist >= ARTIST_MIN && album >= ALBUM_MIN) return album + artist
        if (crossScript(tagArtist, candidateArtist) && album >= CROSS_SCRIPT_ALBUM_MIN) {
            val t = fold(baseTitle(tagAlbum))
            val need = if (isCjk(scriptOf(t))) CROSS_SCRIPT_MIN_CJK else CROSS_SCRIPT_MIN_LATIN
            if (t.codePointCount(0, t.length) >= need) return album + artist
        }
        return null
    }

    fun accepts(tagArtist: String, tagAlbum: String, candidateArtist: String, candidateAlbum: String): Boolean =
        score(tagArtist, tagAlbum, candidateArtist, candidateAlbum) != null

    /**
     * The passing candidates, best first: highest albumSim + artistSim; on a tie a title not ending in " - Single" /
     * " - EP", then the source's own order.
     */
    fun <T> ranked(tagArtist: String, tagAlbum: String, candidates: List<T>, artistOf: (T) -> String, albumOf: (T) -> String): List<T> =
        candidates
            .mapIndexedNotNull { i, c -> score(tagArtist, tagAlbum, artistOf(c), albumOf(c))?.let { Triple(c, it, i) } }
            .sortedWith(
                compareByDescending<Triple<T, Double, Int>> { it.second }
                    .thenBy { if (SINGLE_OR_EP.containsMatchIn(albumOf(it.first))) 1 else 0 }
                    .thenBy { it.third }
            ).map { it.first }

    /** difflib's ratio: 2·M / (|a| + |b|), M = the matching characters found by recursive longest common blocks. */
    internal fun ratio(a: String, b: String): Double {
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        if (x.isEmpty() && y.isEmpty()) return 1.0
        return 2.0 * matching(x, 0, x.size, y, 0, y.size) / (x.size + y.size)
    }

    private fun matching(a: IntArray, alo: Int, ahi: Int, b: IntArray, blo: Int, bhi: Int): Int {
        if (alo >= ahi || blo >= bhi) return 0
        // Longest common block; ties go to the earliest in a, then in b (difflib's find_longest_match without junk).
        var bestI = alo
        var bestJ = blo
        var bestK = 0
        var prev = IntArray(bhi - blo + 1)
        var cur = IntArray(bhi - blo + 1)
        for (i in alo until ahi) {
            for (j in blo until bhi) {
                val k = if (a[i] == b[j]) prev[j - blo] + 1 else 0
                cur[j - blo + 1] = k
                if (k > bestK) {
                    bestI = i - k + 1
                    bestJ = j - k + 1
                    bestK = k
                }
            }
            val t = prev
            prev = cur
            cur = t
            cur.fill(0)
        }
        if (bestK == 0) return 0
        return bestK + matching(a, alo, bestI, b, blo, bestJ) + matching(a, bestI + bestK, ahi, b, bestJ + bestK, bhi)
    }
}
