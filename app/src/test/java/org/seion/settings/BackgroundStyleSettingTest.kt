package org.seion.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The background style setting, with Onyx after the other styles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackgroundStyleSettingTest {
    @Test fun `every style round-trips through the settings store, Onyx included`() = runBlocking {
        val repo = SettingsRepository(ApplicationProvider.getApplicationContext())
        for (style in listOf(BackgroundStyle.ONYX, BackgroundStyle.CLASSIC, BackgroundStyle.AMBIENT, BackgroundStyle.ONYX)) {
            repo.setBackgroundStyle(style)
            assertEquals(style, repo.current().backgroundStyle)
        }
    }

    @Test fun `Onyx is stored as ONYX, and Seion Infinite stays the default`() {
        assertEquals(
            BackgroundStyle.ONYX,
            SeionSettings.from(mutablePreferencesOf(SeionSettings.Keys.BACKGROUND to "ONYX")).backgroundStyle
        )
        assertEquals(BackgroundStyle.AMBIENT, SeionSettings.from(emptyPreferences()).backgroundStyle)
        assertEquals(BackgroundStyle.AMBIENT, SeionSettings().backgroundStyle)
    }

    @Test fun `Onyx comes after the existing styles`() {
        assertEquals(listOf(BackgroundStyle.AMBIENT, BackgroundStyle.CLASSIC, BackgroundStyle.ONYX), BackgroundStyle.entries)
    }
}
