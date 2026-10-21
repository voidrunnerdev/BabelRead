package com.example.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

data class AppLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val rtl: Boolean = false,
)

object AppLanguages {
    const val SYSTEM = "system"

    val turkish = AppLanguage("tr", "Türkçe", "Turkish")
    val english = AppLanguage("en", "English", "English")

    val popular: List<AppLanguage> = listOf(
        turkish,
        english,
        AppLanguage("es", "Español", "Spanish"),
        AppLanguage("pt", "Português", "Portuguese"),
        AppLanguage("fr", "Français", "French"),
        AppLanguage("de", "Deutsch", "German"),
        AppLanguage("ru", "Русский", "Russian"),
        AppLanguage("ar", "العربية", "Arabic", rtl = true),
        AppLanguage("ja", "日本語", "Japanese"),
        AppLanguage("zh", "简体中文", "Chinese (Simplified)"),
    )

    private val byCode = popular.associateBy { it.code }

    fun find(code: String?): AppLanguage? =
        byCode[code?.trim()?.lowercase(Locale.ROOT)]

    fun resolve(requested: String?): AppLanguage {
        val normalized = requested?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isNotEmpty() && normalized != SYSTEM) {
            return find(normalized) ?: english
        }
        return systemLanguage()
    }

    fun systemLanguage(): AppLanguage {
        val language = Locale.getDefault().language.lowercase(Locale.ROOT)
        return byCode[language] ?: english
    }
}

val LocalAppLanguage = staticCompositionLocalOf { AppLanguages.systemLanguage() }

object AppLanguageState {
    @Volatile
    var current: AppLanguage = AppLanguages.systemLanguage()
}
