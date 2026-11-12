package org.seion.playback.dsp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Watches the media output route and writes its voicing into [control]: re-evaluated on every device
 * add/remove. API 33+: the platform's active media route; API 31–32: every connected output, by [RouteVoicing]'s
 * priority. Main thread only.
 */
class RouteMonitor(context: Context, private val control: DspControl) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = update()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = update()
        }

    fun start() {
        audio.registerAudioDeviceCallback(callback, handler)
        update()
    }

    fun stop() {
        audio.unregisterAudioDeviceCallback(callback)
    }

    fun update() {
        val types =
            if (Build.VERSION.SDK_INT >= 33) {
                val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
                audio.getAudioDevicesForAttributes(attrs).map { it.type }.toIntArray()
            } else {
                audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toIntArray()
            }
        val voicing = RouteVoicing.pick(types)
        if (control.params.voicing != voicing) Log.i("SeionDsp", "route voicing $voicing (${types.joinToString()})")
        control.update { it.copy(voicing = voicing) }
    }
}
