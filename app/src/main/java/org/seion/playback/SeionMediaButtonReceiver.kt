package org.seion.playback

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import java.io.File
import org.seion.SeionApp

/**
 * Headset/Bluetooth Play after Seion was closed (its session released). On Android 12+ the system sends such a key to
 * the receiver of the app whose session was active last, so this only fires when Seion was the last thing playing.
 * Media3's receiver starts [PlaybackService] in the foreground for Play/Play-Pause/headset-hook only; the service then
 * runs `onPlaybackResumption` (the saved queue, re-mapped by [QueueResume]). The gate keeps it from starting a
 * foreground service that could never play.
 */
@OptIn(UnstableApi::class)
class SeionMediaButtonReceiver : MediaButtonReceiver() {
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
        val store = (context.applicationContext as? SeionApp)?.container?.queueStore ?: return false
        val start = QueueResume.mayStart(Environment::isExternalStorageManager, store::load) { File(it).isFile }
        Log.i("SeionPlayback", "media button with no session: ${if (start) "resuming the saved queue" else "nothing to resume"}")
        return start
    }
}
