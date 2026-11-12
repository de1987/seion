package org.seion.ui

/** Display formatting (pure, unit-tested). */
object Format {
    /** 03:07, 12:00, 1:02:03 (mm:ss; h:mm:ss once a track reaches an hour). Negative/unknown -> 00:00. */
    fun duration(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** Elapsed label for a position within a track of [totalMs]. */
    fun elapsed(positionMs: Long, totalMs: Long): String = duration(if (totalMs > 0) positionMs.coerceAtMost(totalMs) else positionMs)

    /** Total length for headers: "38 min", or "1 h 12 min". */
    fun totalMinutes(ms: Long): Pair<Long, Long> {
        val minutes = (ms.coerceAtLeast(0) + 30_000) / 60_000
        return minutes / 60 to minutes % 60
    }
}

/** Measures the width in px of [text] at [sizeSp] (the app's TextMeasurer, a fake in tests). */
fun interface TextWidth {
    fun widthPx(text: String, sizeSp: Float): Float
}

/** Label sizing that never breaks inside a word, pure so it is unit-tested. */
object WordSafe {
    const val START_SP = 12.5f
    const val FLOOR_SP = 9f
    const val STEP_SP = 0.5f

    /**
     * The largest size from [START_SP] down in [STEP_SP] steps (floor [FLOOR_SP]) at which the longest word fits on
     * one line of [widthPx] and the greedily wrapped label fits in [maxLines]. Auto-size alone would not help: the
     * mid-word layout *fits* in two lines.
     */
    fun fontSize(label: String, widthPx: Float, maxLines: Int, width: TextWidth): Float {
        val words = label.split(' ').filter { it.isNotEmpty() }
        var sp = START_SP
        while (sp > FLOOR_SP) {
            if (fits(words, sp, widthPx, maxLines, width)) return sp
            sp -= STEP_SP
        }
        return FLOOR_SP
    }

    private fun fits(words: List<String>, sp: Float, widthPx: Float, maxLines: Int, width: TextWidth): Boolean {
        if (words.any { width.widthPx(it, sp) > widthPx }) return false
        var lines = 1
        var line = ""
        for (w in words) {
            val candidate = if (line.isEmpty()) w else "$line $w"
            if (width.widthPx(candidate, sp) <= widthPx) {
                line = candidate
            } else {
                lines++
                line = w
            }
        }
        return lines <= maxLines
    }
}
