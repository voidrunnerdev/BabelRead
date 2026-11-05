package com.example.util

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

data class TextLineMetrics(
    val ascentPx: Float,
    val descentPx: Float
) {
    val heightPx: Float get() = (descentPx - ascentPx).coerceAtLeast(0f)
}

class BubbleTextMeasurer(
    val measureWidth: (String, Float) -> Float,
    val metricsForSize: (Float) -> TextLineMetrics
)

data class BubbleTextLine(
    val text: String,
    val leftPx: Float,
    val topPx: Float,
    val baselinePx: Float,
    val widthPx: Float,
    val heightPx: Float
)

data class BubbleTextLayout(
    val widthPx: Float,
    val heightPx: Float,
    val lines: List<BubbleTextLine>
)

enum class BubbleTextRejection {
    EMPTY_TEXT,
    UNUSABLE_ENVELOPE,
    UNSUPPORTED_SHAPE,
    NO_BREAK_OPPORTUNITY,
    TOO_MANY_LINES,
    UNSTABLE_LINE_COUNT
}

data class BubbleTextFitResult(
    val fontSizeSp: Float,
    val fontSizePx: Float,
    val lineSpacingMultiplier: Float,
    val lineCount: Int,
    val totalHeightPx: Float,
    val maxLineWidthPx: Float,
    val requiredWidthDp: Float,
    val fitsInside: Boolean,
    val layout: BubbleTextLayout?,
    val rejection: BubbleTextRejection? = null
)

object BubbleTextLineFitter {

    const val FONT_SEARCH_STEP_SP = 0.3f
    private const val BASE_LINE_SPACING = 1.06f
    private const val WIDTH_SAFETY = 0.985f
    private const val FIT_EPSILON = 0.5f
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun fit(
        text: String,
        envelope: BubbleLineEnvelope,
        locale: Locale,
        minSp: Float,
        maxSp: Float,
        spToPx: (Float) -> Float,
        measurer: BubbleTextMeasurer,
        density: Float,
        allowBrokenWords: Boolean = false,
        lineSpacingMultiplier: Float = BASE_LINE_SPACING
    ): BubbleTextFitResult {
        require(minSp.isFinite() && maxSp.isFinite() && minSp > 0f && maxSp >= minSp) {
            "Font size range must be finite, positive, and ordered"
        }
        require(density.isFinite() && density > 0f) {
            "Density must be finite and greater than zero"
        }
        require(lineSpacingMultiplier.isFinite() && lineSpacingMultiplier > 0f) {
            "Line spacing must be finite and greater than zero"
        }

        val paragraphs = normalizeParagraphs(text)
        if (paragraphs.isEmpty()) {
            return rejected(maxSp, lineSpacingMultiplier, BubbleTextRejection.EMPTY_TEXT)
        }
        if (envelope.shape.kind != EnvelopeShapeKind.ELLIPSE) {
            return rejected(maxSp, lineSpacingMultiplier, BubbleTextRejection.UNSUPPORTED_SHAPE)
        }
        if (!envelope.isUsable) {
            return rejected(maxSp, lineSpacingMultiplier, BubbleTextRejection.UNUSABLE_ENVELOPE)
        }

        var bestFit: BubbleTextFitResult? = null
        var lastRejection = BubbleTextRejection.UNSTABLE_LINE_COUNT
        val lastIndex = ceil((maxSp - minSp) / FONT_SEARCH_STEP_SP).toInt().coerceAtLeast(0)

        var low = 0
        var high = lastIndex
        while (low <= high) {
            val mid = low + (high - low) / 2
            val candidateSp = max(minSp, maxSp - mid * FONT_SEARCH_STEP_SP)
            val attempt = layoutAtSize(
                candidateSp = candidateSp,
                paragraphs = paragraphs,
                envelope = envelope,
                locale = locale,
                spToPx = spToPx,
                measurer = measurer,
                density = density,
                allowBrokenWords = allowBrokenWords,
                lineSpacingMultiplier = lineSpacingMultiplier
            )
            if (attempt.result != null) {
                bestFit = attempt.result
                high = mid - 1
            } else {
                lastRejection = attempt.rejection ?: lastRejection
                low = mid + 1
            }
        }

        return bestFit ?: rejected(maxSp, lineSpacingMultiplier, lastRejection)
    }

    private fun layoutAtSize(
        candidateSp: Float,
        paragraphs: List<String>,
        envelope: BubbleLineEnvelope,
        locale: Locale,
        spToPx: (Float) -> Float,
        measurer: BubbleTextMeasurer,
        density: Float,
        allowBrokenWords: Boolean,
        lineSpacingMultiplier: Float
    ): Attempt {
        val textSizePx = spToPx(candidateSp)
        if (!textSizePx.isFinite() || textSizePx <= 0f) {
            return Attempt(rejection = BubbleTextRejection.UNUSABLE_ENVELOPE)
        }

        val metrics = measurer.metricsForSize(textSizePx)
        val glyphHeight = metrics.heightPx
        if (!metrics.ascentPx.isFinite() || !metrics.descentPx.isFinite() ||
            !glyphHeight.isFinite() || glyphHeight <= 0f
        ) {
            return Attempt(rejection = BubbleTextRejection.UNUSABLE_ENVELOPE)
        }

        val lineHeight = glyphHeight * lineSpacingMultiplier
        if (!lineHeight.isFinite() || lineHeight <= 0f || lineHeight > envelope.contentHeight) {
            return Attempt(rejection = BubbleTextRejection.TOO_MANY_LINES)
        }

        val maxLines = floor(envelope.contentHeight / lineHeight + 0.0001f).toInt()
        if (maxLines < 1) {
            return Attempt(rejection = BubbleTextRejection.TOO_MANY_LINES)
        }

        val centerBandTop = envelope.centerY - lineHeight / 2f
        val centerBandBottom = envelope.centerY + lineHeight / 2f
        val centerWidth = envelope.widthAtBand(centerBandTop, centerBandBottom) * WIDTH_SAFETY
        if (!centerWidth.isFinite() || centerWidth <= FIT_EPSILON) {
            return Attempt(rejection = BubbleTextRejection.UNUSABLE_ENVELOPE)
        }

        val initialWidths = List(maxLines) { centerWidth }
        val initialBreak = breakLines(
            paragraphs = paragraphs,
            widthsByLine = initialWidths,
            textSizePx = textSizePx,
            measurer = measurer,
            locale = locale,
            allowBrokenWords = allowBrokenWords
        )
        val initialLines = when (initialBreak) {
            is BreakResult.Success -> initialBreak.lines
            BreakResult.NeedsMoreLines -> {
                return Attempt(rejection = BubbleTextRejection.TOO_MANY_LINES)
            }
            BreakResult.Blocked -> {
                return Attempt(rejection = BubbleTextRejection.NO_BREAK_OPPORTUNITY)
            }
        }
        if (initialLines.isEmpty()) {
            return Attempt(rejection = BubbleTextRejection.NO_BREAK_OPPORTUNITY)
        }

        var lineCount = initialLines.size.coerceAtLeast(1)
        val seenCounts = mutableSetOf<Int>()
        while (lineCount <= maxLines) {
            if (!seenCounts.add(lineCount)) {
                return Attempt(rejection = BubbleTextRejection.UNSTABLE_LINE_COUNT)
            }

            val totalHeight = lineCount * lineHeight
            if (totalHeight > envelope.contentHeight + FIT_EPSILON) {
                return Attempt(rejection = BubbleTextRejection.TOO_MANY_LINES)
            }
            val blockTop = blockTop(envelope, totalHeight)
            val widths = List(lineCount) { index ->
                envelope.widthAtBand(
                    blockTop + index * lineHeight,
                    blockTop + (index + 1) * lineHeight
                ) * WIDTH_SAFETY
            }
            if (widths.any { !it.isFinite() || it <= FIT_EPSILON }) {
                return Attempt(rejection = BubbleTextRejection.UNUSABLE_ENVELOPE)
            }

            val broken = breakLines(
                paragraphs = paragraphs,
                widthsByLine = widths,
                textSizePx = textSizePx,
                measurer = measurer,
                locale = locale,
                allowBrokenWords = allowBrokenWords
            )
            when (broken) {
                BreakResult.NeedsMoreLines -> {
                    lineCount++
                }
                BreakResult.Blocked -> {
                    return Attempt(rejection = BubbleTextRejection.NO_BREAK_OPPORTUNITY)
                }
                is BreakResult.Success -> {
                    if (broken.lines.isEmpty()) {
                        return Attempt(rejection = BubbleTextRejection.NO_BREAK_OPPORTUNITY)
                    }
                    if (broken.lines.size != lineCount) {
                        lineCount = broken.lines.size.coerceAtLeast(1)
                        continue
                    }
                    return Attempt(
                        result = buildFit(
                            candidateSp = candidateSp,
                            textSizePx = textSizePx,
                            lineSpacingMultiplier = lineSpacingMultiplier,
                            lineHeight = lineHeight,
                            blockTop = blockTop,
                            metrics = metrics,
                            lines = broken.lines,
                            widths = widths,
                            envelope = envelope,
                            density = density
                        ),
                        rejection = null
                    )
                }
            }
        }

        return Attempt(rejection = BubbleTextRejection.TOO_MANY_LINES)
    }

    private fun buildFit(
        candidateSp: Float,
        textSizePx: Float,
        lineSpacingMultiplier: Float,
        lineHeight: Float,
        blockTop: Float,
        metrics: TextLineMetrics,
        lines: List<DraftLine>,
        widths: List<Float>,
        envelope: BubbleLineEnvelope,
        density: Float
    ): BubbleTextFitResult {
        val textHeight = metrics.heightPx
        val fittedLines = ArrayList<BubbleTextLine>(lines.size)
        for (index in lines.indices) {
            val line = lines[index]
            if (line.widthPx > widths[index] + FIT_EPSILON) {
                return rejected(
                    candidateSp,
                    lineSpacingMultiplier,
                    BubbleTextRejection.NO_BREAK_OPPORTUNITY
                )
            }
            val bandTop = blockTop + index * lineHeight
            val glyphTop = bandTop + (lineHeight - textHeight) / 2f
            fittedLines += BubbleTextLine(
                text = line.text,
                leftPx = envelope.centerX - line.widthPx / 2f,
                topPx = glyphTop,
                baselinePx = glyphTop - metrics.ascentPx,
                widthPx = line.widthPx,
                heightPx = textHeight
            )
        }
        val maxLineWidth = fittedLines.maxOfOrNull { it.widthPx } ?: 0f
        val totalHeight = lines.size * lineHeight
        return BubbleTextFitResult(
            fontSizeSp = candidateSp,
            fontSizePx = textSizePx,
            lineSpacingMultiplier = lineSpacingMultiplier,
            lineCount = fittedLines.size,
            totalHeightPx = totalHeight,
            maxLineWidthPx = maxLineWidth,
            requiredWidthDp = maxLineWidth / density,
            fitsInside = true,
            layout = BubbleTextLayout(
                widthPx = envelope.bounds.width,
                heightPx = envelope.bounds.height,
                lines = fittedLines
            )
        )
    }

    private fun breakLines(
        paragraphs: List<String>,
        widthsByLine: List<Float>,
        textSizePx: Float,
        measurer: BubbleTextMeasurer,
        locale: Locale,
        allowBrokenWords: Boolean
    ): BreakResult {
        val lines = mutableListOf<DraftLine>()
        var lineIndex = 0
        for (paragraph in paragraphs) {
            val iterator = BreakIterator.getLineInstance(locale)
            iterator.setText(paragraph)
            var start = skipWhitespace(paragraph, 0)
            while (start < paragraph.length) {
                if (lineIndex >= widthsByLine.size) {
                    return BreakResult.NeedsMoreLines
                }
                val widthLimit = widthsByLine[lineIndex]
                if (!widthLimit.isFinite() || widthLimit <= FIT_EPSILON) {
                    return BreakResult.Blocked
                }
                val end = findLineEnd(
                    paragraph = paragraph,
                    start = start,
                    widthLimit = widthLimit,
                    textSizePx = textSizePx,
                    measurer = measurer,
                    iterator = iterator,
                    allowBrokenWords = allowBrokenWords
                )
                if (end <= start) {
                    return BreakResult.Blocked
                }
                val lineText = paragraph.substring(start, end).trim()
                if (lineText.isEmpty()) {
                    start = skipWhitespace(paragraph, end)
                    continue
                }
                val measuredWidth = measurer.measureWidth(lineText, textSizePx)
                if (!measuredWidth.isFinite() || measuredWidth > widthLimit + FIT_EPSILON) {
                    return BreakResult.Blocked
                }
                lines += DraftLine(lineText, measuredWidth)
                lineIndex++
                start = skipWhitespace(paragraph, end)
            }
        }
        return BreakResult.Success(lines)
    }

    private fun findLineEnd(
        paragraph: String,
        start: Int,
        widthLimit: Float,
        textSizePx: Float,
        measurer: BubbleTextMeasurer,
        iterator: BreakIterator,
        allowBrokenWords: Boolean
    ): Int {
        var boundary = iterator.following(start)
        var bestEnd = -1
        while (boundary != BreakIterator.DONE) {
            val candidate = paragraph.substring(start, boundary).trimEnd()
            if (candidate.isNotEmpty()) {
                val width = measurer.measureWidth(candidate, textSizePx)
                if (!width.isFinite()) return -1
                if (width <= widthLimit + FIT_EPSILON) {
                    bestEnd = boundary
                } else {
                    break
                }
            }
            boundary = iterator.following(boundary)
        }
        if (bestEnd > start) return bestEnd
        if (!allowBrokenWords) return -1
        return findBrokenEnd(paragraph, start, widthLimit, textSizePx, measurer)
    }

    private fun findBrokenEnd(
        paragraph: String,
        start: Int,
        widthLimit: Float,
        textSizePx: Float,
        measurer: BubbleTextMeasurer
    ): Int {
        val codePointCount = paragraph.codePointCount(start, paragraph.length)
        if (codePointCount <= 1) return -1

        var low = 1
        var high = codePointCount
        var bestEnd = -1
        while (low <= high) {
            val mid = low + (high - low) / 2
            val end = paragraph.offsetByCodePoints(start, mid)
            val candidate = paragraph.substring(start, end).trimEnd()
            val width = if (candidate.isEmpty()) {
                Float.POSITIVE_INFINITY
            } else {
                measurer.measureWidth(candidate, textSizePx)
            }
            if (width.isFinite() && width <= widthLimit + FIT_EPSILON) {
                bestEnd = end
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return bestEnd
    }

    private fun blockTop(envelope: BubbleLineEnvelope, totalHeight: Float): Float {
        val top = envelope.centerY - totalHeight / 2f
        val maxTop = max(envelope.contentTop, envelope.contentBottom - totalHeight)
        return top.coerceIn(envelope.contentTop, maxTop)
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var index = from.coerceAtLeast(0)
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun normalizeParagraphs(text: String): List<String> =
        text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { paragraph -> WHITESPACE_REGEX.replace(paragraph, " ").trim() }
            .filter { it.isNotEmpty() }

    private fun rejected(
        fontSizeSp: Float,
        lineSpacingMultiplier: Float,
        rejection: BubbleTextRejection
    ): BubbleTextFitResult = BubbleTextFitResult(
        fontSizeSp = fontSizeSp,
        fontSizePx = 0f,
        lineSpacingMultiplier = lineSpacingMultiplier,
        lineCount = 0,
        totalHeightPx = 0f,
        maxLineWidthPx = 0f,
        requiredWidthDp = 0f,
        fitsInside = false,
        layout = null,
        rejection = rejection
    )

    private data class DraftLine(
        val text: String,
        val widthPx: Float
    )

    private data class Attempt(
        val result: BubbleTextFitResult? = null,
        val rejection: BubbleTextRejection? = null
    )

    private sealed class BreakResult {
        data class Success(val lines: List<DraftLine>) : BreakResult()
        object NeedsMoreLines : BreakResult()
        object Blocked : BreakResult()
    }
}
