package org.seion.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A file cut short (a copy that stopped, a damaged card) whose header still claims its full length: the player kept
 * the item running on its own clock, in silence, until the header's duration had passed (for
 * example, a FLAC with 7.6 s of audio and a 40 s header advanced after 55.7 s). At the end of the data the
 * extractors Seion plays through report the real duration, so the item ends where its audio does.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EarlyEndTest {
    /**
     * An MPEG-1 Layer III stream (128 kb/s, 44.1 kHz, stereo; 417-byte frames of 1152 samples) whose LAME "Info" frame
     * claims [claimedFrames] frames, as encoders write it, followed by [actualFrames] frames (silent payloads: the
     * extractor only parses headers).
     */
    private fun mp3(claimedFrames: Int, actualFrames: Int): ByteArray {
        val frameSize = 417
        val header = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64)
        val out = ByteBuffer.allocate(frameSize * (actualFrames + 1)).order(ByteOrder.BIG_ENDIAN)
        val info = ByteArray(frameSize)
        System.arraycopy(header, 0, info, 0, 4)
        val tag = ByteBuffer.wrap(info, 36, frameSize - 36).order(ByteOrder.BIG_ENDIAN)
        tag.put("Info".toByteArray()).putInt(0x07).putInt(claimedFrames).putInt(frameSize * (claimedFrames + 1))
        for (i in 0 until 100) tag.put((i * 256 / 100).toByte()) // table of contents: linear
        out.put(info)
        repeat(actualFrames) {
            out.put(header)
            out.put(ByteArray(frameSize - 4))
        }
        return out.array()
    }

    private val frameUs = 1152 * 1_000_000L / 44_100

    private class Recorder : ExtractorOutput {
        val seekMaps = ArrayList<SeekMap>()
        var lastSampleUs = C.TIME_UNSET

        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
            override fun format(format: Format) = Unit

            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val skip = ByteArray(length)
                return input.read(skip, 0, length)
            }

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)

            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                lastSampleUs = timeUs
            }
        }

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) {
            seekMaps += seekMap
        }
    }

    private fun reader(bytes: ByteArray, from: Long) = object : DataReader {
        var pos = from.toInt()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= bytes.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, bytes.size - pos)
            System.arraycopy(bytes, pos, buffer, offset, n)
            pos += n
            return n
        }
    }

    /** Reads [bytes] to the end through the factory the player uses, as ProgressiveMediaPeriod does. */
    private fun extract(bytes: ByteArray): Recorder {
        val out = Recorder()
        val extractor: Extractor =
            seionExtractors { true }.createExtractors().first { e ->
                val ok = runCatching { e.sniff(DefaultExtractorInput(reader(bytes, 0), 0, bytes.size.toLong())) }.getOrDefault(false)
                ok
            }
        extractor.init(out)
        val holder = PositionHolder()
        var input = DefaultExtractorInput(reader(bytes, 0), 0, bytes.size.toLong())
        while (true) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_END_OF_INPUT -> break
                Extractor.RESULT_SEEK -> input = DefaultExtractorInput(reader(bytes, holder.position), holder.position, bytes.size.toLong())
            }
        }
        extractor.release()
        return out
    }

    @Test fun `a file cut short reports its real duration at the end of its data`() {
        val out = extract(mp3(claimedFrames = 1531, actualFrames = 306)) // 40.0 s claimed, 8.0 s there
        val claimed = out.seekMaps.first().durationUs
        assertTrue("the header's duration first, was ${claimed / 1000} ms", claimed in 39_900_000L..40_100_000L)
        val last = out.seekMaps.last().durationUs
        assertTrue("real duration ≈ 8 s, was ${last / 1000} ms", last in 306 * frameUs - 100_000..306 * frameUs + 100_000)
    }

    @Test fun `the shortfall rule`() {
        assertEquals(8_000_000L, EarlyEnd.correctedDurationUs(40_000_000L, 7_974_000L, 26_000L))
        assertEquals(null, EarlyEnd.correctedDurationUs(40_000_000L, 39_200_000L, 26_000L)) // under 1 s short: rounding
        assertEquals(null, EarlyEnd.correctedDurationUs(C.TIME_UNSET, 7_974_000L, 26_000L)) // no claimed duration
        assertEquals(null, EarlyEnd.correctedDurationUs(40_000_000L, C.TIME_UNSET, 0L)) // no audio sample at all
    }

    @Test fun `a seek near the end reports the same real duration once`() {
        // ExoPlayer seeks before the end; the run that reaches the end of the input decides, and nothing repeats.
        val bytes = mp3(claimedFrames = 1531, actualFrames = 306)
        val out = Recorder()
        val extractor = seionExtractors { true }.createExtractors().first { e ->
            runCatching { e.sniff(DefaultExtractorInput(reader(bytes, 0), 0, bytes.size.toLong())) }.getOrDefault(false)
        }
        extractor.init(out)
        val holder = PositionHolder()
        fun readToEnd(from: Long) {
            var input = DefaultExtractorInput(reader(bytes, from), from, bytes.size.toLong())
            while (true) {
                when (extractor.read(input, holder)) {
                    Extractor.RESULT_END_OF_INPUT -> return
                    Extractor.RESULT_SEEK ->
                        input =
                            DefaultExtractorInput(reader(bytes, holder.position), holder.position, bytes.size.toLong())
                }
            }
        }
        readToEnd(0)
        val first = out.seekMaps.last().durationUs
        val pos = 417L * 200
        extractor.seek(pos, 200 * frameUs)
        readToEnd(pos)
        assertEquals(2, out.seekMaps.size)
        assertEquals(first, out.seekMaps.last().durationUs)
    }

    @Test fun `a complete file keeps its header's duration`() {
        val out = extract(mp3(claimedFrames = 306, actualFrames = 306))
        assertEquals(1, out.seekMaps.size)
        val d = out.seekMaps.single().durationUs
        assertTrue("was ${d / 1000} ms", d in 306 * frameUs - 100_000..306 * frameUs + 100_000)
    }
}
