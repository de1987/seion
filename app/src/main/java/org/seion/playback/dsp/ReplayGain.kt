package org.seion.playback.dsp

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.seion.playback.dsp.core.FastMath

/** ReplayGain: off by default; Album falls back to the track values, Track to the album values. */
enum class ReplayGainMode { OFF, TRACK, ALBUM }

/**
 * A stream's ReplayGain values in dB and linear sample peak, NaN when the file has none. Read by the sink from the
 * format's metadata at configure (not on the hot path), never from Seion's index: the tags travel with the stream, so
 * a gapless boundary changes them exactly where the next file's audio starts.
 */
data class ReplayGainTags(
    val trackDb: Double = Double.NaN,
    val trackPeak: Double = Double.NaN,
    val albumDb: Double = Double.NaN,
    val albumPeak: Double = Double.NaN
) {
    val isEmpty: Boolean get() = trackDb.isNaN() && albumDb.isNaN()

    companion object {
        val NONE = ReplayGainTags()

        /**
         * The tags Media3 hands the sink: Vorbis comments (FLAC, Ogg Vorbis/Opus), ID3 `TXXX` frames (MP3, also ID3 in
         * other containers) and MP4 `----:com.apple.iTunes:replaygain_*` atoms (Media3's `InternalFrame`). Keys are
         * matched without case. Opus `R128_TRACK_GAIN`/`R128_ALBUM_GAIN` (Q7.8 dB relative to −23 LUFS) count only when
         * no `REPLAYGAIN_*` value exists, +5 dB to ReplayGain's reference. Values that don't parse are ignored; gains are
         * clamped to ±[MAX_TAG_DB].
         */
        @OptIn(UnstableApi::class)
        fun parse(metadata: Metadata?): ReplayGainTags {
            if (metadata == null || metadata.length() == 0) return NONE
            var tg = Double.NaN
            var tp = Double.NaN
            var ag = Double.NaN
            var ap = Double.NaN
            var r128t = Double.NaN
            var r128a = Double.NaN
            for (i in 0 until metadata.length()) {
                val (key, value) =
                    when (val e = metadata.get(i)) {
                        is VorbisComment -> e.key to e.value
                        is TextInformationFrame -> (if (e.id == "TXXX" || e.id == "TXX") e.description else null) to e.value
                        is InternalFrame -> e.description to e.text
                        else -> null to null
                    }
                if (key == null || value == null) continue
                when (key.trim().uppercase(java.util.Locale.ROOT)) {
                    "REPLAYGAIN_TRACK_GAIN" -> tg = parseDb(value)
                    "REPLAYGAIN_TRACK_PEAK" -> tp = parsePeak(value)
                    "REPLAYGAIN_ALBUM_GAIN" -> ag = parseDb(value)
                    "REPLAYGAIN_ALBUM_PEAK" -> ap = parsePeak(value)
                    "R128_TRACK_GAIN" -> r128t = parseQ78(value)
                    "R128_ALBUM_GAIN" -> r128a = parseQ78(value)
                }
            }
            if (tg.isNaN() && ag.isNaN()) {
                tg = r128t
                ag = r128a
            }
            return if (tg.isNaN() && ag.isNaN()) NONE else ReplayGainTags(tg, tp, ag, ap)
        }

        /** "-6.20 dB", "+3.1 dB", "−6,2 dB", "-6.2": dB, or NaN. */
        internal fun parseDb(text: String): Double {
            val s =
                text
                    .trim()
                    .replace('−', '-')
                    .replace(',', '.')
                    .removeSuffix("dB")
                    .removeSuffix("db")
                    .removeSuffix("DB")
                    .trim()
            val v = s.toDoubleOrNull() ?: return Double.NaN
            return if (v.isFinite()) v.coerceIn(-MAX_TAG_DB, MAX_TAG_DB) else Double.NaN
        }

        /** A linear sample peak ("0.988525"; 1.0 = full scale), or NaN. */
        internal fun parsePeak(text: String): Double {
            val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return Double.NaN
            return if (v.isFinite() && v > 0.0) v else Double.NaN
        }

        /** Opus R128 gain: a signed Q7.8 integer in dB relative to −23 LUFS, +5 dB to ReplayGain's −18 LUFS. */
        internal fun parseQ78(text: String): Double {
            val v = text.trim().toIntOrNull() ?: return Double.NaN
            return (v / 256.0 + R128_TO_RG_DB).coerceIn(-MAX_TAG_DB, MAX_TAG_DB)
        }

        /** Tags beyond this are junk (real values stay within about −20…+15 dB). */
        const val MAX_TAG_DB = 30.0
        const val R128_TO_RG_DB = 5.0
    }
}

/** The gain rules (pure): what the engine applies for a stream, and whether the shared limiter must be in. */
object ReplayGain {
    /** The pre-amp's range on the Sound screen (dB, whole steps). */
    const val PREAMP_MIN_DB = -6
    const val PREAMP_MAX_DB = 6

    /** The total gain's clamp (dB): a junk tag never makes a stream louder than +12 dB or quieter than −24 dB. */
    const val MIN_TOTAL_DB = -24.0
    const val MAX_TOTAL_DB = 12.0

    /** The chosen gain in dB with the mode's fallback, or NaN when the stream has neither value. */
    fun selectedDb(mode: ReplayGainMode, trackDb: Double, albumDb: Double): Double = when (mode) {
        ReplayGainMode.OFF -> Double.NaN
        ReplayGainMode.TRACK -> if (!trackDb.isNaN()) trackDb else albumDb
        ReplayGainMode.ALBUM -> if (!albumDb.isNaN()) albumDb else trackDb
    }

    /** The peak that goes with [selectedDb] (NaN when unknown). */
    fun selectedPeak(mode: ReplayGainMode, trackDb: Double, trackPeak: Double, albumDb: Double, albumPeak: Double): Double = when (mode) {
        ReplayGainMode.OFF -> Double.NaN
        ReplayGainMode.TRACK -> if (!trackDb.isNaN()) trackPeak else albumPeak
        ReplayGainMode.ALBUM -> if (!albumDb.isNaN()) albumPeak else trackPeak
    }

    /**
     * The linear gain: exactly 1.0 when off or when the stream has no ReplayGain value (an untagged file plays unchanged,
     * the pre-amp is not applied to it), else 10^((gain + pre-amp)/20), the sum clamped to [MIN_TOTAL_DB]..[MAX_TOTAL_DB].
     * Through the engine's deterministic [FastMath.fromDb20] (within 1e-4 dB, the same on every device, and no
     * allocation: it runs from setParams on the audio thread, where JDK's `StrictMath.pow` allocates).
     */
    fun linear(mode: ReplayGainMode, preampDb: Float, trackDb: Double, albumDb: Double): Double {
        val db = selectedDb(mode, trackDb, albumDb)
        if (db.isNaN()) return 1.0
        val total = (db + preampDb).coerceIn(MIN_TOTAL_DB, MAX_TOTAL_DB)
        return if (total == 0.0) 1.0 else FastMath.fromDb20(total)
    }

    /**
     * Whether the shared limiter must be engaged: only when the gain raises the level and the raised peak could pass the
     * limiter's ceiling (−1 dBTP); an unknown peak counts as full scale. A cut never needs it (the file was already
     * playable at its own level).
     */
    fun needsLimiter(gain: Double, peak: Double): Boolean = gain > 1.0 && (peak.isNaN() || gain * peak > TruePeakLimiter.CEILING)
}
