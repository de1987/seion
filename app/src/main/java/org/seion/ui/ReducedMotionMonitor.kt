package org.seion.ui

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf

/**
 * The system's "Remove animations" (animator duration scale 0) as Compose state, kept current while Seion runs (read once,
 * the spectrum band and the drift would ignore a change until a restart). [read] gives
 * the current value; [register] subscribes to changes and returns the unsubscribe.
 */
class ReducedMotionMonitor(private val read: () -> Boolean, private val register: (onChange: () -> Unit) -> (() -> Unit)) {
    val reduced = mutableStateOf(read())
    private var unregister: (() -> Unit)? = null

    fun start() {
        if (unregister == null) unregister = register(::refresh)
        refresh()
    }

    fun stop() {
        unregister?.invoke()
        unregister = null
    }

    fun refresh() {
        reduced.value = read()
    }

    companion object {
        /** The real monitor: Settings.Global's animator duration scale, observed on the main thread. */
        fun of(context: Context): ReducedMotionMonitor {
            val resolver = context.applicationContext.contentResolver
            val read = {
                Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
                    !ValueAnimator.areAnimatorsEnabled()
            }
            return ReducedMotionMonitor(read) { onChange ->
                val observer =
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) = onChange()
                    }
                resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
                ({ resolver.unregisterContentObserver(observer) })
            }
        }
    }
}
