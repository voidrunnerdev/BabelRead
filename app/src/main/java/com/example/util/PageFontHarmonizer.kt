package com.example.util

/**
 * Computes a page-wide dialogue font size baseline so adjacent and connected speech
 * bubbles maintain a cohesive typographic rhythm instead of jumping wildly between
 * 10sp and 24sp on the same manga page.
 */
object PageFontHarmonizer {

    private const val MIN_DIALOGUE_CHARS_FOR_BASELINE = 12
    private const val MAX_SHOUT_CHARS_EXEMPT = 9
    private const val MAX_DIALOGUE_CHARS_FOR_BASELINE = 100
    private const val HARMONIZE_TRIGGER_RATIO = 1.18f
    private const val HARMONIZE_CAP_RATIO = 1.15f
    private const val MIN_HARMONIZED_CAP_SP = 10f

    data class BubbleSample(
        val fontSizeSp: Float,
        val text: String,
        val isNormalSpeechBubble: Boolean,
        val fitsInside: Boolean
    )

    /**
     * Returns the median natural font size across fitted normal dialogue bubbles on the page,
     * or `null` if fewer than 2 qualifying bubbles exist.
     */
    fun computePageBaselineSp(samples: List<BubbleSample>): Float? {
        val normalPool = samples
            .asSequence()
            .filter {
                it.isNormalSpeechBubble &&
                    it.fitsInside &&
                    it.fontSizeSp.isFinite() &&
                    it.fontSizeSp > 0f
            }
            .toList()

        val standardSizes = normalPool
            .filter { it.text.trim().length in MIN_DIALOGUE_CHARS_FOR_BASELINE..MAX_DIALOGUE_CHARS_FOR_BASELINE }
            .map { it.fontSizeSp }
            .sorted()

        val sizes = when {
            standardSizes.size >= 2 -> standardSizes
            standardSizes.size == 1 && normalPool.size >= 2 -> standardSizes
            else -> normalPool
                .filter { it.text.trim().length >= MIN_DIALOGUE_CHARS_FOR_BASELINE }
                .map { it.fontSizeSp }
                .sorted()
        }
        if (sizes.isEmpty()) return null
        if (sizes.size == 1) return sizes[0]
        if (sizes.size == 2) {
            return if (sizes[1] > sizes[0] * 1.45f) (sizes[0] + sizes[1]) * 0.5f else sizes[0]
        }
        val mid = sizes.size / 2
        return if (sizes.size % 2 == 1) sizes[mid] else (sizes[mid - 1] + sizes[mid]) * 0.5f
    }

    /**
     * Returns the capped `maxSp` to re-layout a bubble when its natural size is jarsingly
     * larger than [pageBaseSp], or `null` if the bubble should keep its current size.
     *
     * Short exclamations/shouts (e.g. "DUR!", "NE?!") remain exempt so dramatic impact is preserved.
     */
    fun harmonizedCapSp(
        currentSp: Float,
        text: String,
        pageBaseSp: Float
    ): Float? {
        if (!currentSp.isFinite() || !pageBaseSp.isFinite() || pageBaseSp <= 0f) return null
        val trimmed = text.trim().trim('\"', '\'', '“', '”', '‘', '’', '(', ')')
        val wordCount = trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }
        val isShortShout = wordCount == 1 &&
            trimmed.length <= MAX_SHOUT_CHARS_EXEMPT &&
            (trimmed.endsWith("!") || trimmed.endsWith("!?") || trimmed.endsWith("?!"))
        if (isShortShout) return null
        if (currentSp <= pageBaseSp * HARMONIZE_TRIGGER_RATIO) return null
        val cap = (pageBaseSp * HARMONIZE_CAP_RATIO).coerceAtLeast(MIN_HARMONIZED_CAP_SP)
        return if (cap < currentSp - 0.1f) cap else null
    }
}
