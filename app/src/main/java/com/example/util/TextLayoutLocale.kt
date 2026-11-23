package com.example.util

import com.example.i18n.TranslationLanguages
import java.util.Locale

/**
 * Resolves the locale used for translated text shaping and line breaking.
 * UI locale and target language are deliberately separate: the UI may be
 * Turkish while the translation is Arabic, Japanese, etc.
 */
object TextLayoutLocale {

    enum class Direction { LTR, RTL }

    // Languages whose default script is right-to-left.
    private val RTL_LANGUAGES = setOf(
        "ar", "arc", "ckb", "dv", "fa", "he", "ks",
        "ku", "ps", "sd", "ug", "ur", "yi"
    )

    // Legacy ISO 639 codes still present in old preferences/cache entries.
    private val LANGUAGE_ALIASES = mapOf(
        "iw" to "he",
        "in" to "id",
        "ji" to "yi"
    )

    fun resolveLocale(targetLanguageCode: String?, uiLocale: Locale = Locale.getDefault()): Locale {
        val code = targetLanguageCode?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (code.isEmpty() || code == TranslationLanguages.AUTO || code == "all") return uiLocale

        val primary = normalizePrimaryLanguage(code)
        val localeTag = TranslationLanguages.find(primary)?.code
            ?: primary.takeIf { it in RTL_LANGUAGES }
            ?: return uiLocale
        val locale = runCatching { Locale.forLanguageTag(localeTag) }.getOrNull()
        return locale?.takeIf { it.language.isNotBlank() } ?: uiLocale
    }

    fun isRtlLanguage(targetLanguageCode: String?): Boolean {
        val code = targetLanguageCode?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return code.isNotEmpty() && normalizePrimaryLanguage(code) in RTL_LANGUAGES
    }

    private fun normalizePrimaryLanguage(code: String): String {
        val primary = code.replace('_', '-').substringBefore('-')
        return LANGUAGE_ALIASES[primary] ?: primary
    }

    /**
     * RTL is selected only when the target language is RTL and the rendered
     * text actually contains RTL letters. A Latin/digit-only translation in an
     * otherwise RTL target keeps LTR base direction; mixed text still goes
     * through Android's bidi algorithm.
     */
    fun directionFor(targetLanguageCode: String?, text: CharSequence): Direction {
        if (!isRtlLanguage(targetLanguageCode)) return Direction.LTR
        return if (containsRtlCharacter(text)) Direction.RTL else Direction.LTR
    }

    fun containsRtlCharacter(text: CharSequence): Boolean {
        for (char in text) {
            if (isRtlCodePoint(char.code)) return true
        }
        return false
    }

    private fun isRtlCodePoint(code: Int): Boolean =
        code in 0x0590..0x05FF || // Hebrew
            code in 0x0600..0x06FF || // Arabic
            code in 0x0700..0x074F || // Syriac
            code in 0x0750..0x077F || // Arabic Supplement
            code in 0x0780..0x07BF || // Thaana
            code in 0x07C0..0x07FF || // NKo
            code in 0x0800..0x083F || // Samaritan
            code in 0x0840..0x085F || // Mandaic
            code in 0x08A0..0x08FF || // Arabic Extended-A
            code in 0xFB1D..0xFB4F || // Hebrew presentation forms
            code in 0xFB50..0xFDFF || // Arabic presentation forms A
            code in 0xFE70..0xFEFF // Arabic presentation forms B
}
