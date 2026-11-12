package org.seion.playback.dsp

import java.util.concurrent.atomic.AtomicReference

/** The output route's voicing: which CrystalX+ stereo stage and shelf amount apply. */
enum class Voicing { HEADPHONES, SPEAKER, LINE }

/**
 * One immutable snapshot of what the user and the route asked for. [replayGain] and [preampDb]: the ReplayGain
 * stage, OFF by default.
 */
data class DspParams(
    val crystalX: Boolean = false,
    val renovaPure: Boolean = false,
    val voicing: Voicing = Voicing.HEADPHONES,
    val replayGain: ReplayGainMode = ReplayGainMode.OFF,
    val preampDb: Float = 0f
)

/**
 * Control in: written on the main thread by the service, read by the playback thread once per handleBuffer
 * (a volatile read, no allocation, no lock). A change becomes ramp targets in the engine.
 */
class DspControl(initial: DspParams = DspParams()) {
    private val ref = AtomicReference(initial)

    var params: DspParams
        get() = ref.get()
        set(value) = ref.set(value)

    fun update(transform: (DspParams) -> DspParams) {
        while (true) {
            val old = ref.get()
            if (ref.compareAndSet(old, transform(old))) return
        }
    }
}
