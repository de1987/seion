package org.seion.playback.dsp

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ReplayGain tags and rules: what is read from the stream's metadata and what gain it gives. */
class ReplayGainTest {
    @Test fun `vorbis comments, ID3 TXXX and MP4 freeform atoms are read without case`() {
        val flac =
            ReplayGainTags.parse(
                Metadata(
                    VorbisComment("REPLAYGAIN_TRACK_GAIN", "-6.20 dB"),
                    VorbisComment("replaygain_track_peak", "0.988525"),
                    VorbisComment("REPLAYGAIN_ALBUM_GAIN", "-7.5 dB"),
                    VorbisComment("REPLAYGAIN_ALBUM_PEAK", "1.000000"),
                    VorbisComment("TITLE", "x")
                )
            )
        assertEquals(ReplayGainTags(-6.2, 0.988525, -7.5, 1.0), flac)
        val mp3 =
            ReplayGainTags.parse(
                Metadata(
                    TextInformationFrame("TXXX", "REPLAYGAIN_TRACK_GAIN", listOf("+3.10 dB")),
                    TextInformationFrame("TXXX", "REPLAYGAIN_TRACK_PEAK", listOf("0.5")),
                    TextInformationFrame("TIT2", null, listOf("REPLAYGAIN_ALBUM_GAIN"))
                )
            )
        assertEquals(3.1, mp3.trackDb, 1e-12)
        assertEquals(0.5, mp3.trackPeak, 1e-12)
        assertTrue(mp3.albumDb.isNaN())
        val m4a = ReplayGainTags.parse(Metadata(InternalFrame("com.apple.iTunes", "replaygain_album_gain", "-4,25 dB")))
        assertEquals(-4.25, m4a.albumDb, 1e-12)
        assertTrue(m4a.trackDb.isNaN())
    }

    @Test fun `untagged, junk and missing metadata read as none`() {
        assertSame(ReplayGainTags.NONE, ReplayGainTags.parse(null))
        assertSame(ReplayGainTags.NONE, ReplayGainTags.parse(Metadata(VorbisComment("ARTIST", "a"))))
        assertSame(ReplayGainTags.NONE, ReplayGainTags.parse(Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "loud"))))
        // Only a peak, no gain: nothing to apply.
        assertSame(ReplayGainTags.NONE, ReplayGainTags.parse(Metadata(VorbisComment("REPLAYGAIN_TRACK_PEAK", "0.9"))))
        assertEquals(ReplayGainTags.MAX_TAG_DB, ReplayGainTags.parseDb("+99 dB"), 0.0)
        assertEquals(-6.2, ReplayGainTags.parseDb("−6.2 dB"), 1e-12)
        assertTrue(ReplayGainTags.parsePeak("0").isNaN())
        assertTrue(ReplayGainTags.parsePeak("-1").isNaN())
    }

    @Test fun `opus R128 gains count only without ReplayGain values, plus 5 dB`() {
        val opus = ReplayGainTags.parse(Metadata(VorbisComment("R128_TRACK_GAIN", "-1792"), VorbisComment("R128_ALBUM_GAIN", "256")))
        assertEquals(-7.0 + 5.0, opus.trackDb, 1e-12)
        assertEquals(1.0 + 5.0, opus.albumDb, 1e-12)
        val both = ReplayGainTags.parse(
            Metadata(VorbisComment("R128_TRACK_GAIN", "-1792"), VorbisComment("REPLAYGAIN_TRACK_GAIN", "-3 dB"))
        )
        assertEquals(-3.0, both.trackDb, 1e-12)
    }

    @Test fun `off and untagged give exactly 1, and the pre-amp is not applied to untagged files`() {
        assertEquals(1.0, ReplayGain.linear(ReplayGainMode.OFF, 6f, -6.0, -6.0), 0.0)
        assertEquals(1.0, ReplayGain.linear(ReplayGainMode.TRACK, 6f, Double.NaN, Double.NaN), 0.0)
        assertEquals(1.0, ReplayGain.linear(ReplayGainMode.ALBUM, -3f, Double.NaN, Double.NaN), 0.0)
        // A tag that cancels the pre-amp is exactly unity too.
        assertEquals(1.0, ReplayGain.linear(ReplayGainMode.TRACK, 2f, -2.0, Double.NaN), 0.0)
    }

    @Test fun `modes fall back to the other value, and the pre-amp adds`() {
        assertEquals(-6.0, ReplayGain.selectedDb(ReplayGainMode.TRACK, -6.0, -8.0), 0.0)
        assertEquals(-8.0, ReplayGain.selectedDb(ReplayGainMode.ALBUM, -6.0, -8.0), 0.0)
        assertEquals(-6.0, ReplayGain.selectedDb(ReplayGainMode.ALBUM, -6.0, Double.NaN), 0.0)
        assertEquals(-8.0, ReplayGain.selectedDb(ReplayGainMode.TRACK, Double.NaN, -8.0), 0.0)
        assertEquals(0.5, ReplayGain.selectedPeak(ReplayGainMode.ALBUM, -6.0, 0.5, Double.NaN, 0.25), 0.0)
        assertEquals(Math.pow(10.0, -4.0 / 20), ReplayGain.linear(ReplayGainMode.TRACK, 2f, -6.0, -8.0), 2e-5)
        // The total is clamped.
        assertEquals(Math.pow(10.0, 12.0 / 20), ReplayGain.linear(ReplayGainMode.TRACK, 6f, 25.0, Double.NaN), 1e-4)
        assertEquals(Math.pow(10.0, -24.0 / 20), ReplayGain.linear(ReplayGainMode.TRACK, -6f, -25.0, Double.NaN), 1e-6)
    }

    @Test fun `the limiter is needed only when a rise could pass the ceiling`() {
        assertFalse(ReplayGain.needsLimiter(0.5, 1.0)) // a cut never
        assertFalse(ReplayGain.needsLimiter(1.0, Double.NaN))
        assertTrue(ReplayGain.needsLimiter(1.5, Double.NaN)) // unknown peak = full scale
        assertFalse(ReplayGain.needsLimiter(1.5, 0.5)) // 0.75 < 0.891
        assertTrue(ReplayGain.needsLimiter(1.5, 0.7)) // 1.05
    }
}
