package com.example.i18n

import java.util.Locale

data class TranslationLanguage(
    val code: String,
    val turkishName: String,
    val nativeName: String,
    val englishName: String,
    val deeplTarget: String? = null,
    val mlKitTag: String? = null,
)

object TranslationLanguages {
    const val AUTO = "auto"

    private val catalog: List<TranslationLanguage> = listOf(
        TranslationLanguage("tr", "Türkçe", "Türkçe", "Turkish", "TR", "tr"),
        TranslationLanguage("en", "İngilizce", "English", "English", "EN-US", "en"),
        TranslationLanguage("es", "İspanyolca", "Español", "Spanish", "ES", "es"),
        TranslationLanguage("fr", "Fransızca", "Français", "French", "FR", "fr"),
        TranslationLanguage("de", "Almanca", "Deutsch", "German", "DE", "de"),
        TranslationLanguage("it", "İtalyanca", "Italiano", "Italian", "IT", "it"),
        TranslationLanguage("pt", "Portekizce", "Português", "Portuguese", "PT-BR", "pt"),
        TranslationLanguage("ru", "Rusça", "Русский", "Russian", "RU", "ru"),
        TranslationLanguage("ar", "Arapça", "العربية", "Arabic", "AR", "ar"),
        TranslationLanguage("ja", "Japonca", "日本語", "Japanese", "JA", "ja"),
        TranslationLanguage("ko", "Korece", "한국어", "Korean", "KO", "ko"),
        TranslationLanguage("zh", "Çince", "简体中文", "Chinese (Simplified)", "ZH", "zh"),
        TranslationLanguage("hi", "Hintçe", "हिन्दी", "Hindi", null, "hi"),
        TranslationLanguage("id", "Endonezce", "Bahasa Indonesia", "Indonesian", "ID", "id"),
        TranslationLanguage("vi", "Vietnamca", "Tiếng Việt", "Vietnamese", null, "vi"),
        TranslationLanguage("th", "Tayca", "ไทย", "Thai", null, "th"),
        TranslationLanguage("pl", "Lehçe", "Polski", "Polish", "PL", "pl"),
        TranslationLanguage("nl", "Hollandaca", "Nederlands", "Dutch", "NL", "nl"),
        TranslationLanguage("uk", "Ukraynaca", "Українська", "Ukrainian", "UK", "uk"),
        TranslationLanguage("fa", "Farsça", "فارسی", "Persian", null, "fa"),
        TranslationLanguage("cs", "Çekçe", "Čeština", "Czech", "CS", "cs"),
        TranslationLanguage("ro", "Romence", "Română", "Romanian", "RO", "ro"),
        TranslationLanguage("hu", "Macarca", "Magyar", "Hungarian", "HU", "hu"),
        TranslationLanguage("sv", "İsveççe", "Svenska", "Swedish", "SV", "sv"),
        TranslationLanguage("da", "Danca", "Dansk", "Danish", "DA", "da"),
        TranslationLanguage("fi", "Fince", "Suomi", "Finnish", "FI", "fi"),
        TranslationLanguage("no", "Norveççe", "Norsk", "Norwegian", "NB", "no"),
        TranslationLanguage("all", "Çok Dilli", "Multi-language", "Multi-language"),
        TranslationLanguage("jv", "Cava dili", "Basa Jawa", "Javanese"),
        TranslationLanguage("ceb", "Cebuano", "Cebuano", "Cebuano"),
        TranslationLanguage("ca", "Katalanca", "Català", "Catalan"),
        TranslationLanguage("el", "Yunanca", "Ελληνικά", "Greek", "EL"),
        TranslationLanguage("he", "İbranice", "עברית", "Hebrew"),
        TranslationLanguage("bg", "Bulgarca", "Български", "Bulgarian", "BG"),
        TranslationLanguage("sr", "Sırpça", "Српски", "Serbian"),
        TranslationLanguage("hr", "Hırvatça", "Hrvatski", "Croatian", null, "hr"),
        TranslationLanguage("sk", "Slovakça", "Slovenčina", "Slovak", "SK", "sk"),
        TranslationLanguage("sl", "Slovence", "Slovenščina", "Slovenian", "SL", "sl"),
        TranslationLanguage("lt", "Litvanca", "Lietuvių", "Lithuanian", "LT", "lt"),
        TranslationLanguage("lv", "Letonca", "Latviešu", "Latvian", "LV", "lv"),
        TranslationLanguage("et", "Estonca", "Eesti", "Estonian", "ET", "et"),
        TranslationLanguage("ms", "Malayca", "Bahasa Melayu", "Malay", null, "ms"),
        TranslationLanguage("tl", "Filipince", "Filipino", "Filipino", null, "tl"),
        TranslationLanguage("az", "Azerice", "Azərbaycan dili", "Azerbaijani"),
        TranslationLanguage("kk", "Kazakça", "Қазақ тілі", "Kazakh"),
        TranslationLanguage("uz", "Özbekçe", "Oʻzbekcha", "Uzbek"),
        TranslationLanguage("sq", "Arnavutça", "Shqip", "Albanian"),
        TranslationLanguage("is", "İzlandaca", "Íslenska", "Icelandic", null, "is"),
        TranslationLanguage("ga", "İrlandaca", "Gaeilge", "Irish"),
        TranslationLanguage("mt", "Maltaca", "Malti", "Maltese"),
        TranslationLanguage("eo", "Esperanto", "Esperanto", "Esperanto", null, "eo"),
    )

    private val byCode = catalog.associateBy { it.code }

    val targets: List<TranslationLanguage> = listOf(
        "tr", "en", "es", "fr", "de", "it", "pt", "ru", "ar", "ja",
        "ko", "zh", "hi", "id", "vi", "th", "pl", "nl", "uk", "fa",
    ).mapNotNull(byCode::get)

    val sources: List<TranslationLanguage> = listOf(
        "en", "tr", "es", "fr", "de", "it", "pt", "nl", "pl",
        "cs", "ro", "hu", "sv", "da", "fi",
    ).mapNotNull(byCode::get)

    fun find(code: String?): TranslationLanguage? =
        byCode[code?.trim()?.lowercase(Locale.ROOT)]

    fun turkishName(code: String?): String? = find(code)?.turkishName

    fun englishName(code: String?): String? = find(code)?.englishName

    fun nativeName(code: String?): String? = find(code)?.nativeName

    fun deeplTarget(code: String?): String? = find(code)?.deeplTarget

    fun mlKitTag(code: String?): String? = find(code)?.mlKitTag

    fun defaultTargetCode(): String {
        val language = Locale.getDefault().language.lowercase(Locale.ROOT)
        return if (targets.any { it.code == language }) language else "tr"
    }

    fun codeForDisplayName(name: String?): String? {
        val key = name?.trim()?.lowercase(Locale.ROOT)?.replace("\u0307", "") ?: return null
        if (key.isBlank()) return null
        byCode[key]?.let { return it.code }
        return byCode.values.firstOrNull { entry ->
            entry.turkishName.lowercase(Locale.ROOT).replace("\u0307", "") == key ||
                entry.englishName.lowercase(Locale.ROOT) == key ||
                entry.nativeName.lowercase(Locale.ROOT) == key
        }?.code
    }
}
