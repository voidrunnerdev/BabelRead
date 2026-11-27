package com.example.service

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

data class SegmentedTextLine(
    val rect: Rect,
    val vertical: Boolean
)

/**
 * Heuristic line splitter for detector text blocks.
 *
 * PP-OCR recognition models expect one text line per crop. The comic detector
 * returns text blocks (often a whole balloon paragraph), so this class builds a
 * text-ink mask and uses projection profiles to recover lines. It is used when
 * the PP-OCRv5 detection model is unavailable or its result is unusable.
 */
class TextLineSegmenter {

    private companion object {
        const val MAX_SEGMENT_PIXELS = 2_000_000L
        const val MAX_RECOGNITION_LINES = 32
    }

    fun segment(
        bitmap: Bitmap,
        region: Rect,
        originLanguage: String,
        forceVertical: Boolean = false,
        forceHorizontal: Boolean = false
    ): List<SegmentedTextLine> {
        if (bitmap.isRecycled || region.isEmpty) return emptyList()
        val pageRect = Rect(region)
        if (!pageRect.intersect(0, 0, bitmap.width, bitmap.height)) return emptyList()
        if (pageRect.width() < 8 || pageRect.height() < 8) {
            return singleLine(pageRect, originLanguage, forceVertical, forceHorizontal)
        }

        val pad = (min(pageRect.width(), pageRect.height()) / 16).coerceIn(2, 8)
        val cropRect = Rect(
            (pageRect.left - pad).coerceAtLeast(0),
            (pageRect.top - pad).coerceAtLeast(0),
            (pageRect.right + pad).coerceAtMost(bitmap.width),
            (pageRect.bottom + pad).coerceAtMost(bitmap.height)
        )
        if (cropRect.width() < 8 || cropRect.height() < 8 ||
            cropRect.width().toLong() * cropRect.height() > MAX_SEGMENT_PIXELS
        ) {
            return singleLine(pageRect, originLanguage, forceVertical, forceHorizontal)
        }

        val crop = try {
            Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        } catch (_: Exception) {
            return singleLine(pageRect, originLanguage, forceVertical, forceHorizontal)
        }

        val w = crop.width
        val h = crop.height
        val pixels = try {
            IntArray(w * h).also { crop.getPixels(it, 0, w, 0, 0, w, h) }
        } catch (_: Throwable) {
            return singleLine(pageRect, originLanguage, forceVertical, forceHorizontal)
        } finally {
            if (crop !== bitmap && !crop.isRecycled) crop.recycle()
        }

        val gray = IntArray(pixels.size) { index ->
            val color = pixels[index]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }
        val minGray = gray.minOrNull() ?: 255
        val maxGray = gray.maxOrNull() ?: 0
        if (maxGray - minGray < 20) {
            return singleLine(pageRect, originLanguage, forceVertical, forceHorizontal)
        }

        val threshold = otsuThreshold(gray)
        val lightCount = gray.count { it >= threshold }
        var borderLight = 0
        var borderDark = 0
        for (x in 0 until w) {
            if (gray[x] >= threshold) borderLight++ else borderDark++
            if (gray[(h - 1) * w + x] >= threshold) borderLight++ else borderDark++
        }
        for (y in 1 until h - 1) {
            if (gray[y * w] >= threshold) borderLight++ else borderDark++
            if (gray[y * w + (w - 1)] >= threshold) borderLight++ else borderDark++
        }
        val backgroundIsLight = if (borderLight != borderDark) borderLight > borderDark else lightCount >= gray.size - lightCount
        val inkMask = BooleanArray(gray.size) { index ->
            if (backgroundIsLight) gray[index] < threshold else gray[index] > threshold
        }
        val cleanedMask = removeNonTextComponents(inkMask, w, h)
        val inkCount = cleanedMask.count { it }
        if (inkCount < max(8, w * h / 5000)) {
            return singleLine(pageRect, originLanguage, forceVertical, forceHorizontal)
        }

        val horizontalLines = buildHorizontalLines(cleanedMask, w, h, cropRect)
        val verticalLines = buildVerticalLines(cleanedMask, w, h, cropRect)
        val japanese = isJapanese(originLanguage)

        val chosen = when {
            forceVertical -> verticalLines
            forceHorizontal -> horizontalLines
            !japanese -> horizontalLines.ifEmpty { verticalLines }
            else -> {
                val horizontalScore = scoreLines(horizontalLines, cleanedMask, w, h, vertical = false, cropRect = cropRect)
                val verticalScore = scoreLines(verticalLines, cleanedMask, w, h, vertical = true, cropRect = cropRect)
                val aspectBias = when {
                    h >= w * 0.90f -> 0.55f
                    w > h * 1.30f -> -0.55f
                    else -> 0.0f
                }
                if (verticalLines.isNotEmpty() && verticalScore + aspectBias >= horizontalScore) {
                    verticalLines
                } else {
                    horizontalLines.ifEmpty { verticalLines }
                }
            }
        }

        val filtered = if (japanese) filterSmallJapaneseLines(chosen) else chosen
        val bounded = coalesceLines(filtered)
        val result = bounded.map { line ->
            val rect = Rect(line.rect)
            val clamped = if (rect.intersect(0, 0, bitmap.width, bitmap.height)) {
                rect
            } else {
                Rect(pageRect)
            }
            SegmentedTextLine(clamped, line.vertical)
        }.filter { !it.rect.isEmpty }

        return result.ifEmpty {
            singleLine(pageRect, originLanguage, forceVertical, forceHorizontal)
        }
    }

    private fun singleLine(
        pageRect: Rect,
        originLanguage: String,
        forceVertical: Boolean,
        forceHorizontal: Boolean
    ): List<SegmentedTextLine> {
        val vertical = when {
            forceVertical -> true
            forceHorizontal -> false
            else -> isVerticalBlock(pageRect, originLanguage)
        }
        return listOf(SegmentedTextLine(pageRect, vertical))
    }

    private fun coalesceLines(lines: List<SegmentedTextLine>): List<SegmentedTextLine> {
        if (lines.size <= MAX_RECOGNITION_LINES) return lines
        val groupSize = (lines.size + MAX_RECOGNITION_LINES - 1) / MAX_RECOGNITION_LINES
        val merged = ArrayList<SegmentedTextLine>(MAX_RECOGNITION_LINES)
        var index = 0
        while (index < lines.size) {
            val end = min(index + groupSize, lines.size)
            val rect = Rect(lines[index].rect)
            for (i in index + 1 until end) rect.union(lines[i].rect)
            merged.add(SegmentedTextLine(rect, lines[index].vertical))
            index = end
        }
        return merged
    }

    private fun isJapanese(originLanguage: String): Boolean =
        originLanguage.equals("Japonca", ignoreCase = true) ||
            originLanguage.equals("Japanese", ignoreCase = true)

    private fun isVerticalBlock(rect: Rect, originLanguage: String): Boolean =
        isJapanese(originLanguage) && rect.height() > rect.width() * 1.8f

    private fun otsuThreshold(gray: IntArray): Int {
        val histogram = IntArray(256)
        for (value in gray) histogram[value.coerceIn(0, 255)]++
        val total = gray.size
        var sum = 0L
        for (i in 0 until 256) sum += i.toLong() * histogram[i]
        var sumBackground = 0L
        var weightBackground = 0
        var bestVariance = -1.0
        var bestThreshold = 127
        for (t in 0 until 256) {
            weightBackground += histogram[t]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += t.toLong() * histogram[t]
            val meanBackground = sumBackground.toDouble() / weightBackground
            val meanForeground = (sum - sumBackground).toDouble() / weightForeground
            val difference = meanBackground - meanForeground
            val variance = weightBackground.toDouble() * weightForeground * difference * difference
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = t
            }
        }
        return bestThreshold.coerceIn(1, 254)
    }

    private data class ComponentStats(
        val area: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private fun removeNonTextComponents(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val labels = IntArray(mask.size) { -1 }
        val queue = IntArray(mask.size)
        val components = ArrayList<ComponentStats>()

        for (start in mask.indices) {
            if (!mask[start] || labels[start] >= 0) continue
            val componentId = components.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = componentId
            var area = 0
            var left = w
            var top = h
            var right = -1
            var bottom = -1

            while (head < tail) {
                val index = queue[head++]
                val x = index % w
                val y = index / w
                area++
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y

                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val next = ny * w + nx
                        if (mask[next] && labels[next] < 0) {
                            labels[next] = componentId
                            queue[tail++] = next
                        }
                    }
                }
            }
            components.add(ComponentStats(area, left, top, right, bottom))
        }

        val keep = BooleanArray(components.size)
        val cropArea = w.toLong() * h
        val minArea = 2
        for (index in components.indices) {
            val component = components[index]
            val componentWidth = component.right - component.left + 1
            val componentHeight = component.bottom - component.top + 1
            val isLongBorderLine = (componentWidth > w * 0.80f && componentHeight <= 3) ||
                (componentHeight > h * 0.80f && componentWidth <= 3)
            val isBackgroundBlob = component.area.toLong() > cropArea / 5
            val touchesHorizontalEdge = component.top <= 0 || component.bottom >= h - 1
            val touchesVerticalEdge = component.left <= 0 || component.right >= w - 1
            val touchesEdge = touchesHorizontalEdge || touchesVerticalEdge
            val isEdgeBorderStroke = (touchesHorizontalEdge && componentWidth > w * 0.70f &&
                componentHeight <= max(4, (h * 0.15f).toInt())) ||
                (touchesVerticalEdge && componentHeight > h * 0.70f &&
                    componentWidth <= max(4, (w * 0.15f).toInt()))
            val isLargeEdgeBlob = touchesEdge &&
                component.area.toLong() > cropArea / 8 &&
                (componentWidth > w * 0.50f || componentHeight > h * 0.50f)
            val isEdgeArtwork = isEdgeBorderStroke || isLargeEdgeBlob
            keep[index] = component.area >= minArea && !isLongBorderLine &&
                !isBackgroundBlob && !isEdgeArtwork
        }

        val cleaned = BooleanArray(mask.size)
        for (index in mask.indices) {
            val label = labels[index]
            if (label >= 0 && keep[label]) cleaned[index] = true
        }
        return cleaned
    }

    private fun buildHorizontalLines(
        mask: BooleanArray,
        w: Int,
        h: Int,
        cropRect: Rect
    ): List<SegmentedTextLine> {
        val profile = IntArray(h)
        for (y in 0 until h) {
            var count = 0
            val rowOffset = y * w
            for (x in 0 until w) if (mask[rowOffset + x]) count++
            profile[y] = count
        }
        val smoothed = IntArray(h) { index ->
            val before = profile[(index - 1).coerceAtLeast(0)]
            val after = profile[(index + 1).coerceAtMost(h - 1)]
            (before + profile[index] * 2 + after) / 4
        }
        val maxValue = smoothed.maxOrNull() ?: 0
        if (maxValue <= 0) return emptyList()
        val threshold = max(1, (maxValue * 0.08f).toInt())
        val runs = runs(smoothed, threshold)

        val lines = ArrayList<SegmentedTextLine>()
        for (run in runs) {
            val y1 = (run.first - 1).coerceAtLeast(0)
            val y2 = (run.last + 1).coerceAtMost(h - 1)
            var left = w
            var right = -1
            var pixels = 0
            for (y in y1..y2) {
                val rowOffset = y * w
                for (x in 0 until w) {
                    if (mask[rowOffset + x]) {
                        if (x < left) left = x
                        if (x > right) right = x
                        pixels++
                    }
                }
            }
            if (right < left || pixels < 8 || right - left < 4 || y2 - y1 < 2) continue
            val padX = 4
            val padY = 2
            val rect = Rect(
                cropRect.left + (left - padX).coerceAtLeast(0),
                cropRect.top + (y1 - padY).coerceAtLeast(0),
                cropRect.left + (right + padX + 1).coerceAtMost(w),
                cropRect.top + (y2 + padY + 1).coerceAtMost(h)
            )
            lines.add(SegmentedTextLine(rect, vertical = false))
        }
        return lines
    }

    private fun buildVerticalLines(
        mask: BooleanArray,
        w: Int,
        h: Int,
        cropRect: Rect
    ): List<SegmentedTextLine> {
        val profile = IntArray(w)
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) if (mask[rowOffset + x]) profile[x]++
        }
        val smoothed = IntArray(w) { index ->
            val before = profile[(index - 1).coerceAtLeast(0)]
            val after = profile[(index + 1).coerceAtMost(w - 1)]
            (before + profile[index] * 2 + after) / 4
        }
        val maxValue = smoothed.maxOrNull() ?: 0
        if (maxValue <= 0) return emptyList()
        val threshold = max(1, (maxValue * 0.08f).toInt())
        val runs = runs(smoothed, threshold)

        val lines = ArrayList<SegmentedTextLine>()
        for (run in runs) {
            val x1 = (run.first - 1).coerceAtLeast(0)
            val x2 = (run.last + 1).coerceAtMost(w - 1)
            var top = h
            var bottom = -1
            var pixels = 0
            for (y in 0 until h) {
                val rowOffset = y * w
                for (x in x1..x2) {
                    if (mask[rowOffset + x]) {
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                        pixels++
                    }
                }
            }
            if (bottom < top || pixels < 8 || bottom - top < 4 || x2 - x1 < 2) continue
            val padX = 2
            val padY = 4
            val rect = Rect(
                cropRect.left + (x1 - padX).coerceAtLeast(0),
                cropRect.top + (top - padY).coerceAtLeast(0),
                cropRect.left + (x2 + padX + 1).coerceAtMost(w),
                cropRect.top + (bottom + padY + 1).coerceAtMost(h)
            )
            lines.add(SegmentedTextLine(rect, vertical = true))
        }
        return lines.sortedByDescending { it.rect.right }
    }

    private fun runs(profile: IntArray, threshold: Int, maxBridgeGap: Int = 2): List<IntRange> {
        val raw = ArrayList<IntRange>()
        var start = -1
        for (index in 0..profile.size) {
            val active = index < profile.size && profile[index] >= threshold
            if (active && start < 0) start = index
            if (!active && start >= 0) {
                raw.add(start until index)
                start = -1
            }
        }
        if (raw.size <= 1 || maxBridgeGap <= 0) return raw
        val merged = ArrayList<IntRange>(raw.size)
        var cur = raw[0]
        for (i in 1 until raw.size) {
            val next = raw[i]
            if (next.first - cur.last - 1 <= maxBridgeGap) {
                cur = cur.first..next.last
            } else {
                merged.add(cur)
                cur = next
            }
        }
        merged.add(cur)
        return merged
    }

    private fun scoreLines(
        lines: List<SegmentedTextLine>,
        mask: BooleanArray,
        w: Int,
        h: Int,
        vertical: Boolean,
        cropRect: Rect
    ): Double {
        if (lines.isEmpty()) return -1_000_000.0
        val totalInk = mask.count { it }
        if (totalInk <= 0) return -1_000_000.0
        var covered = 0
        var areaSum = 0.0
        val shapeScores = ArrayList<Double>(lines.size)
        for (line in lines) {
            val rect = line.rect
            val x1 = (rect.left - cropRect.left).coerceIn(0, w - 1)
            val y1 = (rect.top - cropRect.top).coerceIn(0, h - 1)
            val x2 = (rect.right - 1 - cropRect.left).coerceIn(x1, w - 1)
            val y2 = (rect.bottom - 1 - cropRect.top).coerceIn(y1, h - 1)
            var linePixels = 0
            for (y in y1..y2) {
                val rowOffset = y * w
                for (x in x1..x2) if (mask[rowOffset + x]) linePixels++
            }
            covered += linePixels
            val lineWidth = (x2 - x1 + 1).toDouble()
            val lineHeight = (y2 - y1 + 1).toDouble()
            areaSum += lineWidth * lineHeight
            val ratio = if (vertical) lineHeight / lineWidth else lineWidth / lineHeight
            shapeScores.add(tanh(ln(max(ratio, 1e-6))))
        }
        if (areaSum <= 0.0 || shapeScores.isEmpty()) return -1_000_000.0
        val coverage = covered.toDouble() / totalInk
        val density = covered / areaSum
        val shape = shapeScores.average()
        val fragmentationPenalty = 0.08 * max(0, lines.size - 1)
        return coverage * 1.4 + density * 0.8 + shape * 1.2 - fragmentationPenalty
    }

    private fun filterSmallJapaneseLines(
        lines: List<SegmentedTextLine>
    ): List<SegmentedTextLine> {
        if (lines.size < 2) return lines
        val vertical = lines.count { it.vertical } >= lines.size / 2
        val thickness = lines.map {
            if (vertical) it.rect.width().toDouble() else it.rect.height().toDouble()
        }
        val areas = lines.map { it.rect.width().toDouble() * it.rect.height().toDouble() }
        val medianThickness = median(thickness)
        val medianArea = median(areas)
        if (medianThickness <= 0.0 || medianArea <= 0.0) return lines
        val kept = lines.filterIndexed { index, _ ->
            val isSmall = thickness[index] < medianThickness * 0.55 &&
                areas[index] < medianArea * 0.55
            !isSmall
        }
        if (kept.isNotEmpty()) return kept
        val mainIndex = lines.indices.maxByOrNull { areas[it] } ?: return lines
        return listOf(lines[mainIndex])
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }
}
