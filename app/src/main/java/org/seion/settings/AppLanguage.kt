package org.seion.settings

import java.util.Locale

/** Seion's language choice. [tag] is what the system per-app setting stores (null = follow the system). */
enum class AppLanguage(val tag: String?) { SYSTEM(null), ENGLISH("en"), PORTUGUESE_BR("pt-BR") }

/** Which language Seion shows (pure, unit-tested). */
object UiLanguage {
    val EN: Locale = Locale.forLanguageTag("en")
    val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

    /**
     * The locale Seion shows. Follow system: a pt-BR primary device language -> pt-BR, everything else (pt-PT, a bare
     * pt, es, ja...) -> English. Android's own resolution would give pt-PT users the pt-BR strings.
     */
    fun resolve(choice: AppLanguage, systemPrimary: Locale): Locale = when (choice) {
        AppLanguage.ENGLISH -> EN
        AppLanguage.PORTUGUESE_BR -> PT_BR
        AppLanguage.SYSTEM -> if (systemPrimary.language == "pt" && systemPrimary.country == "BR") PT_BR else EN
    }

    /** The system per-app setting -> Seion's choice (empty = follow system; unknown tags = follow system). */
    fun fromAppLocales(tags: List<String>): AppLanguage {
        val first = tags.firstOrNull { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: return AppLanguage.SYSTEM
        return when {
            first.language == "en" -> AppLanguage.ENGLISH
            first.language == "pt" && first.country == "BR" -> AppLanguage.PORTUGUESE_BR
            else -> AppLanguage.SYSTEM
        }
    }
}
