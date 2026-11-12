package org.seion.playback.dsp

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.seion.playback.viz.SpectrumTap

/**
 * The DSP host: a [ForwardingAudioSink] around DefaultAudioSink (float output ON, no app processors).
 * Every supported PCM stream (1–2 channels, ≤ 192 kHz, end padding ≤ the trimmer ring, no offload) is converted to
 * float, trimmed here ([Trimmer]), run through [DspEngine] sample-synchronously (each [handleBuffer] emits as many
 * frames as it consumes, sliced to [SLICE_FRAMES], with back-pressure through one preallocated output buffer) and
 * handed to the inner sink as float with encoder delay = padding = 0. Anything else is delegated unchanged.
 *
 * Timestamps: each output slice is stamped `streamStartPts + emittedFrames / fs`, the post-trim timeline the inner
 * sink expects (its 200 ms discontinuity check sees no offset). `configure(Format, …)` is final in
 * ForwardingAudioSink, so the config overload is the one overridden. Every method runs on the playback thread.
 */
@OptIn(UnstableApi::class)
class SeionAudioSink(
    private val inner: AudioSink,
    private val stats: DspStats = DspStats({ 0L }),
    private val status: DspStatus = DspStatus(),
    private val control: DspControl = DspControl(),
    private val engine: DspEngine = DspEngine(stats, cx = CrystalX(), rp = RenovaPure(), limiter = TruePeakLimiter()),
    /** The spectrum band's tap: fed what is heard, only while it is enabled. */
    private val spectrum: SpectrumTap? = null
) : ForwardingAudioSink(inner) {
    private enum class Mode { IDLE, PROCESS, DELEGATE }

    private var mode = Mode.IDLE
    private val trimmer = Trimmer(DspEngine.MAX_CHANNELS)

    private val inFloats = FloatArray(SLICE_FRAMES * DspEngine.MAX_CHANNELS)
    private val work = FloatArray(SLICE_FRAMES * DspEngine.MAX_CHANNELS)
    private val out: ByteBuffer = ByteBuffer.allocateDirect(SLICE_FRAMES * DspEngine.MAX_CHANNELS * 4).order(ByteOrder.LITTLE_ENDIAN)
    private val outFloats = out.asFloatBuffer()

    private var encoding = C.ENCODING_INVALID
    private var inFrameBytes = 0
    private var channels = 0
    private var sampleRate = 0
    private var offloadMode = AudioSink.OFFLOAD_MODE_DISABLED

    /** The trimmed-timeline time of the first frame of the current stream (set by its first buffer). */
    private var streamStartPts = 0L
    private var streamStartPending = true
    private var emittedFrames = 0L
    private var outPts = 0L

    /** The codec class of the next configure, set by [SeionMediaCodecAudioRenderer] right before it. */
    private var nextCodecHint: CodecClass? = null

    fun setNextCodecHint(codec: CodecClass) {
        nextCodecHint = codec
    }

    /** Consumes the hint: no hint means the configure came from the FFmpeg (ALAC) renderer: LOSSLESS. */
    private fun takeCodecHint(): CodecClass = (nextCodecHint ?: CodecClass.LOSSLESS).also { nextCodecHint = null }

    /** The media id of the item this configure belongs to (best effort; null when unknown). */
    private fun mediaKey(cfg: AudioSink.AudioSinkConfig): String? = runCatching {
        val id = cfg.mediaPeriodId ?: return null
        val timeline = cfg.timeline
        if (timeline.isEmpty) return null
        val period = timeline.getPeriodByUid(id.periodUid, androidx.media3.common.Timeline.Period())
        timeline.getWindow(period.windowIndex, androidx.media3.common.Timeline.Window()).mediaItem.mediaId
    }.getOrNull()

    private var pendingCodec = CodecClass.LOSSLESS
    private var pendingKey: String? = null

    /** A configure whose output differs (rate, channels, or a switch to delegation): applied after the old tail. */
    private var pendingConfig: AudioSink.AudioSinkConfig? = null
    private var tailRemaining = -1
    private var eosTailStarted = false
    private var tailDone = false

    override fun getFormatSupport(format: Format): Int =
        if (processable(format, checkPadding = false)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else super.getFormatSupport(format)

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun setOffloadMode(offloadMode: Int) {
        this.offloadMode = offloadMode
        super.setOffloadMode(offloadMode)
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        pendingCodec = takeCodecHint()
        pendingKey = mediaKey(audioSinkConfig)
        val format = audioSinkConfig.format
        val processable = processable(format, checkPadding = true)
        if (mode == Mode.PROCESS && engine.hasAudio && pendingConfig == null) {
            if (processable && format.sampleRate == sampleRate && format.channelCount == channels && sameMapping(audioSinkConfig)) {
                configureGapless(audioSinkConfig)
            } else {
                // The old stream's tail (latency frames) goes out under the old config first.
                pendingConfig = audioSinkConfig
                tailRemaining = -1
            }
            return
        }
        if (pendingConfig != null) {
            pendingConfig = audioSinkConfig
            return
        }
        apply(audioSinkConfig)
    }

    private fun processable(format: Format, checkPadding: Boolean): Boolean = MimeTypes.AUDIO_RAW == format.sampleMimeType &&
        PcmToFloat.isSupported(format.pcmEncoding) &&
        format.channelCount in 1..DspEngine.MAX_CHANNELS &&
        (format.sampleRate == Format.NO_VALUE || format.sampleRate in 1..DspEngine.MAX_RATE) &&
        (!checkPadding || format.encoderPadding <= trimmer.capacityFrames) &&
        offloadMode == AudioSink.OFFLOAD_MODE_DISABLED

    private var lastMapping: Any? = null

    private fun sameMapping(cfg: AudioSink.AudioSinkConfig) = cfg.outputChannelMapping == lastMapping

    /** Same rate and channels: the inner sink keeps its AudioTrack, the engine keeps every state (no click, no gap). */
    private fun configureGapless(cfg: AudioSink.AudioSinkConfig) {
        Log.d(
            TAG,
            "stream (gapless): encoding ${cfg.format.pcmEncoding}, delay ${cfg.format.encoderDelay}, padding ${cfg.format.encoderPadding}"
        )
        super.configure(floatConfig(cfg))
        val f = cfg.format
        trimmer.configure(f.encoderDelay.coerceAtLeast(0), f.encoderPadding.coerceAtLeast(0), channels)
        encoding = f.pcmEncoding
        inFrameBytes = PcmToFloat.bytesPerSample(encoding) * channels
        streamStartPending = true
        eosTailStarted = false
        tailDone = false
        engine.newStream(pendingCodec, pendingKey)
        setStreamGains(f)
        // Debug builds: keep the output around this gapless boundary in the tap (for checking gapless joins).
        if (status.tapEnabled) status.armTapFreeze(DspStatus.TAP_FRAMES / 2 + engine.latency)
    }

    private fun apply(cfg: AudioSink.AudioSinkConfig) {
        val f = cfg.format
        pendingConfig = null
        tailRemaining = -1
        eosTailStarted = false
        tailDone = false
        out.position(0).limit(0)
        if (!processable(f, checkPadding = true)) {
            enterDelegate(cfg)
            return
        }
        try {
            super.configure(floatConfig(cfg))
        } catch (e: AudioSink.ConfigurationException) {
            Log.w(TAG, "float output refused, delegating this stream: ${e.message}")
            enterDelegate(cfg)
            return
        }
        mode = Mode.PROCESS
        encoding = f.pcmEncoding
        channels = f.channelCount
        sampleRate = f.sampleRate
        inFrameBytes = PcmToFloat.bytesPerSample(encoding) * channels
        lastMapping = cfg.outputChannelMapping
        engine.configure(sampleRate, channels)
        debugTp.configure(sampleRate)
        engine.newStream(pendingCodec, pendingKey)
        setStreamGains(f)
        trimmer.configure(f.encoderDelay.coerceAtLeast(0), f.encoderPadding.coerceAtLeast(0), channels)
        streamStartPending = true
        emittedFrames = 0
        status.sampleRate = sampleRate
        spectrum?.sampleRate = sampleRate
        status.channels = channels
        status.latencyFrames = engine.latency
        status.processing = true
        Log.d(
            TAG,
            "stream: encoding ${f.pcmEncoding}, $sampleRate Hz, $channels ch, delay ${f.encoderDelay}, padding ${f.encoderPadding} -> engine (L ${engine.latency})"
        )
    }

    /**
     * The stream's ReplayGain tags from the format's metadata, which Media3 carries from the extractor to the sink
     * for every new stream. Parsed here at configure (strings allocate; never on the hot path).
     */
    private fun setStreamGains(f: Format) {
        val tags = ReplayGainTags.parse(f.metadata)
        engine.setStreamGains(tags)
        status.replayGainTarget = engine.replayGainTarget
        status.replayGainLimiter = engine.replayGainLimiter
        if (!tags.isEmpty) {
            Log.d(TAG, "replaygain tags: track ${tags.trackDb} dB peak ${tags.trackPeak}, album ${tags.albumDb} dB peak ${tags.albumPeak}")
        }
    }

    private fun enterDelegate(cfg: AudioSink.AudioSinkConfig) {
        Log.d(
            TAG,
            "stream: ${cfg.format.sampleMimeType} encoding ${cfg.format.pcmEncoding}, ${cfg.format.sampleRate} Hz, ${cfg.format.channelCount} ch -> delegated"
        )
        if (mode != Mode.DELEGATE) stats.onDelegatedStream()
        mode = Mode.DELEGATE
        status.processing = false
        status.latencyFrames = 0
        super.configure(cfg)
    }

    /** The inner sink's config: float, same rate/channels/mapping, no delay or padding (the wrapper trims). */
    @Suppress("DEPRECATION") // preferredBufferSizeOverride is still honoured by DefaultAudioSink 1.11.1.
    private fun floatConfig(cfg: AudioSink.AudioSinkConfig): AudioSink.AudioSinkConfig {
        val f = cfg.format
        val floatFormat =
            f
                .buildUpon()
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .setEncoderDelay(0)
                .setEncoderPadding(0)
                .build()
        val inBytes = PcmToFloat.bytesPerSample(f.pcmEncoding)
        val buffer = if (cfg.preferredBufferSizeOverride > 0 && inBytes > 0) cfg.preferredBufferSizeOverride / inBytes * 4 else 0
        return AudioSink.AudioSinkConfig
            .Builder(floatFormat)
            .setPreferredBufferSizeOverride(buffer)
            .setOutputChannelMapping(cfg.outputChannelMapping)
            .setTimeline(cfg.timeline)
            .setMediaPeriodId(cfg.mediaPeriodId)
            .build()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (pendingConfig != null && !finishReconfig()) return false
        if (mode != Mode.PROCESS) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (out.hasRemaining() && !super.handleBuffer(out, outPts, 1)) return false
        if (!buffer.hasRemaining()) return true
        if (streamStartPending) {
            streamStartPending = false
            streamStartPts = presentationTimeUs
            emittedFrames = 0
        }
        tailDone = false
        eosTailStarted = false
        engine.setParams(control.params) // one volatile read per handleBuffer
        while (buffer.hasRemaining()) {
            val frames = minOf(buffer.remaining() / inFrameBytes, SLICE_FRAMES)
            if (frames == 0) {
                buffer.position(buffer.limit()) // a partial frame cannot occur with PCM; never spin on it
                break
            }
            stats.beginCall()
            PcmToFloat.convert(buffer, frames * channels, encoding, inFloats, 0)
            val kept = trimmer.process(inFloats, frames, work)
            if (kept > 0) engine.process(work, kept)
            stats.endCall(frames, sampleRate)
            status.renovaPureAmount = engine.pureAmount
            status.renovaPureCutoff = engine.renovaPureCutoff
            status.codec = engine.renovaPureCodec
            status.replayGainTarget = engine.replayGainTarget
            status.replayGainLimiter = engine.replayGainLimiter
            if (kept > 0) {
                emit(kept)
                if (!super.handleBuffer(out, outPts, 1)) return false
            }
        }
        return true
    }

    /** Writes [frames] frames of [work] into [out] and stamps them on the post-trim timeline. */
    private fun emit(frames: Int) {
        out.clear()
        outFloats.clear()
        outFloats.put(work, 0, frames * channels)
        out.limit(frames * channels * 4)
        if (status.tapEnabled) {
            status.tap(work, frames, channels)
            debugMeter(frames)
        }
        outPts = streamStartPts + emittedFrames * C.MICROS_PER_SECOND / sampleRate
        spectrum?.write(work, frames, channels, outPts)
        emittedFrames += frames
    }

    // Debug builds: the output's true peak from a 4× interpolator, max over 30 s windows, and a
    // periodic log line under SeionDsp.
    private val debugTp = org.seion.playback.dsp.core.TpUpsampler(DspEngine.MAX_CHANNELS)
    private var debugPeak = 0.0
    private var debugFrames = 0L

    private fun debugMeter(frames: Int) {
        engine.selfCheck = true
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val v = debugTp.push(work[f * channels + c].toDouble(), c)
                if (v > debugPeak) debugPeak = v
            }
        }
        debugFrames += frames
        status.idleSelfCheckOk = !engine.selfCheckFailed
        if (debugFrames >= 30L * sampleRate) {
            status.maxTruePeakDb = 20 * kotlin.math.log10(debugPeak.coerceAtLeast(1e-10))
            Log.d(TAG, DspReport.lines(stats, status).joinToString(" | "))
            debugPeak = 0.0
            debugFrames = 0
        }
    }

    /**
     * Drains the engine tail (latency frames of zeros in, the last real frames out) under the current config,
     * re-presented until accepted, then applies [pendingConfig]. Runs from handleBuffer and playToEndOfStream.
     */
    private fun finishReconfig(): Boolean {
        if (!drainTail()) return false
        val cfg = pendingConfig ?: return true
        pendingConfig = null
        apply(cfg)
        return true
    }

    /** Pushes the engine tail; true when it is fully delivered. */
    private fun drainTail(): Boolean {
        if (mode != Mode.PROCESS) {
            tailRemaining = 0
            return true
        }
        if (tailRemaining < 0) {
            trimmer.discardWithheld()
            tailRemaining = if (engine.hasAudio) engine.latency else 0
        }
        while (true) {
            if (out.hasRemaining()) {
                if (!super.handleBuffer(out, outPts, 1)) return false
                continue
            }
            if (tailRemaining <= 0) return true
            val n = minOf(tailRemaining, SLICE_FRAMES)
            java.util.Arrays.fill(work, 0, n * channels, 0f)
            stats.beginCall()
            engine.process(work, n)
            stats.endCall(0, sampleRate)
            emit(n)
            tailRemaining -= n
        }
    }

    /** The audible position also feeds the spectrum tap (one volatile write while it is enabled). */
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val p = super.getCurrentPositionUs(sourceEnded)
        val s = spectrum
        if (s != null && s.enabled && p != AudioSink.CURRENT_POSITION_NOT_SET) s.audible(p, System.nanoTime())
        return p
    }

    override fun playToEndOfStream() {
        if (pendingConfig != null && !finishReconfig()) return
        if (mode == Mode.PROCESS && !tailDone) {
            if (!eosTailStarted) {
                eosTailStarted = true
                // The withheld end padding is discarded at a true end of stream (Media3 emits it; it is not music).
                trimmer.discardWithheld()
                tailRemaining = -1
            }
            if (!drainTail()) return
            tailDone = true
            engine.flush() // the tail is out: nothing left to drain at a later reconfigure
        }
        super.playToEndOfStream()
    }

    override fun isEnded(): Boolean = if (mode == Mode.PROCESS) tailDone && super.isEnded() else super.isEnded()

    override fun hasPendingData(): Boolean =
        if (mode == Mode.PROCESS) out.hasRemaining() || (eosTailStarted && !tailDone) || super.hasPendingData() else super.hasPendingData()

    override fun flush() {
        out.position(0).limit(0)
        val cfg = pendingConfig
        if (cfg != null) {
            // After a flush there is no tail to deliver: apply the pending configuration now.
            engine.flush()
            apply(cfg)
        }
        engine.flush()
        trimmer.flush()
        streamStartPending = true
        emittedFrames = 0
        tailRemaining = -1
        eosTailStarted = false
        tailDone = false
        super.flush()
    }

    override fun reset() {
        out.position(0).limit(0)
        pendingConfig = null
        engine.reset()
        trimmer.reset()
        mode = Mode.IDLE
        status.processing = false
        streamStartPending = true
        emittedFrames = 0
        tailRemaining = -1
        eosTailStarted = false
        tailDone = false
        super.reset()
    }

    /** Test view: frames the trimmer dropped from the start since the last flush. */
    internal val trimmedFrames: Long get() = trimmer.trimmedFrames

    internal val latencyFrames: Int get() = engine.latency

    companion object {
        private const val TAG = "SeionDsp"

        /** Each handleBuffer slice is at most 2048 frames. */
        const val SLICE_FRAMES = 2048
    }
}

/**
 * The platform-decoder audio renderer that tells the sink the codec class of each new stream: Media3 calls
 * `onOutputFormatChanged` on the playback thread right before `audioSink.configure`, in data order, for every new
 * input format (also bypassed raw PCM).
 */
@OptIn(UnstableApi::class)
class SeionMediaCodecAudioRenderer(
    context: Context,
    codecAdapterFactory: androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory,
    selector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: android.os.Handler?,
    eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener?,
    private val seionSink: AudioSink
) : androidx.media3.exoplayer.audio.MediaCodecAudioRenderer(
    context,
    codecAdapterFactory,
    selector,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    seionSink
) {
    override fun onOutputFormatChanged(format: Format, mediaFormat: android.media.MediaFormat?) {
        (seionSink as? SeionAudioSink)?.setNextCodecHint(CodecHint.classify(format.sampleMimeType))
        super.onOutputFormatChanged(format, mediaFormat)
    }
}

/** ExoPlayer renderers whose audio sink is [SeionAudioSink] around a DefaultAudioSink with float output on. */
@OptIn(UnstableApi::class)
class SeionRenderersFactory(context: Context, private val hub: DspHub? = null) : DefaultRenderersFactory(context) {
    init {
        // Float output is ON: the wrapper hands the inner sink float. DefaultAudioSink then skips
        // Sonic, so playback speed is not supported (COMMAND_SET_SPEED_AND_PITCH is removed from the session).
        setEnableAudioFloatOutput(true)
        // Extension decoders (the bundled FFmpeg audio decoder) only where no platform decoder handles the format:
        // ALAC on stock Android. Platform decoders keep MP3/AAC/FLAC/Vorbis/Opus/PCM.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    /** Replaces the MediaCodec audio renderer with the hinting subclass; the FFmpeg extension stays as appended. */
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: android.os.Handler,
        eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
        out: ArrayList<androidx.media3.exoplayer.Renderer>
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        val i = out.indexOfFirst { it.javaClass == androidx.media3.exoplayer.audio.MediaCodecAudioRenderer::class.java }
        if (i >= 0) {
            out[i] =
                SeionMediaCodecAudioRenderer(
                    context,
                    codecAdapterFactory,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    audioSink
                )
        }
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val inner =
            DefaultAudioSink
                .Builder(context)
                .setEnableFloatOutput(true)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .build()
        return if (hub != null) {
            SeionAudioSink(inner, hub.stats, hub.status, hub.control, spectrum = hub.spectrum)
        } else {
            SeionAudioSink(inner)
        }
    }
}
