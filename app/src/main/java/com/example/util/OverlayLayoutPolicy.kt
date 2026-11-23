package com.example.util

/**
 * Pure decisions for translated-text overlays. Keeping these out of the
 * composable makes the subtitle/background fallbacks deterministic and easy
 * to test without touching RenderThread or StaticLayout.
 */
object OverlayLayoutPolicy {

    const val READABLE_MIN_SP = 6f
    const val READABLE_SUBTITLE_SP = 8f
    const val MIN_FORCED_DRAW_SCALE = 0.7f

    data class VerticalBounds(val top: Float, val height: Float)
    data class SubtitleFit(val fitsInside: Boolean, val fontSizeSp: Float)
    enum class SubtitleFitChoice { ORIGINAL, HYPHENATED, MINIMUM, FALLBACK }

    fun expandSubtitleToRequiredHeight(
        top: Float,
        height: Float,
        requiredHeight: Float,
        pageHeight: Float
    ): VerticalBounds {
        val targetHeight = maxOf(height, requiredHeight)
        val boundedHeight = targetHeight.coerceAtMost(pageHeight).coerceAtLeast(1f)
        val centeredTop = top - (boundedHeight - height) / 2f
        val maxTop = (pageHeight - boundedHeight).coerceAtLeast(0f)
        return VerticalBounds(centeredTop.coerceIn(0f, maxTop), boundedHeight)
    }

    /**
     * Subtitles retry at the readable minimum whenever the current attempt does
     * not fit, including when the current attempt is already 6sp. The fit
     * comparison below decides whether hyphenation can produce a larger result.
     */
    fun shouldShrinkSubtitleToMinimum(fitsInside: Boolean, fontSizeSp: Float): Boolean =
        !fitsInside && fontSizeSp >= READABLE_MIN_SP

    /**
     * Chooses the best subtitle attempt after vertical expansion. A larger
     * hyphenated fit must win over accepting the 6sp plain fit too early.
     */
    fun chooseSubtitleFit(
        original: SubtitleFit,
        hyphenated: SubtitleFit,
        minimum: SubtitleFit
    ): SubtitleFitChoice {
        if (original.fitsInside) return SubtitleFitChoice.ORIGINAL
        if (hyphenated.fitsInside && hyphenated.fontSizeSp >= READABLE_MIN_SP) {
            if (!minimum.fitsInside || hyphenated.fontSizeSp >= minimum.fontSizeSp) {
                return SubtitleFitChoice.HYPHENATED
            }
        }
        if (minimum.fitsInside) return SubtitleFitChoice.MINIMUM
        return SubtitleFitChoice.FALLBACK
    }

    fun canForceTextToFit(fontSizeSp: Float, drawScale: Float): Boolean =
        fontSizeSp >= READABLE_MIN_SP && drawScale >= MIN_FORCED_DRAW_SCALE

    const val MIN_READABLE_OVERLAY_WIDTH_DP = 44f
    const val MIN_CHARS_PER_LINE_THRESHOLD = 3.2f
    const val MIN_HORIZONTAL_ASPECT_RATIO = 0.35f

    /**
     * Detects narrow vertical slivers that chop horizontal words into 1-2 letter
     * fragments (e.g. PU \n CEZASI \n İÇİN BU \n KONUDA \n NE DÜŞÜ- \n NÜ-).
     */
    fun isPathologicalSliver(
        widthDp: Float,
        heightDp: Float,
        lineCount: Int,
        textLength: Int
    ): Boolean {
        if (widthDp < 36f) return true
        val aspect = widthDp / heightDp.coerceAtLeast(1f)
        if (aspect < MIN_HORIZONTAL_ASPECT_RATIO && textLength > 12) return true
        if (lineCount >= 4 && (textLength.toFloat() / lineCount) < MIN_CHARS_PER_LINE_THRESHOLD) return true
        return false
    }

    fun shouldDrawBackground(
        hasText: Boolean,
        hasLayout: Boolean,
        fitsInside: Boolean,
        forceTextToFitBox: Boolean,
        isSliver: Boolean = false
    ): Boolean = hasText && hasLayout && (fitsInside || forceTextToFitBox) && !isSliver
}
