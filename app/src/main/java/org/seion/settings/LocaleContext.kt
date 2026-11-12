package org.seion.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Applies Seion's language to a context (attachBaseContext of the app, the activity and the playback service). */
object LocaleContext {
    /** The device's primary locale, ignoring Seion's own per-app choice. */
    fun systemPrimary(context: Context): Locale {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.systemLocales?.takeIf { !it.isEmpty }?.let { return it[0] }
        }
        return Resources.getSystem().configuration.locales[0]
    }

    fun wrap(base: Context, store: LanguageStore = languageStore(base), system: Locale = systemPrimary(base)): Context {
        val target = UiLanguage.resolve(store.get(), system)
        val current = base.resources.configuration.locales
        if (current.size() == 1 && current[0] == target) return base
        val cfg = Configuration(base.resources.configuration).apply { setLocales(LocaleList(target)) }
        return base.createConfigurationContext(cfg)
    }
}
