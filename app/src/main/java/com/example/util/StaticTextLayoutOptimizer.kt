package com.example.util

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

object StaticTextLayoutOptimizer {

    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val BASE_LINE_SPACING = 1.08f
    private const val TIGHT_LINE_SPACING = 1.03f
    private const val BASE_LETTER_SPACING = -0.01f
    private const val TIGHT_LETTER_SPACING = -0.018f
    private const val TIGHT_SP_THRESHOLD = 0.6f
    private const val FONT_SEARCH_STEP_SP = 0.3f

    data class OptimizedLayoutResult(
        val fontSizeSp: Float,
        val lineSpacingMultiplier: Float,
        /** Paint.letterSpacing biçiminde, font boyutuna göre em cinsinden aralık (API 21+). */
        val letterSpacing: Float,
        val lineCount: Int,
        val totalHeightPx: Float,
        val maxLineWidthPx: Float,
        val fitsInside: Boolean,
        val requiredWidthDp: Float,
        val staticLayout: StaticLayout?
    )

    /**
     * Konuşma balonuna metin yerleştirirken:
     * - StaticLayout satır sonlarını locale tabanlı yasal kırılma noktalarıyla doğrular.
     * - Ölçüm ve çizimde aynı StaticLayout'u kullanarak font ve satır düzenini korur.
     * - Yaklaşık monoton sığma varsayımıyla ikili aramada büyük bir yazı boyutu bulur;
     *   Android satır kırma sezgileri nedeniyle bu her zaman mutlak en büyük boyut olmayabilir.
     * - En küçük boyut da sığmıyorsa denenen ölçümler arasındaki taşması en düşük sonucu döndürür.
     * - Yazı boyutu aralığını taramak yerine logaritmik sayıda StaticLayout ölçümü yapar.
     * - allowBrokenWords açıkken tireleme etkinleşir ve kelime ortası kırılmalar yalnızca
     *   kırılan parça satırın büyük bölümünü dolduruyorsa kabul edilir; böylece dar
     *   balonlarda uzun kelimeler yüzünden yazı minicik kalmaz.
     */
    fun optimizeLayout(
        text: String,
        // Bounds are in dp; density converts them to the px used by StaticLayout.
        bounds: RectF,
        minSp: Float = 5.0f,
        maxSp: Float = 14.0f,
        density: Float,
        spToPx: (Float) -> Float,
        layoutLocale: Locale = Locale.getDefault(),
        isOvalBubble: Boolean = true,
        measureTypeface: Typeface? = null,
        allowBrokenWords: Boolean = false
    ): OptimizedLayoutResult {
        require(minSp.isFinite() && maxSp.isFinite() && minSp > 0f && maxSp >= minSp) {
            "Font size range must be finite, positive, and ordered"
        }
        require(density.isFinite() && density > 0f) {
            "Density must be finite and greater than zero"
        }

        val cleanText = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .joinToString("\n") { line -> WHITESPACE_REGEX.replace(line, " ").trim() }
            .trim()
        val widthDp = bounds.width().coerceAtLeast(1f)
        val heightDp = bounds.height().coerceAtLeast(1f)
        val targetWidthPx = widthDp * density
        val targetHeightPx = heightDp * density
        if (cleanText.isEmpty()) {
            return OptimizedLayoutResult(
                fontSizeSp = maxSp,
                lineSpacingMultiplier = BASE_LINE_SPACING,
                letterSpacing = BASE_LETTER_SPACING,
                lineCount = 0,
                totalHeightPx = 0f,
                maxLineWidthPx = 0f,
                fitsInside = true,
                requiredWidthDp = 0f,
                staticLayout = null
            )
        }

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = measureTypeface ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = BASE_LETTER_SPACING
            textLocale = layoutLocale
        }

        // Binary search a large fitting font size. This is approximately monotonic;
        // BALANCED line breaking can introduce rare discontinuities between sizes.
        var smallestOverflowResult: OptimizedLayoutResult? = null
        var minOverflowPx = Float.MAX_VALUE
        val safeWidthPx = max(8, targetWidthPx.toInt())
        val lastIndex = ceil((maxSp - minSp) / FONT_SEARCH_STEP_SP).toInt().coerceAtLeast(0)
        val lineBreakIterator = BreakIterator.getLineInstance(layoutLocale).apply {
            setText(cleanText)
        }

        val aspectRatio = (targetWidthPx / targetHeightPx).coerceIn(0.25f, 4.0f)

        fun measureCandidate(candidateSp: Float): Pair<OptimizedLayoutResult, Float> {
            val textSizePx = spToPx(candidateSp)
            require(textSizePx.isFinite() && textSizePx > 0f) {
                "spToPx must return a finite value greater than zero"
            }
            // The near-minimum adjustment only tightens spacing, so smaller candidates
            // remain no harder to fit when the search crosses this threshold.
            val isNearMinimum = candidateSp <= minSp + TIGHT_SP_THRESHOLD
            val candidateLineSpacing = if (isNearMinimum) TIGHT_LINE_SPACING else BASE_LINE_SPACING
            val candidateLetterSpacing = if (isNearMinimum) TIGHT_LETTER_SPACING else BASE_LETTER_SPACING

            // StaticLayout may keep the supplied paint for later drawing. Give every
            // candidate its own instance so later measurements cannot alter an earlier layout.
            val candidatePaint = TextPaint(textPaint).apply {
                textSize = textSizePx
                letterSpacing = candidateLetterSpacing
            }

            val layout = buildStaticLayout(
                cleanText,
                candidatePaint,
                safeWidthPx,
                candidateLineSpacing,
                hyphenation = allowBrokenWords
            )
            val totalH = layout.height.toFloat()
            var maxLineW = 0f
            for (i in 0 until layout.lineCount) {
                val lw = layout.getLineWidth(i)
                if (lw > maxLineW) maxLineW = lw
            }

            val firstLineW = if (layout.lineCount > 0) layout.getLineWidth(0) else 0f
            val lastLineW = if (layout.lineCount > 1) layout.getLineWidth(layout.lineCount - 1) else 0f

            val edgeWidthRatio = if (isOvalBubble && layout.lineCount > 0) {
                val widePenalty = ((aspectRatio - 0.75f) / 1.75f).coerceIn(0f, 1f)
                (0.94f - widePenalty * 0.16f).coerceIn(0.78f, 0.94f)
            } else {
                1.0f
            }

            // StaticLayout can emergency-break an unbreakable token to keep the line
            // inside the box. Validate the actual line-end offsets against the same
            // locale-aware boundaries instead of estimating token widths separately.
            // With allowBrokenWords, a mid-word break (hyphenated or emergency) is
            // tolerated only when the broken piece still fills most of the line; that
            // rejects ugly single-glyph fragments while letting long words split.
            var hasIllegalLineBreak = false
            for (line in 0 until (layout.lineCount - 1).coerceAtLeast(0)) {
                val lineEnd = layout.getLineEnd(line)
                if (lineEnd < cleanText.length && !lineBreakIterator.isBoundary(lineEnd)) {
                    if (!allowBrokenWords || layout.getLineWidth(line) < targetWidthPx * 0.55f) {
                        hasIllegalLineBreak = true
                        break
                    }
                }
            }

            val ovalRequiredWidthPx = if (isOvalBubble && layout.lineCount > 0) {
                max(firstLineW, lastLineW) / edgeWidthRatio
            } else {
                0f
            }
            val requiredWidthPx = max(maxLineW, ovalRequiredWidthPx)
            val fitsHorizontally = requiredWidthPx <= targetWidthPx && !hasIllegalLineBreak

            val safeVerticalPaddingPx = max(1.5f, textSizePx * 0.10f)
            val fitsVertically = totalH + safeVerticalPaddingPx * 2f <= targetHeightPx

            val neededWidthDp = requiredWidthPx / density
            val overflowH = max(0f, totalH + safeVerticalPaddingPx * 2f - targetHeightPx)
            val overflowW = max(0f, requiredWidthPx - targetWidthPx)
            val lineBreakOverflow = if (hasIllegalLineBreak) targetWidthPx else 0f
            // requiredWidthPx already includes the oval edge-width allowance.
            val totalOverflow = overflowH * 1.5f + overflowW + lineBreakOverflow
            val result = OptimizedLayoutResult(
                fontSizeSp = candidateSp,
                lineSpacingMultiplier = candidateLineSpacing,
                letterSpacing = candidateLetterSpacing,
                lineCount = layout.lineCount,
                totalHeightPx = totalH,
                maxLineWidthPx = maxLineW,
                fitsInside = fitsHorizontally && fitsVertically,
                requiredWidthDp = neededWidthDp,
                staticLayout = layout
            )
            return result to totalOverflow
        }

        var low = 0
        var high = lastIndex
        var bestFit: OptimizedLayoutResult? = null
        while (low <= high) {
            val mid = low + (high - low) / 2
            val candidateSp = max(minSp, maxSp - mid * FONT_SEARCH_STEP_SP)
            val (candidate, overflow) = measureCandidate(candidateSp)
            if (candidate.fitsInside) {
                bestFit = candidate
                high = mid - 1
            } else {
                if (overflow < minOverflowPx) {
                    minOverflowPx = overflow
                    smallestOverflowResult = candidate
                }
                low = mid + 1
            }
        }

        if (bestFit != null) return bestFit

        // With no fitting candidate, binary search visits the last index (minSp),
        // so at least one overflow result must have been recorded.
        return checkNotNull(smallestOverflowResult) {
            "Binary search produced no layout candidate"
        }
    }

    private fun buildStaticLayout(
        text: CharSequence,
        paint: TextPaint,
        widthPx: Int,
        lineSpacingMultiplier: Float,
        hyphenation: Boolean = false
    ): StaticLayout {
        val safeWidth = max(8, widthPx)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, lineSpacingMultiplier)
                // Keep font extents that can otherwise clip accented or script-specific glyphs.
                .setIncludePad(true)
                .setBreakStrategy(StaticLayout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(
                    if (hyphenation) {
                        StaticLayout.HYPHENATION_FREQUENCY_NORMAL
                    } else {
                        StaticLayout.HYPHENATION_FREQUENCY_NONE
                    }
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Fallback fonts can have taller/deeper glyph metrics than the primary font.
                // Include those metrics in line spacing to avoid adjacent lines colliding.
                builder.setUseLineSpacingFromFallbacks(true)
            }
            builder.build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                text,
                paint,
                safeWidth,
                Layout.Alignment.ALIGN_CENTER,
                lineSpacingMultiplier,
                0f,
                true
            )
        }
    }
}
