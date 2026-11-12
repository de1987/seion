package org.seion.playback.dsp

import android.media.AudioDeviceInfo

/**
 * Route → CrystalX+ voicing, pure for the tests. On API 33+ the platform names the active media
 * route; on API 31–32 `getDevices(GET_DEVICES_OUTPUTS)` lists every connected output, so the active one is picked
 * by priority: USB headset/device > wired > BLE headset / A2DP / hearing aid > HDMI / line / dock > BLE speaker >
 * built-in speaker (roughly the platform's own media policy). An A2DP speaker gets HEADPHONES (honest limit: telling
 * it apart needs BLUETOOTH_CONNECT).
 */
object RouteVoicing {
    private val LINE_TYPES =
        intArrayOf(
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_DOCK_ANALOG
        )

    fun voicingFor(type: Int): Voicing = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLE_SPEAKER -> Voicing.SPEAKER
        in LINE_TYPES -> Voicing.LINE
        else -> Voicing.HEADPHONES
    }

    /** Lower is preferred; −1 for types that are not media outputs. */
    fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 0
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 1
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID -> 2
        in LINE_TYPES -> 3
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> 4
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 5
        else -> -1
    }

    /** The voicing for a list of connected output types (API 31–32), or for the platform's active route (API 33+). */
    fun pick(types: IntArray): Voicing {
        var best = -1
        var bestPriority = Int.MAX_VALUE
        for (t in types) {
            val p = priority(t)
            if (p >= 0 && p < bestPriority) {
                bestPriority = p
                best = t
            }
        }
        return if (best < 0) Voicing.SPEAKER else voicingFor(best)
    }
}
