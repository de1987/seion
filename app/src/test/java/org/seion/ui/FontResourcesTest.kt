package org.seion.ui

import android.app.Application
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.seion.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FontResourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `the three bundled weights load`() {
        listOf(R.font.roboto_light, R.font.roboto_regular, R.font.roboto_medium).forEach {
            assertNotNull(ResourcesCompat.getFont(context, it))
        }
    }

    @Test fun `the font licence ships with the app`() {
        val text = context.resources.openRawResource(R.raw.ofl_roboto).bufferedReader().use { it.readText() }
        assertTrue(text.contains("SIL OPEN FONT LICENSE Version 1.1"))
    }
}
