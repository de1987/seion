package org.seion.settings

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class UiLanguageTest {
    private fun l(tag: String) = Locale.forLanguageTag(tag)

    @Test fun followSystemDefaults() {
        assertEquals(UiLanguage.EN, UiLanguage.resolve(AppLanguage.SYSTEM, l("en-US")))
        assertEquals(UiLanguage.PT_BR, UiLanguage.resolve(AppLanguage.SYSTEM, l("pt-BR")))
        assertEquals(UiLanguage.EN, UiLanguage.resolve(AppLanguage.SYSTEM, l("pt-PT")))
        assertEquals(UiLanguage.EN, UiLanguage.resolve(AppLanguage.SYSTEM, l("ja-JP")))
        assertEquals(UiLanguage.EN, UiLanguage.resolve(AppLanguage.SYSTEM, l("pt")))
        assertEquals(UiLanguage.EN, UiLanguage.resolve(AppLanguage.SYSTEM, l("es-ES")))
    }

    @Test fun overrides() {
        assertEquals(UiLanguage.EN, UiLanguage.resolve(AppLanguage.ENGLISH, l("pt-BR")))
        assertEquals(UiLanguage.PT_BR, UiLanguage.resolve(AppLanguage.PORTUGUESE_BR, l("ja-JP")))
        assertEquals(UiLanguage.PT_BR, UiLanguage.resolve(AppLanguage.PORTUGUESE_BR, l("pt-PT")))
    }

    @Test fun fromAppLocales() {
        assertEquals(AppLanguage.SYSTEM, UiLanguage.fromAppLocales(emptyList()))
        assertEquals(AppLanguage.ENGLISH, UiLanguage.fromAppLocales(listOf("en")))
        assertEquals(AppLanguage.ENGLISH, UiLanguage.fromAppLocales(listOf("en-US")))
        assertEquals(AppLanguage.PORTUGUESE_BR, UiLanguage.fromAppLocales(listOf("pt-BR")))
        assertEquals(AppLanguage.SYSTEM, UiLanguage.fromAppLocales(listOf("pt-PT")))
    }
}
