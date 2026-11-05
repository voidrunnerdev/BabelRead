package com.example.util

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import java.util.Locale

object BubbleTextLayoutEngine {

    private const val LETTER_SPACING = -0.01f
    private const val SAFE_INSET_RATIO = 0.01f
    private const val MIN_SAFE_INSET_PX = 0.5f
    private const val MAX_SAFE_INSET_PX = 4f

    fun envelopeForBubble(
        bubbleBounds: RectF,
        horizontalPaddingDp: Float,
        verticalPaddingDp: Float,
        density: Float
    ): BubbleLineEnvelope? {
        if (!density.isFinite() || density <= 0f) return null
        if (bubbleBounds.width() <= 0f || bubbleBounds.height() <= 0f) return null
        if (!horizontalPaddingDp.isFinite() || !verticalPaddingDp.isFinite()) return null

        val bubbleWidthPx = bubbleBounds.width() * density
        val bubbleHeightPx = bubbleBounds.height() * density
        if (!bubbleWidthPx.isFinite() || !bubbleHeightPx.isFinite() ||
            bubbleWidthPx <= 1f || bubbleHeightPx <= 1f
        ) {
            return null
        }

        val inset = (minOf(bubbleWidthPx, bubbleHeightPx) * SAFE_INSET_RATIO)
            .coerceIn(MIN_SAFE_INSET_PX, MAX_SAFE_INSET_PX)
        return BubbleTextEnvelopeGeometry.forBubble(
            bubbleWidthPx = bubbleWidthPx,
            bubbleHeightPx = bubbleHeightPx,
            horizontalPaddingPx = horizontalPaddingDp * density,
            verticalPaddingPx = verticalPaddingDp * density,
            safeInsetPx = inset
        )
    }

    fun optimizeLayout(
        text: String,
        bubbleBounds: RectF,
        horizontalPaddingDp: Float,
        verticalPaddingDp: Float,
        minSp: Float,
        maxSp: Float,
        density: Float,
        spToPx: (Float) -> Float,
        layoutLocale: Locale,
        targetLanguage: String?,
        measureTypeface: Typeface?
    ): StaticTextLayoutOptimizer.OptimizedLayoutResult? {
        if (!BubbleTextScriptPolicy.isSafe(targetLanguage, text)) return null
        val envelope = envelopeForBubble(
            bubbleBounds = bubbleBounds,
            horizontalPaddingDp = horizontalPaddingDp,
            verticalPaddingDp = verticalPaddingDp,
            density = density
        ) ?: return null

        val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = measureTypeface ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textLocale = layoutLocale
            letterSpacing = LETTER_SPACING
        }
        val measurementPaint = TextPaint(basePaint)
        val measurer = BubbleTextMeasurer(
            measureWidth = { line, textSizePx ->
                measurementPaint.textSize = textSizePx
                measurementPaint.measureText(line)
            },
            metricsForSize = { textSizePx ->
                measurementPaint.textSize = textSizePx
                val fontMetrics = measurementPaint.fontMetrics
                TextLineMetrics(fontMetrics.ascent, fontMetrics.descent)
            }
        )

        val fit = BubbleTextLineFitter.fit(
            text = text,
            envelope = envelope,
            locale = layoutLocale,
            minSp = minSp,
            maxSp = maxSp,
            spToPx = spToPx,
            measurer = measurer,
            density = density
        )
        val layout = fit.layout ?: return null
        if (!fit.fitsInside || layout.lines.isEmpty()) return null

        val linePaint = TextPaint(basePaint).apply {
            textSize = fit.fontSizePx
            textLocale = layoutLocale
            letterSpacing = LETTER_SPACING
        }
        return StaticTextLayoutOptimizer.OptimizedLayoutResult(
            fontSizeSp = fit.fontSizeSp,
            lineSpacingMultiplier = fit.lineSpacingMultiplier,
            letterSpacing = LETTER_SPACING,
            lineCount = fit.lineCount,
            totalHeightPx = fit.totalHeightPx,
            maxLineWidthPx = fit.maxLineWidthPx,
            fitsInside = true,
            requiredWidthDp = fit.requiredWidthDp,
            staticLayout = null,
            bubbleTextLayout = layout,
            bubbleTextPaint = linePaint
        )
    }
}
