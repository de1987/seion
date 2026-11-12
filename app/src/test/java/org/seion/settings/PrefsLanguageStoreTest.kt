package org.seion.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Android 12/12L store (the class doesn't depend on the API level; sdk 35 is the Robolectric jar cached here). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PrefsLanguageStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun freshInstallFollowsSystem() {
        assertEquals(AppLanguage.SYSTEM, PrefsLanguageStore(context).get())
    }

    @Test fun overridePersists() {
        PrefsLanguageStore(context).set(AppLanguage.PORTUGUESE_BR)
        assertEquals(AppLanguage.PORTUGUESE_BR, PrefsLanguageStore(context).get())
        PrefsLanguageStore(context).set(AppLanguage.SYSTEM)
        assertEquals(AppLanguage.SYSTEM, PrefsLanguageStore(context).get())
    }
}
