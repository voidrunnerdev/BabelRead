package com.example.util

/**
 * Deterministic Turkish phonetic syllabifier (TDK vowel-nucleus rules: V, CV, VC, CVC, CVCC)
 * used for clean comic-bubble line breaking with visible hyphens.
 *
 * Keeps at least [MIN_SIDE_CHARS] characters on both sides of any break point so
 * bubbles never show ugly 1-letter orphans or widows.
 */
object TurkishSyllabifier {

    const val SOFT_HYPHEN = '\u00AD'
    const val DEFAULT_MIN_WORD_LENGTH = 8
    private const val MIN_SIDE_CHARS = 3

    private val VOWELS = setOf(
        'a', 'e', 'ı', 'i', 'o', 'ö', 'u', 'ü',
        'A', 'E', 'I', 'İ', 'O', 'Ö', 'U', 'Ü',
        'â', 'î', 'û', 'Â', 'Î', 'Û'
    )

    fun isVowel(ch: Char): Boolean = ch in VOWELS

    /**
     * Returns sorted, valid syllable break offsets inside [word] (each offset is in
     * `MIN_SIDE_CHARS .. word.length - MIN_SIDE_CHARS`).
     *
     * Words shorter than [minWordLength], words containing digits, or short ALL-CAPS
     * acronyms return an empty list.
     */
    fun syllableBreakOffsets(
        word: String,
        minWordLength: Int = DEFAULT_MIN_WORD_LENGTH
    ): List<Int> {
        val len = word.length
        if (len < minWordLength.coerceAtLeast(MIN_SIDE_CHARS * 2)) return emptyList()

        var allUpper = true
        val vowelIndices = ArrayList<Int>(len / 2 + 1)
        val apostropheIndices = ArrayList<Int>(2)
        for (i in 0 until len) {
            val ch = word[i]
            if (ch == '\'' || ch == '’') {
                if (i == 0 || i == len - 1) return emptyList()
                apostropheIndices.add(i)
            } else if (!ch.isLetter()) {
                return emptyList()
            } else {
                if (ch.isLowerCase()) allUpper = false
                if (isVowel(ch)) vowelIndices.add(i)
            }
        }
        // Keep short uppercase acronyms/onomatopoeia intact (e.g. "NASA", "HAHA").
        if (allUpper && len <= 5) return emptyList()
        if (vowelIndices.size < 2 || vowelIndices.size == len) return emptyList()

        val breaks = ArrayList<Int>(vowelIndices.size + apostropheIndices.size)
        for (k in 0 until vowelIndices.size - 1) {
            val v1 = vowelIndices[k]
            val v2 = vowelIndices[k + 1]
            val consonantsBetween = (v1 + 1 until v2).count { word[it].isLetter() && !isVowel(word[it]) }
            val split = when (consonantsBetween) {
                0 -> v1 + 1        // sa-at, a-i-le
                1 -> v1 + 1        // a-ra-ba, ge-le-cek
                2 -> v1 + 2        // kar-şı, gel-dim
                else -> v2 - 1     // türk-çe, elekt-rik, kont-rol
            }
            if (split >= MIN_SIDE_CHARS && len - split >= MIN_SIDE_CHARS) {
                if (!breaks.contains(split)) {
                    breaks.add(split)
                }
            }
            for (apo in apostropheIndices) {
                if (apo in v1 until v2) {
                    val apoSplit = apo + 1
                    if (apoSplit >= MIN_SIDE_CHARS && len - apoSplit >= 2) {
                        if (!breaks.contains(apoSplit)) {
                            breaks.add(apoSplit)
                        }
                    }
                }
            }
        }
        breaks.sort()
        return breaks
    }

    /**
     * Inserts Unicode soft hyphens ([SOFT_HYPHEN]) at valid Turkish syllable boundaries
     * inside words of length >= [minWordLength].
     *
     * Android's StaticLayout renders [SOFT_HYPHEN] invisibly when the word stays on one
     * line and automatically draws a visible '-' only when the line wraps at that offset.
     */
    fun insertSoftHyphens(
        text: String,
        minWordLength: Int = DEFAULT_MIN_WORD_LENGTH
    ): String {
        if (text.length < minWordLength) return text
        val builder = StringBuilder(text.length + 16)
        var index = 0
        while (index < text.length) {
            if (text[index].isLetter()) {
                var end = index
                while (end < text.length && (text[end].isLetter() || text[end] == '\'' || text[end] == '’')) end++
                while (end > index && (text[end - 1] == '\'' || text[end - 1] == '’')) end--
                val word = text.substring(index, end)
                val breaks = syllableBreakOffsets(word, minWordLength)
                if (breaks.isEmpty()) {
                    builder.append(word)
                } else {
                    var prev = 0
                    for (split in breaks) {
                        builder.append(word, prev, split)
                        builder.append(SOFT_HYPHEN)
                        prev = split
                    }
                    builder.append(word, prev, word.length)
                }
                index = end
            } else {
                builder.append(text[index])
                index++
            }
        }
        return builder.toString()
    }
}
