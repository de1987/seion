package org.seion.ui.settings

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.seion.BuildConfig

/** "Source code" opens the project URL in the browser, and does nothing harmful on a device without one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SourceLinkTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun `opens the project URL with ACTION_VIEW`() {
        assertTrue(openUrl(app, BuildConfig.PROJECT_URL))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(BuildConfig.PROJECT_URL, started.dataString)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        // Not an activity context: the browser needs its own task.
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `no browser installed is a false return, not a crash`() {
        shadowOf(app).checkActivities(true)
        assertFalse(openUrl(app, BuildConfig.PROJECT_URL))
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
