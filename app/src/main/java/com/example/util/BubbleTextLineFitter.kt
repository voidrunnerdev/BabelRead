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
    val metricsForSize: (Float) -> TextLineMetrics,
    /** Optional (topOffsetFromBaselinePx, bottomOffsetFromBaselinePx) via Paint.getTextBounds for optical centering. */
    val visualBoundsForLine: ((String, Float) -> Pair<Float, Float>)? = null
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
    val lines: List<BubbleTextLine>,
    val contourProfile: ContourScanlineProfile? = null
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
    private const val MAX_DP_TOKENS = 140
    private const val HYPHEN_PENALTY = 150.0f
    private const val ORPHAN_PENALTY = 1.50f
    private const val CONJUNCTION_PENALTY = 250.0f
    private const val HOURGLASS_PENALTY = 0.85f
    private const val TAPER_PENALTY = 0.45f
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val TURKISH_TRAILING_CONJUNCTIONS = setOf(
        "ve", "de", "da", "ki", "ya", "ile", "bu", "şu", "o"
    )

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
        if (!envelope.isSupportedShape) {
            return rejected(maxSp, lineSpacingMultiplier, BubbleTextRejection.UNSUPPORTED_SHAPE)
        }
        if (!envelope.isUsable) {
            return rejected(maxSp, lineSpacingMultiplier, BubbleTextRejection.UNUSABLE_ENVELOPE)
        }

        var bestFit: BubbleTextFitResult? = null
        var lastRejection = BubbleTextRejection.UNSTABLE_LINE_COUNT

        // BallonsTranslator np.geomspace prensibi: 8 kaba geometrik aralık dene.
        // Sığan ilk kaba boyuttan sonra, 4-5 adımlı binary refinement ile en büyük fontu 0.2sp hassasiyetle kilitle.
        val probeCount = if (maxSp <= minSp + 0.1f) 1 else 8
        val ratio = (minSp / maxSp).toDouble()
        val probes = if (probeCount == 1) {
            floatArrayOf(maxSp)
        } else {
            FloatArray(probeCount) { i ->
                val fraction = i.toDouble() / (probeCount - 1).toDouble()
                (maxSp * Math.pow(ratio, fraction)).toFloat().coerceIn(minSp, maxSp)
            }
        }

        var previousSp = maxSp
        for (candidateSp in probes) {
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
            if (attempt.result?.fitsInside == true) {
                bestFit = attempt.result
                var low = candidateSp
                var high = previousSp
                var refinementSteps = 0
                while (refinementSteps < 5 && (high - low) > 0.2f) {
                    refinementSteps++
                    val mid = (low + high) / 2f
                    val refAttempt = layoutAtSize(
                        candidateSp = mid,
                        paragraphs = paragraphs,
                        envelope = envelope,
                        locale = locale,
                        spToPx = spToPx,
                        measurer = measurer,
                        density = density,
                        allowBrokenWords = allowBrokenWords,
                        lineSpacingMultiplier = lineSpacingMultiplier
                    )
                    if (refAttempt.result?.fitsInside == true) {
                        bestFit = refAttempt.result
                        low = mid
                    } else {
                        lastRejection = refAttempt.rejection ?: lastRejection
                        high = mid
                    }
                }
                break
            } else {
                lastRejection = attempt.rejection ?: lastRejection
            }
            previousSp = candidateSp
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

        val referenceWidth = widestAvailableBandWidth(envelope, lineHeight) * WIDTH_SAFETY
        if (!referenceWidth.isFinite() || referenceWidth <= FIT_EPSILON) {
            return Attempt(rejection = BubbleTextRejection.UNUSABLE_ENVELOPE)
        }

        val initialWidths = List(maxLines) { referenceWidth }
        val initialBreak = breakLinesGreedy(
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

        val minLines = initialLines.size.coerceIn(1, maxLines)
        for (lineCount in minLines..maxLines) {
            val totalHeight = lineCount * lineHeight
            if (totalHeight > envelope.contentHeight + FIT_EPSILON) {
                break
            }
            val blockTop = blockTop(envelope, totalHeight)
            val spans = List(lineCount) { index ->
                envelope.spanAtBand(
                    blockTop + index * lineHeight,
                    blockTop + (index + 1) * lineHeight
                )
            }
            val widths = spans.map { it.widthPx * WIDTH_SAFETY }
            if (widths.any { !it.isFinite() || it <= FIT_EPSILON }) {
                continue
            }

            val broken = breakLinesBalanced(
                paragraphs = paragraphs,
                widthsByLine = widths,
                textSizePx = textSizePx,
                measurer = measurer,
                locale = locale,
                allowBrokenWords = allowBrokenWords
            )
            when (broken) {
                BreakResult.NeedsMoreLines -> {
                    continue
                }
                BreakResult.Blocked -> {
                    return Attempt(rejection = BubbleTextRejection.NO_BREAK_OPPORTUNITY)
                }
                is BreakResult.Success -> {
                    if (broken.lines.isEmpty()) {
                        continue
                    }
                    val built = buildFit(
                        candidateSp = candidateSp,
                        textSizePx = textSizePx,
                        lineSpacingMultiplier = lineSpacingMultiplier,
                        lineHeight = lineHeight,
                        blockTop = blockTop,
                        metrics = metrics,
                        measurer = measurer,
                        lines = broken.lines,
                        spans = spans,
                        widths = widths,
                        envelope = envelope,
                        density = density
                    )
                    if (built.fitsInside) {
                        return Attempt(result = built, rejection = null)
                    }
                }
            }
        }

        return Attempt(rejection = BubbleTextRejection.TOO_MANY_LINES)
    }

    private fun widestAvailableBandWidth(envelope: BubbleLineEnvelope, lineHeight: Float): Float {
        val centerTop = envelope.centerY - lineHeight / 2f
        var widest = envelope.widthAtBand(centerTop, centerTop + lineHeight)
        if (envelope.shape.kind == EnvelopeShapeKind.CONTOUR_SCANLINE) {
            val usableSpan = (envelope.contentHeight - lineHeight).coerceAtLeast(0f)
            if (usableSpan > 0f) {
                for (step in 0..4) {
                    val top = envelope.contentTop + usableSpan * (step / 4f)
                    val w = envelope.widthAtBand(top, top + lineHeight)
                    if (w.isFinite() && w > widest) widest = w
                }
            }
        }
        return widest
    }

    private fun buildFit(
        candidateSp: Float,
        textSizePx: Float,
        lineSpacingMultiplier: Float,
        lineHeight: Float,
        blockTop: Float,
        metrics: TextLineMetrics,
        measurer: BubbleTextMeasurer,
        lines: List<DraftLine>,
        spans: List<ScanlineSpan>,
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
            val span = spans[index]
            val bandTop = blockTop + index * lineHeight
            val glyphTop = bandTop + (lineHeight - textHeight) / 2f
            val centeredLeft = span.centerX - line.widthPx / 2f
            val maxLeft = (span.rightPx - line.widthPx).coerceAtLeast(span.leftPx)
            val safeLeft = centeredLeft.coerceIn(span.leftPx, maxLeft)
            fittedLines += BubbleTextLine(
                text = line.text,
                leftPx = safeLeft,
                topPx = glyphTop,
                baselinePx = glyphTop - metrics.ascentPx,
                widthPx = line.widthPx,
                heightPx = textHeight
            )
        }
        // Optical vertical centering: shift lines subtly so the real ink mass
        // (cap height, Turkish diacritics, descenders) centers inside the chamber
        // without ever crossing contentTop/contentBottom.
        val boundsFn = measurer.visualBoundsForLine
        val finalLines = if (boundsFn != null && fittedLines.isNotEmpty()) {
            val firstLine = fittedLines.first()
            val lastLine = fittedLines.last()
            val (firstTopOff, _) = boundsFn(firstLine.text, textSizePx)
            val (_, lastBotOff) = boundsFn(lastLine.text, textSizePx)
            val visualTop = firstLine.baselinePx + firstTopOff
            val visualBottom = lastLine.baselinePx + lastBotOff
            if (firstTopOff.isFinite() && lastBotOff.isFinite() && visualTop < visualBottom) {
                val visualCenterY = (visualTop + visualBottom) / 2f
                val maxShift = textHeight * 0.22f
                val minAllowed = (envelope.contentTop - visualTop).coerceAtMost(0f)
                val maxAllowed = (envelope.contentBottom - visualBottom).coerceAtLeast(0f)
                val rawShift = (envelope.centerY - visualCenterY).coerceIn(-maxShift, maxShift)
                var candidateShift = rawShift.coerceIn(minAllowed, maxAllowed)
                // Verify shifted lines remain strictly inside curved ellipse/contour spans.
                fun shiftFitsEnvelope(dy: Float): Boolean {
                    for (line in fittedLines) {
                        val shiftedTop = line.topPx + dy
                        val shiftedBottom = shiftedTop + line.heightPx
                        if (shiftedTop < envelope.contentTop - FIT_EPSILON ||
                            shiftedBottom > envelope.contentBottom + FIT_EPSILON
                        ) {
                            return false
                        }
                        val span = envelope.spanAtBand(shiftedTop, shiftedBottom)
                        if (line.leftPx < span.leftPx - FIT_EPSILON ||
                            line.leftPx + line.widthPx > span.rightPx + FIT_EPSILON
                        ) {
                            return false
                        }
                    }
                    return true
                }
                if (kotlin.math.abs(candidateShift) > 0.25f && !shiftFitsEnvelope(candidateShift)) {
                    candidateShift *= 0.5f
                    if (kotlin.math.abs(candidateShift) <= 0.25f || !shiftFitsEnvelope(candidateShift)) {
                        candidateShift = 0f
                    }
                }
                if (kotlin.math.abs(candidateShift) > 0.25f) {
                    fittedLines.map { line ->
                        line.copy(
                            topPx = line.topPx + candidateShift,
                            baselinePx = line.baselinePx + candidateShift
                        )
                    }
                } else {
                    fittedLines
                }
            } else {
                fittedLines
            }
        } else {
            fittedLines
        }
        val maxLineWidth = finalLines.maxOfOrNull { it.widthPx } ?: 0f
        val totalHeight = lines.size * lineHeight
        return BubbleTextFitResult(
            fontSizeSp = candidateSp,
            fontSizePx = textSizePx,
            lineSpacingMultiplier = lineSpacingMultiplier,
            lineCount = finalLines.size,
            totalHeightPx = totalHeight,
            maxLineWidthPx = maxLineWidth,
            requiredWidthDp = maxLineWidth / density,
            fitsInside = true,
            layout = BubbleTextLayout(
                widthPx = envelope.bounds.width,
                heightPx = envelope.bounds.height,
                lines = finalLines,
                contourProfile = if (envelope.shape.kind == EnvelopeShapeKind.CONTOUR_SCANLINE) {
                    envelope.contourProfile
                } else {
                    null
                }
            )
        )
    }

    /**
     * Knuth-Plass style dynamic-programming line breaker matched to per-band
     * bubble widths. Tries clean whole-word DP first; when [allowBrokenWords] is
     * enabled for Turkish, retries with syllable sub-tokens (adding visible '-'
     * with a hyphen penalty); falls back to greedy breaking for very long texts
     * or unbreakable non-Turkish tokens.
     */
    private fun breakLinesBalanced(
        paragraphs: List<String>,
        widthsByLine: List<Float>,
        textSizePx: Float,
        measurer: BubbleTextMeasurer,
        locale: Locale,
        allowBrokenWords: Boolean
    ): BreakResult {
        if (paragraphs.size != 1) {
            return breakLinesGreedy(
                paragraphs, widthsByLine, textSizePx, measurer, locale, allowBrokenWords
            )
        }
        val paragraph = paragraphs[0]
        val isTurkish = locale.language.equals("tr", ignoreCase = true)

        val wordTokens = tokenizeParagraph(paragraph, locale, expandTurkishSyllables = false)
        if (wordTokens.isEmpty()) return BreakResult.Blocked
        if (wordTokens.size <= MAX_DP_TOKENS) {
            val dpWords = dpBreakTokens(wordTokens, widthsByLine, textSizePx, measurer, locale)
            if (dpWords != null) return BreakResult.Success(dpWords)
        }

        if (allowBrokenWords && isTurkish) {
            val syllableTokens = tokenizeParagraph(paragraph, locale, expandTurkishSyllables = true)
            if (syllableTokens.isNotEmpty() && syllableTokens.size <= MAX_DP_TOKENS) {
                val dpSyllables = dpBreakTokens(syllableTokens, widthsByLine, textSizePx, measurer, locale)
                if (dpSyllables != null) return BreakResult.Success(dpSyllables)
            }
        }

        return breakLinesGreedy(
            paragraphs, widthsByLine, textSizePx, measurer, locale, allowBrokenWords
        )
    }

    private data class BreakToken(
        val text: String,
        val spaceAfter: Boolean,
        val isMidWordPrefix: Boolean
    )

    private fun tokenizeParagraph(
        paragraph: String,
        locale: Locale,
        expandTurkishSyllables: Boolean
    ): List<BreakToken> {
        val iterator = BreakIterator.getLineInstance(locale)
        iterator.setText(paragraph)
        val tokens = ArrayList<BreakToken>(16)
        var start = skipWhitespace(paragraph, 0)
        while (start < paragraph.length) {
            val boundary = iterator.following(start)
            val end = if (boundary == BreakIterator.DONE) paragraph.length else boundary
            if (end <= start) break
            var trimmedEnd = end
            while (trimmedEnd > start && paragraph[trimmedEnd - 1].isWhitespace()) trimmedEnd--
            val hasTrailingSpace = trimmedEnd < end
            if (trimmedEnd > start) {
                val raw = paragraph.substring(start, trimmedEnd)
                if (expandTurkishSyllables) {
                    appendTurkishExpandedTokens(raw, hasTrailingSpace, tokens)
                } else {
                    tokens += BreakToken(raw, hasTrailingSpace, isMidWordPrefix = false)
                }
            }
            start = skipWhitespace(paragraph, end)
        }
        return tokens
    }

    private fun appendTurkishExpandedTokens(
        raw: String,
        spaceAfter: Boolean,
        out: MutableList<BreakToken>
    ) {
        var letterStart = 0
        while (letterStart < raw.length && !raw[letterStart].isLetter()) letterStart++
        if (letterStart >= raw.length) {
            out += BreakToken(raw, spaceAfter, isMidWordPrefix = false)
            return
        }
        var letterEnd = letterStart
        while (letterEnd < raw.length && (raw[letterEnd].isLetter() || raw[letterEnd] == '\'' || raw[letterEnd] == '’')) letterEnd++
        while (letterEnd > letterStart && (raw[letterEnd - 1] == '\'' || raw[letterEnd - 1] == '’')) letterEnd--
        val word = raw.substring(letterStart, letterEnd)
        val offsets = TurkishSyllabifier.syllableBreakOffsets(word)
        if (offsets.isEmpty()) {
            out += BreakToken(raw, spaceAfter, isMidWordPrefix = false)
            return
        }
        val prefix = raw.substring(0, letterStart)
        val suffix = raw.substring(letterEnd)
        var prev = 0
        for (i in offsets.indices) {
            val split = offsets[i]
            val piece = (if (i == 0) prefix else "") + word.substring(prev, split)
            out += BreakToken(piece, spaceAfter = false, isMidWordPrefix = true)
            prev = split
        }
        out += BreakToken(word.substring(prev) + suffix, spaceAfter = spaceAfter, isMidWordPrefix = false)
    }

    private fun dpBreakTokens(
        tokens: List<BreakToken>,
        widthsByLine: List<Float>,
        textSizePx: Float,
        measurer: BubbleTextMeasurer,
        locale: Locale
    ): List<DraftLine>? {
        val n = tokens.size
        val maxL = widthsByLine.size
        if (n == 0 || maxL == 0) return null

        val inf = Float.POSITIVE_INFINITY
        val dp = Array(maxL + 1) { FloatArray(n + 1) { inf } }
        val prevIdx = Array(maxL + 1) { IntArray(n + 1) { -1 } }
        val lineTextCache = Array(maxL + 1) { arrayOfNulls<String>(n + 1) }
        val lineWidthCache = Array(maxL + 1) { FloatArray(n + 1) }

        dp[0][0] = 0f
        val sb = StringBuilder(64)

        for (line in 0 until maxL) {
            val widthLimit = widthsByLine[line]
            if (!widthLimit.isFinite() || widthLimit <= FIT_EPSILON) return null
            for (i in 0 until n) {
                val baseCost = dp[line][i]
                if (!baseCost.isFinite()) continue
                sb.setLength(0)
                for (j in i + 1..n) {
                    val tok = tokens[j - 1]
                    if (j - 1 > i && tokens[j - 2].spaceAfter) {
                        sb.append(' ')
                    }
                    sb.append(tok.text)
                    val endsWithHyphen = tok.isMidWordPrefix
                    val candidate = if (endsWithHyphen) {
                        if (tok.text.endsWith('\'') || tok.text.endsWith('’')) {
                            sb.toString()
                        } else {
                            sb.append('-')
                            val s = sb.toString()
                            sb.setLength(sb.length - 1)
                            s
                        }
                    } else {
                        sb.toString()
                    }
                    val width = measurer.measureWidth(candidate, textSizePx)
                    if (!width.isFinite()) break
                    if (width > widthLimit + FIT_EPSILON) {
                        if (endsWithHyphen) continue else break
                    }
                    val slack = ((widthLimit - width) / widthLimit).coerceIn(0f, 1f)
                    val isLastLine = (j == n)
                    var lineCost = slack * slack * (if (isLastLine) 0.65f else 1.0f)
                    if (endsWithHyphen) lineCost += HYPHEN_PENALTY
                    if (isLastLine && line > 0 && j - i == 1) {
                        lineCost += ORPHAN_PENALTY
                    }
                    // BallonsTranslator tarzı dilbilgisel ceza: satır sonunda tek başına kalan 1-2 harfli Türkçe bağlaçlar
                    if (!isLastLine && !endsWithHyphen) {
                        val cleanLastWord = tok.text.trim().trim('.', ',', '!', '?', ';', ':', '…', '"', '“', '”', '’', '\'').lowercase(locale)
                        if (cleanLastWord in TURKISH_TRAILING_CONJUNCTIONS) {
                            lineCost += CONJUNCTION_PENALTY
                        }
                    }
                    // Diamond / oval silhouette shaping: penalize hourglass pinches (middle line
                    // narrower than both neighbors) and bottom-heavy flares on 3+ line bubbles.
                    if (maxL >= 3 && line >= 1) {
                        val prevW = lineWidthCache[line][i]
                        if (line >= 2) {
                            val prevPrevIdx = prevIdx[line][i]
                            if (prevPrevIdx >= 0) {
                                val prevPrevW = lineWidthCache[line - 1][prevPrevIdx]
                                if (prevW < prevPrevW * 0.85f && prevW < width * 0.85f) {
                                    lineCost += HOURGLASS_PENALTY
                                }
                            }
                        } else if (line == 1 && !isLastLine && width < prevW * 0.76f) {
                            lineCost += TAPER_PENALTY
                        }
                        if (isLastLine && widthLimit < widthsByLine[line - 1] * 0.98f && width > prevW * 1.12f) {
                            lineCost += TAPER_PENALTY
                        }
                    }
                    val totalCost = baseCost + lineCost
                    if (totalCost < dp[line + 1][j]) {
                        dp[line + 1][j] = totalCost
                        prevIdx[line + 1][j] = i
                        lineTextCache[line + 1][j] = candidate
                        lineWidthCache[line + 1][j] = width
                    }
                }
            }
        }

        var bestL = -1
        var bestCost = inf
        for (l in 1..maxL) {
            if (dp[l][n] < bestCost) {
                bestCost = dp[l][n]
                bestL = l
            }
        }
        val targetLines = bestL
        if (targetLines <= 0) return null

        val result = ArrayList<DraftLine>(targetLines)
        var curJ = n
        for (l in targetLines downTo 1) {
            val curI = prevIdx[l][curJ]
            if (curI < 0) return null
            val text = lineTextCache[l][curJ] ?: return null
            val width = lineWidthCache[l][curJ]
            result += DraftLine(text, width)
            curJ = curI
        }
        result.reverse()
        return result
    }

    private fun breakLinesGreedy(
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
                val step = findLineBreak(
                    paragraph = paragraph,
                    start = start,
                    widthLimit = widthLimit,
                    textSizePx = textSizePx,
                    measurer = measurer,
                    iterator = iterator,
                    locale = locale,
                    allowBrokenWords = allowBrokenWords
                ) ?: return BreakResult.Blocked
                val lineText = step.text
                if (lineText.isEmpty()) {
                    start = skipWhitespace(paragraph, step.nextStart)
                    continue
                }
                val measuredWidth = step.widthPx
                if (!measuredWidth.isFinite() || measuredWidth > widthLimit + FIT_EPSILON) {
                    return BreakResult.Blocked
                }
                lines += DraftLine(lineText, measuredWidth)
                lineIndex++
                start = skipWhitespace(paragraph, step.nextStart)
            }
        }
        return BreakResult.Success(lines)
    }

    private data class LineBreakStep(
        val nextStart: Int,
        val text: String,
        val widthPx: Float
    )

    private fun findLineBreak(
        paragraph: String,
        start: Int,
        widthLimit: Float,
        textSizePx: Float,
        measurer: BubbleTextMeasurer,
        iterator: BreakIterator,
        locale: Locale,
        allowBrokenWords: Boolean
    ): LineBreakStep? {
        var boundary = iterator.following(start)
        var bestEnd = -1
        var bestText = ""
        var bestWidth = 0f
        while (boundary != BreakIterator.DONE) {
            val candidate = paragraph.substring(start, boundary).trimEnd()
            if (candidate.isNotEmpty()) {
                val width = measurer.measureWidth(candidate, textSizePx)
                if (!width.isFinite()) return null
                if (width <= widthLimit + FIT_EPSILON) {
                    bestEnd = boundary
                    bestText = candidate
                    bestWidth = width
                } else {
                    break
                }
            }
            boundary = iterator.following(boundary)
        }
        val isTurkishLocale = locale.language.equals("tr", ignoreCase = true)
        if (bestEnd > start) {
            if (allowBrokenWords && isTurkishLocale && bestEnd < paragraph.length && bestWidth < widthLimit * 0.72f) {
                val nextWordStart = skipWhitespace(paragraph, bestEnd)
                val hyphenated = findSyllableBreak(
                    paragraph = paragraph,
                    lineStart = start,
                    wordStart = nextWordStart,
                    widthLimit = widthLimit,
                    textSizePx = textSizePx,
                    measurer = measurer
                )
                if (hyphenated != null) return hyphenated
            }
            return LineBreakStep(bestEnd, bestText, bestWidth)
        }
        if (!allowBrokenWords) return null
        if (isTurkishLocale) {
            val syllableStep = findSyllableBreak(
                paragraph = paragraph,
                lineStart = start,
                wordStart = start,
                widthLimit = widthLimit,
                textSizePx = textSizePx,
                measurer = measurer
            )
            if (syllableStep != null) return syllableStep
        }

        val brokenEnd = findBrokenEnd(paragraph, start, widthLimit, textSizePx, measurer)
        if (brokenEnd <= start) return null
        val rawSlice = paragraph.substring(start, brokenEnd).trim()
        val splitsMidWord = brokenEnd < paragraph.length &&
            rawSlice.isNotEmpty() &&
            rawSlice.last().isLetter() &&
            paragraph[brokenEnd].isLetter()
        val textWithHyphen = if (splitsMidWord) "$rawSlice-" else rawSlice
        val widthWithHyphen = measurer.measureWidth(textWithHyphen, textSizePx)
        return if (splitsMidWord && widthWithHyphen.isFinite() && widthWithHyphen <= widthLimit + FIT_EPSILON) {
            LineBreakStep(brokenEnd, textWithHyphen, widthWithHyphen)
        } else {
            val width = measurer.measureWidth(rawSlice, textSizePx)
            if (!width.isFinite() || width > widthLimit + FIT_EPSILON) return null
            LineBreakStep(brokenEnd, rawSlice, width)
        }
    }

    private fun findSyllableBreak(
        paragraph: String,
        lineStart: Int,
        wordStart: Int,
        widthLimit: Float,
        textSizePx: Float,
        measurer: BubbleTextMeasurer
    ): LineBreakStep? {
        if (wordStart >= paragraph.length || !paragraph[wordStart].isLetter()) return null
        var wordEnd = wordStart
        while (wordEnd < paragraph.length && (paragraph[wordEnd].isLetter() || paragraph[wordEnd] == '\'' || paragraph[wordEnd] == '’')) wordEnd++
        while (wordEnd > wordStart && (paragraph[wordEnd - 1] == '\'' || paragraph[wordEnd - 1] == '’')) wordEnd--
        val word = paragraph.substring(wordStart, wordEnd)
        val offsets = TurkishSyllabifier.syllableBreakOffsets(word)
        if (offsets.isEmpty()) return null

        var best: LineBreakStep? = null
        for (offset in offsets) {
            val splitIndex = wordStart + offset
            val hyphenStr = if (splitIndex > 0 && (paragraph[splitIndex - 1] == '\'' || paragraph[splitIndex - 1] == '’')) "" else "-"
            val candidate = (paragraph.substring(lineStart, splitIndex) + hyphenStr).trimStart()
            val width = measurer.measureWidth(candidate, textSizePx)
            if (width.isFinite() && width <= widthLimit + FIT_EPSILON) {
                best = LineBreakStep(splitIndex, candidate, width)
            } else {
                break
            }
        }
        return best
    }

    private fun findBrokenEnd(
        paragraph: String,
        start: Int,
        widthLimit: Float,
        textSizePx: Float,
        measurer: BubbleTextMeasurer
    ): Int {
        val codePointCount = paragraph.codePointCount(start, paragraph.length)
        if (codePointCount <= 3) return -1

        var low = 2
        var high = codePointCount - 2
        var bestEnd = -1
        while (low <= high) {
            val mid = low + (high - low) / 2
            val end = paragraph.offsetByCodePoints(start, mid)
            val candidate = paragraph.substring(start, end).trimEnd()
            val splitsMidWord = end < paragraph.length &&
                candidate.isNotEmpty() &&
                candidate.last().isLetter() &&
                paragraph[end].isLetter()
            val measuredCandidate = if (splitsMidWord) "$candidate-" else candidate
            val width = if (candidate.isEmpty()) {
                Float.POSITIVE_INFINITY
            } else {
                measurer.measureWidth(measuredCandidate, textSizePx)
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
