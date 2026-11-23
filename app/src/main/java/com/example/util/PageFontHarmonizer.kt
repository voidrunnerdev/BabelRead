package com.example.util

/**
 * Computes a page-wide dialogue font size baseline so adjacent and connected speech
 * bubbles maintain a cohesive typographic rhythm instead of jumping wildly between
 * 10sp and 24sp on the same manga page.
 */
object PageFontHarmonizer {

    private const val MIN_DIALOGUE_CHARS_FOR_BASELINE = 12
    private const val MAX_SHOUT_CHARS_EXEMPT = 6
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
        val sizes = samples
            .asSequence()
            .filter {
                it.isNormalSpeechBubble &&
                    it.fitsInside &&
                    it.fontSizeSp.isFinite() &&
                    it.fontSizeSp > 0f &&
                    it.text.trim().length >= MIN_DIALOGUE_CHARS_FOR_BASELINE
            }
            .map { it.fontSizeSp }
            .sorted()
            .toList()
        if (sizes.size < 2) return null
        // For 2-bubble pages (e.g. a dense 11sp bubble paired with a loose 19sp bubble),
        // anchor to the denser bubble so the loose one harmonizes cleanly instead of pulling
        // the midpoint up. For 3+ bubbles, use the lower median.
        return if (sizes.size == 2) {
            sizes[0]
        } else {
            sizes[(sizes.size - 1) / 2]
        }
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
        val trimmed = text.trim()
        val isShortShout = trimmed.length <= MAX_SHOUT_CHARS_EXEMPT &&
            (trimmed.endsWith("!") || trimmed.endsWith("!?"))
        if (isShortShout) return null
        if (currentSp <= pageBaseSp * HARMONIZE_TRIGGER_RATIO) return null
        val cap = (pageBaseSp * HARMONIZE_CAP_RATIO).coerceAtLeast(MIN_HARMONIZED_CAP_SP)
        return if (cap < currentSp - 0.1f) cap else null
    }
}
