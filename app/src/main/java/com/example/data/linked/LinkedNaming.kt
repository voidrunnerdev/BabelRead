package com.example.data.linked

object LinkedNaming {
    private val explicitChapterNumber = Regex(
        "(?i)(?:b[oö]l[uü]m|chapter|ch\\.?|episode|ep\\.?)\\s*[._-]?\\s*(\\d+(?:[.,]\\d+)?)"
    )
    private val anyNumber = Regex("\\d+(?:[.,]\\d+)?")

    fun cleanTitle(rawName: String): String {
        return rawName
            .substringBeforeLast(".")
            .replace("_", " ")
            .replace("-", " ")
            .replace(".", " ")
            .trim()
            .ifBlank { "Yerel Manga" }
    }

    fun chapterNumber(rawName: String, fallback: Int): Double {
        val trimmed = rawName.trim()
        val match = explicitChapterNumber.find(trimmed)
            ?: anyNumber.find(trimmed)
        val raw = match?.groupValues?.getOrNull(1)
            ?: match?.value
            ?: return fallback.toDouble()
        return raw.replace(',', '.').toDoubleOrNull() ?: fallback.toDouble()
    }
}
