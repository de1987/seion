package org.seion.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.seion.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocaleContextTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private class Fixed(var value: AppLanguage) : LanguageStore {
        override fun get() = value

        override fun set(value: AppLanguage) {
            this.value = value
        }
    }

    private fun settingsLabel(store: LanguageStore, system: String) =
        LocaleContext.wrap(context, store, Locale.forLanguageTag(system)).getString(R.string.settings)

    @Test fun freshInstall() {
        val store = Fixed(AppLanguage.SYSTEM)
        assertEquals("Settings", settingsLabel(store, "en-US"))
        assertEquals("Configurações", settingsLabel(store, "pt-BR"))
        // Android alone would resolve pt-PT to the pt-BR strings (sibling fallback); Seion falls back to English instead.
        assertEquals("Settings", settingsLabel(store, "pt-PT"))
        assertEquals("Settings", settingsLabel(store, "ja-JP"))
    }

    @Test
    @Config(qualifiers = "pt-rPT")
    fun `a pt-PT device gets English, not the sibling pt-BR strings`() {
        assertEquals("Settings", settingsLabel(Fixed(AppLanguage.SYSTEM), "pt-PT"))
    }

    @Test fun overrideAndBack() {
        val store = Fixed(AppLanguage.PORTUGUESE_BR)
        assertEquals("Configurações", settingsLabel(store, "en-US"))
        store.set(AppLanguage.SYSTEM)
        assertEquals("Settings", settingsLabel(store, "en-US"))
        assertEquals("Configurações", settingsLabel(store, "pt-BR"))
        store.set(AppLanguage.ENGLISH)
        assertEquals("Settings", settingsLabel(store, "pt-BR"))
    }
}
