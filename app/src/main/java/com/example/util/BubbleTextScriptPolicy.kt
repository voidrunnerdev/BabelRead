package com.example.util

import java.util.Locale

object BubbleTextScriptPolicy {

    private val UNSAFE_LANGUAGES = setOf(
        "hi", "th", "bn", "gu", "kn", "ml", "mr", "ne", "or", "pa",
        "si", "ta", "te", "km", "lo", "my"
    )

    private val SAFE_SCRIPTS = setOf(
        Character.UnicodeScript.LATIN,
        Character.UnicodeScript.GREEK,
        Character.UnicodeScript.CYRILLIC,
        Character.UnicodeScript.ARMENIAN,
        Character.UnicodeScript.GEORGIAN,
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        Character.UnicodeScript.BOPOMOFO,
        Character.UnicodeScript.COMMON
    )

    fun isSafe(targetLanguage: String?, text: CharSequence): Boolean {
        if (TextLayoutLocale.isRtlLanguage(targetLanguage)) return false
        if (TextLayoutLocale.containsRtlCharacter(text)) return false

        val primaryLanguage = targetLanguage
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace('_', '-')
            ?.substringBefore('-')
            .orEmpty()
        if (primaryLanguage in UNSAFE_LANGUAGES) return false

        var offset = 0
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            if (codePoint > 0xFFFF) return false
            if (isCombiningMark(codePoint)) return false
            val script = Character.UnicodeScript.of(codePoint)
            if (script !in SAFE_SCRIPTS) return false
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private fun isCombiningMark(codePoint: Int): Boolean {
        val type = Character.getType(codePoint).toInt()
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }
}
