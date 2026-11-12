package org.seion.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.core.content.edit

/** Where Seion's language choice lives. Read synchronously: attachBaseContext can't wait for DataStore. */
interface LanguageStore {
    fun get(): AppLanguage

    fun set(value: AppLanguage)
}

/** Thin seam over [LocaleManager]'s per-app locales, faked in tests. */
interface AppLocales {
    fun get(): List<String>

    fun set(tags: List<String>)
}

/**
 * Android 13+: the system per-app locale is the single source of truth (it persists across updates and is what
 * Settings › Apps › Seion › Language shows and edits).
 */
class PlatformLanguageStore(private val locales: AppLocales) : LanguageStore {
    override fun get(): AppLanguage = UiLanguage.fromAppLocales(locales.get())

    override fun set(value: AppLanguage) = locales.set(listOfNotNull(value.tag))
}

@RequiresApi(33)
class LocaleManagerAppLocales(context: Context) : AppLocales {
    private val manager = context.getSystemService(LocaleManager::class.java)

    override fun get(): List<String> = manager?.applicationLocales?.toLanguageTags().orEmpty().split(',').filter { it.isNotBlank() }

    override fun set(tags: List<String>) {
        manager?.applicationLocales =
            if (tags.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tags.joinToString(","))
    }
}

/** Android 12/12L: SharedPreferences "seion_locale", key "language" (the enum name), written with commit(). */
class PrefsLanguageStore(context: Context) : LanguageStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(): AppLanguage = prefs.getString(KEY, null)?.let { n -> AppLanguage.entries.firstOrNull { it.name == n } }
        ?: AppLanguage.SYSTEM

    override fun set(value: AppLanguage) = prefs.edit(commit = true) { putString(KEY, value.name) }

    private companion object {
        const val PREFS = "seion_locale"
        const val KEY = "language"
    }
}

fun languageStore(context: Context): LanguageStore =
    if (Build.VERSION.SDK_INT >= 33) PlatformLanguageStore(LocaleManagerAppLocales(context)) else PrefsLanguageStore(context)
