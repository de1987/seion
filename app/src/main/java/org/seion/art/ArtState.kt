package org.seion.art

import android.graphics.Bitmap

/** Where one track's art is; the only thing the now-playing UI observes. */
sealed interface ArtState {
    /** Local resolution (embedded, folder image) not finished yet. */
    data object Loading : ArtState

    data class Found(val bitmap: Bitmap, val origin: ArtOrigin) : ArtState

    /** No local art; [onlinePending] while an online lookup is queued or running. */
    data class Missing(val onlinePending: Boolean) : ArtState
}

/** What the now-playing cover shows next, pure so the "never the previous track's cover" rule is tested. */
sealed interface FrameChoice {
    /** Keep the frame on screen (the previous track's cover during the grace, or this track's while it reloads). */
    data object KeepPrevious : FrameChoice

    /** Show this track's art, or the no-cover tile when [bitmap] is null. */
    data class Show(val bitmap: Bitmap?) : FrameChoice
}

object CoverFramePolicy {
    /** The previous cover may stay on a new track at most this long, then the no-cover tile shows. */
    const val GRACE_MS = 150L

    /**
     * [prevMediaId] is the track of the frame on screen, [mediaId] the current track, [elapsedMs] the time since it
     * became current.
     */
    fun next(prevMediaId: String?, mediaId: String?, state: ArtState, elapsedMs: Long): FrameChoice = when (state) {
        is ArtState.Found -> FrameChoice.Show(state.bitmap)
        is ArtState.Missing -> FrameChoice.Show(null)
        ArtState.Loading ->
            if (prevMediaId == mediaId || elapsedMs < GRACE_MS) FrameChoice.KeepPrevious else FrameChoice.Show(null)
    }
}
