package org.seion.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingSeekMap
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput

/**
 * A file cut short (a copy that stopped, a damaged card) whose header still claims its full length. Media3 takes the
 * item's duration from the header (FLAC STREAMINFO, an MP3 Info/Xing frame count, the MP4 `moov`), and once the audio
 * ran out the player kept the item going on its own clock, in silence, until that duration had passed (a FLAC with
 * 7.6 s of audio and a 40 s header advanced only after 55.7 s).
 *
 * Every extractor is wrapped: when its data ends more than [MIN_SHORTFALL_US] before the duration its seek map claims,
 * it reports the real duration (the end of the last audio sample) with a second seek map. ProgressiveMediaPeriod hands
 * a seek map that arrives after preparation to the source as new source info, so the timeline's item shrinks to its
 * audio and the next track starts where the audio ends (gapless, as for any item). A local file is read to its end
 * within moments of the start, long before playback gets there. Complete files never get a second seek map.
 */
object EarlyEnd {
    /** Shorter gaps are rounding in a header (a VBR estimate, a last partial frame): left alone. */
    const val MIN_SHORTFALL_US = 1_000_000L

    /** The real duration when the data ended well before [claimedUs], else null (pure). */
    fun correctedDurationUs(claimedUs: Long, lastSampleUs: Long, lastSampleDurationUs: Long): Long? {
        if (claimedUs == C.TIME_UNSET || lastSampleUs == C.TIME_UNSET || lastSampleUs < 0) return null
        val end = lastSampleUs + lastSampleDurationUs.coerceAtLeast(0)
        return end.takeIf { claimedUs - it > MIN_SHORTFALL_US }
    }
}

/** [inner]'s extractors, each wrapped in an [EarlyEndExtractor]. */
@UnstableApi
internal class EarlyEndExtractors(private val inner: ExtractorsFactory) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = inner.createExtractors().wrapped()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        inner.createExtractors(uri, responseHeaders).wrapped()

    private fun Array<Extractor>.wrapped(): Array<Extractor> = Array(size) { EarlyEndExtractor(this[it]) }
}

/** Forwards everything to [inner]; at the end of the input, reports a real duration shorter than the header's. */
@UnstableApi
internal class EarlyEndExtractor(private val inner: Extractor) : Extractor {
    private var output: EarlyEndOutput? = null

    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = inner.sniffFailureDetails

    override fun init(output: ExtractorOutput) {
        val wrapped = EarlyEndOutput(output)
        this.output = wrapped
        inner.init(wrapped)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val result = inner.read(input, seekPosition)
        if (result == Extractor.RESULT_END_OF_INPUT) output?.endOfInput()
        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        output?.seeked()
        inner.seek(position, timeUs)
    }

    override fun release() = inner.release()

    /** The real extractor ([androidx.media3.exoplayer.source.BundledExtractorsAdapter] looks for Mp3Extractor). */
    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
}

@UnstableApi
private class EarlyEndOutput(private val out: ExtractorOutput) : ExtractorOutput {
    private var claimed: SeekMap? = null
    private var reportedUs = C.TIME_UNSET
    private val audio = ArrayList<LastSample>()

    override fun track(id: Int, type: Int): TrackOutput {
        val track = out.track(id, type)
        return if (type == C.TRACK_TYPE_AUDIO) LastSample(track).also { audio += it } else track
    }

    override fun endTracks() = out.endTracks()

    override fun seekMap(seekMap: SeekMap) {
        claimed = seekMap
        reportedUs = C.TIME_UNSET
        out.seekMap(seekMap)
    }

    /** A seek starts a new run of samples; the run that reaches the end of the input tells where the audio ends. */
    fun seeked() = audio.forEach { it.reset() }

    fun endOfInput() {
        // Once per header: after a seek the timestamps come from the header's own (too long) time map, so the first
        // read to the end, from the start, is the exact one.
        if (reportedUs != C.TIME_UNSET) return
        val map = claimed ?: return
        val last = audio.maxByOrNull { it.lastUs } ?: return
        val realUs = EarlyEnd.correctedDurationUs(map.durationUs, last.lastUs, last.lastDurationUs) ?: return
        reportedUs = realUs
        Log.i(TAG, "file ends early: audio until ${realUs / 1000} ms, header says ${map.durationUs / 1000} ms")
        out.seekMap(ShortSeekMap(map, realUs))
    }

    private companion object {
        const val TAG = "SeionPlayback"
    }
}

/** [inner] with the real duration; exact (not estimated), so the source accepts it after a non-estimated one. */
@UnstableApi
private class ShortSeekMap(inner: SeekMap, private val realUs: Long) : ForwardingSeekMap(inner) {
    override fun getDurationUs(): Long = realUs

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints = super.getSeekPoints(minOf(timeUs, realUs))

    override fun isEstimated(): Boolean = false
}

/** Forwards a track's samples and remembers the time of the last two (their distance estimates the last one's length). */
@UnstableApi
private class LastSample(private val out: TrackOutput) : TrackOutput {
    var lastUs = C.TIME_UNSET
        private set
    private var previousUs = C.TIME_UNSET

    val lastDurationUs: Long get() = if (lastUs == C.TIME_UNSET || previousUs == C.TIME_UNSET) 0 else lastUs - previousUs

    fun reset() {
        lastUs = C.TIME_UNSET
        previousUs = C.TIME_UNSET
    }

    override fun format(format: Format) = out.format(format)

    override fun durationUs(durationUs: Long) = out.durationUs(durationUs)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
        out.sampleData(input, length, allowEndOfInput, sampleDataPart)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = out.sampleData(data, length, sampleDataPart)

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        previousUs = lastUs
        lastUs = timeUs
        out.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
}
