package com.example.i18n

import android.content.Context
import org.json.JSONObject
import java.util.Locale

object UiTranslations {
    private var translations: Map<String, Map<String, String>> = emptyMap()

    fun load(context: Context) {
        translations = runCatching {
            val json = context.assets
                .open("ui_translations.json")
                .bufferedReader()
                .use { it.readText() }
            val root = JSONObject(json)
            buildMap {
                root.keys().forEach { language ->
                    val table = root.optJSONObject(language) ?: return@forEach
                    val values = buildMap {
                        table.keys().forEach { key ->
                            val value = table.optString(key)
                            if (value.isNotBlank()) put(key, value)
                        }
                    }
                    put(language, values)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun translate(language: AppLanguage, text: String): String {
        if (language.code == "tr" || text.isBlank()) return text
        val table = translations[language.code] ?: return text
        table[text]?.let { return matchSourceCase(text, it, language) }

        if (text.contains(" | ")) {
            return text.split(" | ").joinToString(" | ") { translate(language, it) }
        }

        val colon = text.indexOf(": ")
        if (colon > 0) {
            val label = text.substring(0, colon)
            table[label]?.let { return matchSourceCase(label, it, language) + text.substring(colon) }
        }

        val parenthesis = text.indexOf(" (")
        if (parenthesis > 0) {
            val label = text.substring(0, parenthesis)
            table[label]?.let { return matchSourceCase(label, it, language) + text.substring(parenthesis) }
        }

        val dynamicPrefixes = listOf("İndiriliyor ", "Hata: ", "Durum: ", "Web: ")
        for (prefix in dynamicPrefixes) {
            if (text.startsWith(prefix)) {
                val label = prefix.trimEnd(':', ' ')
                table[label]?.let {
                    return matchSourceCase(label, it, language) +
                        prefix.substring(label.length) +
                        text.substring(prefix.length)
                }
            }
        }

        return text
    }

    private fun matchSourceCase(source: String, translated: String, language: AppLanguage): String {
        if (source.firstOrNull()?.isUpperCase() != true) return translated
        if (translated.firstOrNull()?.isLowerCase() != true) return translated
        return translated.replaceFirstChar {
            it.titlecase(Locale.forLanguageTag(language.code))
        }
    }
}
