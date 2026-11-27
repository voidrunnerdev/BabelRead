package com.example.util

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics
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
        val staticLayout: StaticLayout?,
        val bubbleTextLayout: BubbleTextLayout? = null,
        val bubbleTextPaint: TextPaint? = null
    ) {
        val hasRenderableLayout: Boolean
            get() = staticLayout != null || bubbleTextLayout?.lines?.isNotEmpty() == true

        val layoutWidthPx: Float
            get() = bubbleTextLayout?.widthPx ?: staticLayout?.width?.toFloat() ?: 0f

        val layoutHeightPx: Float
            get() = bubbleTextLayout?.heightPx ?: staticLayout?.height?.toFloat() ?: 0f
    }

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
        minSp: Float = 8.0f,
        maxSp: Float = 14.0f,
        density: Float,
        spToPx: (Float) -> Float,
        layoutLocale: Locale = Locale.getDefault(),
        isOvalBubble: Boolean = true,
        measureTypeface: Typeface? = null,
        allowBrokenWords: Boolean = false,
        textDirection: TextLayoutLocale.Direction = TextLayoutLocale.Direction.LTR,
        textScaleX: Float = 1.0f
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
        val finiteBounds = bounds.left.isFinite() && bounds.top.isFinite() &&
            bounds.right.isFinite() && bounds.bottom.isFinite() && density.isFinite() && density > 0f
        if (!finiteBounds || cleanText.isEmpty()) {
            return OptimizedLayoutResult(
                fontSizeSp = maxSp,
                lineSpacingMultiplier = BASE_LINE_SPACING,
                letterSpacing = if (textDirection == TextLayoutLocale.Direction.RTL) 0f else BASE_LETTER_SPACING,
                lineCount = 0,
                totalHeightPx = 0f,
                maxLineWidthPx = 0f,
                fitsInside = cleanText.isEmpty(),
                requiredWidthDp = 0f,
                staticLayout = null
            )
        }
        val widthDp = bounds.width().coerceAtLeast(1f)
        val heightDp = bounds.height().coerceAtLeast(1f)
        val targetWidthPx = widthDp * density
        val targetHeightPx = heightDp * density

        val safeScaleX = if (textScaleX.isFinite()) textScaleX.coerceIn(0.75f, 1.0f) else 1.0f
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = measureTypeface ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = BASE_LETTER_SPACING
            textLocale = layoutLocale
            this.textScaleX = safeScaleX
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

        fun measureCandidate(candidateSp: Float): Pair<OptimizedLayoutResult, Float> {
            val textSizePx = spToPx(candidateSp)
            require(textSizePx.isFinite() && textSizePx > 0f) {
                "spToPx must return a finite value greater than zero"
            }
            // The near-minimum adjustment only tightens spacing, so smaller candidates
            // remain no harder to fit when the search crosses this threshold.
            val isNearMinimum = candidateSp <= minSp + TIGHT_SP_THRESHOLD
            val candidateLineSpacing = if (isNearMinimum) TIGHT_LINE_SPACING else BASE_LINE_SPACING
            val candidateLetterSpacing = if (textDirection == TextLayoutLocale.Direction.RTL) {
                0f
            } else if (isNearMinimum) {
                TIGHT_LETTER_SPACING
            } else {
                BASE_LETTER_SPACING
            }

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
                textDirection,
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

            val edgeWidthRatio = if (isOvalBubble && layout.lineCount > 1) {
                val verticalFill = (totalH / targetHeightPx).coerceIn(0f, 1f)
                val edgeY = (verticalFill * 0.65f).coerceIn(0f, 0.82f)
                kotlin.math.sqrt((1f - edgeY * edgeY).coerceAtLeast(0.01f)).coerceIn(0.84f, 0.96f)
            } else {
                1.0f
            }

            // StaticLayout can emergency-break an unbreakable token to keep the line
            // inside the box. Validate the actual line-end offsets against the same
            // locale-aware boundaries instead of estimating token widths separately.
            // With allowBrokenWords, a mid-word break (hyphenated or emergency) is
            // tolerated only when the broken piece still fills most of the line; that
            // rejects ugly single-glyph fragments while letting long words split.
            val isTurkishLocale = layoutLocale.language.equals("tr", ignoreCase = true)
            var hasIllegalLineBreak = false
            for (line in 0 until (layout.lineCount - 1).coerceAtLeast(0)) {
                val lineEnd = layout.getLineEnd(line)
                val endsWithSoftHyphen = lineEnd > 0 && cleanText.getOrNull(lineEnd - 1) == TurkishSyllabifier.SOFT_HYPHEN
                if (lineEnd < cleanText.length && !endsWithSoftHyphen && !lineBreakIterator.isBoundary(lineEnd)) {
                    // Reject unhyphenated mid-word character chops (e.g. "SUNU / Z?" or "Ta / nrım!")
                    // whenever the broken word itself has Turkish soft hyphens, or when fewer than 2
                    // letters remain on either side of the emergency break.
                    var wordHasSoftHyphen = false
                    var lettersBefore = 0
                    var bi = lineEnd - 1
                    while (bi >= 0 && !cleanText[bi].isWhitespace()) {
                        if (cleanText[bi] == TurkishSyllabifier.SOFT_HYPHEN) wordHasSoftHyphen = true
                        else if (cleanText[bi].isLetter()) lettersBefore++
                        bi--
                    }
                    var lettersAfter = 0
                    var ai = lineEnd
                    while (ai < cleanText.length && !cleanText[ai].isWhitespace()) {
                        if (cleanText[ai] == TurkishSyllabifier.SOFT_HYPHEN) wordHasSoftHyphen = true
                        else if (cleanText[ai].isLetter()) lettersAfter++
                        ai++
                    }
                    val totalWordLetters = lettersBefore + lettersAfter
                    // Tiresiz kelime ortası harf doğramalarını (ör. "HAY / IR", "GEN / Ç", "OLM / ALI")
                    // kesin olarak reddet; yalnızca soft-hyphen taşımayan çok uzun (>=10 harf) kelimelerde
                    // son çare olarak >=3 harf kuralıyla izin ver.
                    if (!allowBrokenWords ||
                        (isTurkishLocale && (wordHasSoftHyphen || totalWordLetters < 10)) ||
                        totalWordLetters < 8 ||
                        lettersBefore < 3 ||
                        lettersAfter < 3 ||
                        layout.getLineWidth(line) < targetWidthPx * 0.72f
                    ) {
                        hasIllegalLineBreak = true
                        break
                    }
                }
            }

            val ovalRequiredWidthPx = if (isOvalBubble && layout.lineCount > 1) {
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
        textDirection: TextLayoutLocale.Direction,
        hyphenation: Boolean = false
    ): StaticLayout {
        val safeWidth = max(8, widthPx)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(textDirection.toHeuristic())
                .setLineSpacing(0f, lineSpacingMultiplier)
                // Keep font extents that can otherwise clip accented or script-specific glyphs.
                .setIncludePad(true)
                .setBreakStrategy(StaticLayout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(
                    if (hyphenation) {
                        StaticLayout.HYPHENATION_FREQUENCY_FULL
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

    private fun TextLayoutLocale.Direction.toHeuristic(): TextDirectionHeuristic =
        if (this == TextLayoutLocale.Direction.RTL) {
            TextDirectionHeuristics.RTL
        } else {
            TextDirectionHeuristics.FIRSTSTRONG_LTR
        }

}
