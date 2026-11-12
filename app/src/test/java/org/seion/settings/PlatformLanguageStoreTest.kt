package org.seion.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformLanguageStoreTest {
    private class FakeAppLocales : AppLocales {
        var tags: List<String> = emptyList()

        override fun get() = tags

        override fun set(tags: List<String>) {
            this.tags = tags
        }
    }

    @Test fun `writes the system per-app setting and reads it back`() {
        val fake = FakeAppLocales()
        val store = PlatformLanguageStore(fake)
        assertEquals(AppLanguage.SYSTEM, store.get())
        store.set(AppLanguage.ENGLISH)
        assertEquals(listOf("en"), fake.tags)
        store.set(AppLanguage.PORTUGUESE_BR)
        assertEquals(listOf("pt-BR"), fake.tags)
        store.set(AppLanguage.SYSTEM)
        assertEquals(emptyList<String>(), fake.tags)
    }

    @Test fun `a change made in the system settings is what Seion reads`() {
        val fake = FakeAppLocales()
        val store = PlatformLanguageStore(fake)
        fake.tags = listOf("pt-BR")
        assertEquals(AppLanguage.PORTUGUESE_BR, store.get())
        fake.tags = listOf("en-GB")
        assertEquals(AppLanguage.ENGLISH, store.get())
        fake.tags = emptyList()
        assertEquals(AppLanguage.SYSTEM, store.get())
    }
}
