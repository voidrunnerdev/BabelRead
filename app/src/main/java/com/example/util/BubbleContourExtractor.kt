package com.example.util

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Extracts a resolution-independent [ContourScanlineProfile] from a speech bubble's
 * interior pixels using a 2-pass exact Euclidean Distance Transform (EDT, Felzenszwalb &
 * Huttenlocher) so text and interior cleaning masks conform accurately to real bubble
 * shapes (ovals, rectangles, spiky shout bubbles, clouds, and tail-heavy bubbles).
 *
 * The core grid algorithm ([extractFromLuminanceGrid]) is pure JVM Kotlin so it can
 * be unit-tested without Android Bitmaps.
 */
object BubbleContourExtractor {

    private const val MAX_GRID_DIM = 48
    private const val MIN_CROP_PX = 12
    private const val MIN_USABLE_AREA_RATIO = 0.17f
    private const val MIN_VALID_ROW_RATIO = 0.36f
    private const val MIN_SPAN_WIDTH_RATIO = 0.22f
    private const val MIN_MEDIAN_SPAN_RATIO = 0.34f
    private const val MAX_GLYPH_BRIDGE_RATIO = 0.10f
    private const val MAX_PERIMETER_LEAK_RATIO = 0.42f
    private const val EDT_INF = 1e6f

    fun extractFromBitmap(
        bitmap: Bitmap?,
        bubbleBox: Rect,
        anchorBox: Rect,
        pageOriginalWidth: Float,
        pageOriginalHeight: Float,
        isDarkBubble: Boolean = false,
        marginScale: Float = 1.0f
    ): ContourScanlineProfile? {
        if (bitmap == null || bitmap.isRecycled) return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE
        ) {
            return null
        }
        if (!pageOriginalWidth.isFinite() || !pageOriginalHeight.isFinite() ||
            pageOriginalWidth <= 0f || pageOriginalHeight <= 0f
        ) {
            return null
        }

        val scaleX = bitmap.width.toFloat() / pageOriginalWidth
        val scaleY = bitmap.height.toFloat() / pageOriginalHeight
        val left = (bubbleBox.left * scaleX).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (bubbleBox.top * scaleY).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (bubbleBox.right * scaleX).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bubbleBox.bottom * scaleY).roundToInt().coerceIn(top + 1, bitmap.height)
        val cropW = right - left
        val cropH = bottom - top
        if (cropW < MIN_CROP_PX || cropH < MIN_CROP_PX) return null

        val gridW = min(cropW, MAX_GRID_DIM)
        val gridH = min(cropH, MAX_GRID_DIM)
        val luma = IntArray(gridW * gridH)
        val rowBuffer = IntArray(cropW)

        try {
            // Border-preserving block pooling: thin 1-2px inked bubble outlines would be
            // skipped by single-pixel nearest-neighbor sampling, causing flood-fill to leak
            // into white page backgrounds outside the oval. Min-pooling (light bubbles) and
            // max-pooling (dark bubbles) preserve every 1px border stroke cleanly.
            for (gy in 0 until gridH) {
                val y0 = (top + (gy * cropH) / gridH).coerceIn(top, bottom - 1)
                val y1 = (top + ((gy + 1) * cropH) / gridH).coerceIn(y0 + 1, bottom)
                val rowOffset = gy * gridW
                for (gx in 0 until gridW) {
                    luma[rowOffset + gx] = if (isDarkBubble) 0 else 255
                }
                for (srcY in y0 until y1) {
                    bitmap.getPixels(rowBuffer, 0, cropW, left, srcY, cropW, 1)
                    for (gx in 0 until gridW) {
                        val x0 = ((gx * cropW) / gridW).coerceIn(0, cropW - 1)
                        val x1 = (((gx + 1) * cropW) / gridW).coerceIn(x0 + 1, cropW)
                        var cellLum = luma[rowOffset + gx]
                        for (srcX in x0 until x1) {
                            val argb = rowBuffer[srcX]
                            val a = (argb ushr 24) and 0xFF
                            val rawR = (argb shr 16) and 0xFF
                            val rawG = (argb shr 8) and 0xFF
                            val rawB = argb and 0xFF
                            val r = if (a < 255) (rawR * a + 255 * (255 - a)) / 255 else rawR
                            val g = if (a < 255) (rawG * a + 255 * (255 - a)) / 255 else rawG
                            val b = if (a < 255) (rawB * a + 255 * (255 - a)) / 255 else rawB
                            val pxLum = (299 * r + 587 * g + 114 * b) / 1000
                            if (isDarkBubble) {
                                if (pxLum > cellLum) cellLum = pxLum
                            } else {
                                if (pxLum < cellLum) cellLum = pxLum
                            }
                        }
                        luma[rowOffset + gx] = cellLum
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }

        val anchorCenterX = anchorBox.exactCenterX() * scaleX
        val anchorCenterY = anchorBox.exactCenterY() * scaleY
        val seedX = (((anchorCenterX - left) / cropW) * gridW).toInt().coerceIn(1, gridW - 2)
        val seedY = (((anchorCenterY - top) / cropH) * gridH).toInt().coerceIn(1, gridH - 2)

        // Map anchorBox (where original text glyphs live) into grid coordinates only when it
        // genuinely intersects the clamped bubble crop, preventing out-of-bounds anchors from
        // forcing a false seed at the bubble border.
        val anchorInter = Rect(
            (anchorBox.left * scaleX).roundToInt(),
            (anchorBox.top * scaleY).roundToInt(),
            (anchorBox.right * scaleX).roundToInt(),
            (anchorBox.bottom * scaleY).roundToInt()
        )
        val hasAnchorIntersection = anchorInter.intersect(left, top, right, bottom) && !anchorInter.isEmpty
        val aLeft = if (hasAnchorIntersection) {
            (((anchorInter.left - left).toFloat() / cropW) * gridW).toInt().coerceIn(1, gridW - 2)
        } else -1
        val aTop = if (hasAnchorIntersection) {
            (((anchorInter.top - top).toFloat() / cropH) * gridH).toInt().coerceIn(1, gridH - 2)
        } else -1
        val aRight = if (hasAnchorIntersection) {
            (((anchorInter.right - left).toFloat() / cropW) * gridW).toInt().coerceIn(aLeft, gridW - 2)
        } else -1
        val aBottom = if (hasAnchorIntersection) {
            (((anchorInter.bottom - top).toFloat() / cropH) * gridH).toInt().coerceIn(aTop, gridH - 2)
        } else -1

        return extractFromLuminanceGrid(
            luma = luma,
            gridW = gridW,
            gridH = gridH,
            seedX = seedX,
            seedY = seedY,
            isDarkBubble = isDarkBubble,
            anchorLeft = aLeft,
            anchorTop = aTop,
            anchorRight = aRight,
            anchorBottom = aBottom,
            marginScale = marginScale
        )
    }

    /**
     * Detects tinted/bordered rectangular narration boxes whose thin/colored edges or
     * flush panel boundaries may cause flood-fill contour extraction to return `null`.
     */
    fun detectRectangularFrameFromBitmap(
        bitmap: Bitmap?,
        bubbleBox: Rect,
        pageOriginalWidth: Float,
        pageOriginalHeight: Float
    ): ContourScanlineProfile? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width < 12 || bitmap.height < 12) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) return null
        if (!pageOriginalWidth.isFinite() || !pageOriginalHeight.isFinite() ||
            pageOriginalWidth <= 0f || pageOriginalHeight <= 0f || bubbleBox.isEmpty
        ) {
            return null
        }
        val scaleX = bitmap.width.toFloat() / pageOriginalWidth
        val scaleY = bitmap.height.toFloat() / pageOriginalHeight
        val left = (bubbleBox.left * scaleX).roundToInt().coerceIn(0, bitmap.width - 1)
        val top = (bubbleBox.top * scaleY).roundToInt().coerceIn(0, bitmap.height - 1)
        val right = (bubbleBox.right * scaleX).roundToInt().coerceIn(left + 1, bitmap.width)
        val bottom = (bubbleBox.bottom * scaleY).roundToInt().coerceIn(top + 1, bitmap.height)
        val boxW = right - left
        val boxH = bottom - top
        if (boxW < MIN_CROP_PX || boxH < MIN_CROP_PX) return null

        fun pxLuma(px: Int): Int {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            return (299 * r + 587 * g + 114 * b) / 1000
        }

        fun colorDist(c1: Int, c2: Int): Int {
            val dr = abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
            val dg = abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
            val db = abs((c1 and 0xFF) - (c2 and 0xFF))
            return dr + dg + db
        }

        val insetX = (boxW * 0.10f).roundToInt().coerceIn(2, max(2, boxW / 5))
        val insetY = (boxH * 0.10f).roundToInt().coerceIn(2, max(2, boxH / 5))
        val cornerCoords = arrayOf(
            intArrayOf((left + insetX).coerceIn(left, right - 1), (top + insetY).coerceIn(top, bottom - 1)),
            intArrayOf((right - 1 - insetX).coerceIn(left, right - 1), (top + insetY).coerceIn(top, bottom - 1)),
            intArrayOf((left + insetX).coerceIn(left, right - 1), (bottom - 1 - insetY).coerceIn(top, bottom - 1)),
            intArrayOf((right - 1 - insetX).coerceIn(left, right - 1), (bottom - 1 - insetY).coerceIn(top, bottom - 1))
        )
        val cornerColors = IntArray(4) { i -> bitmap.getPixel(cornerCoords[i][0], cornerCoords[i][1]) }
        val cornerLums = IntArray(4) { i -> pxLuma(cornerColors[i]) }
        var maxCornerDist = 0
        for (i in 0 until 4) {
            for (j in i + 1 until 4) {
                val d = colorDist(cornerColors[i], cornerColors[j])
                if (d > maxCornerDist) maxCornerDist = d
            }
        }
        // Rectangular panels have consistent interior corners (unlike round ovals on contrasting page backgrounds).
        if (maxCornerDist > 75) return null

        val avgCornerLum = cornerLums.average().toFloat()
        val avgCornerChroma = cornerColors.map { c ->
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            max(r, max(g, b)) - min(r, min(g, b))
        }.average().toFloat()
        val isTintedInterior = avgCornerLum < 236f || avgCornerChroma >= 10f

        var sharpEdges = 0
        val outOff = 3
        val inOff = min(4, max(2, min(boxW, boxH) / 10))
        val samples = 7

        fun checkVerticalEdge(xBorder: Int, isLeft: Boolean): Boolean {
            val xOut = (if (isLeft) xBorder - outOff else xBorder + outOff).coerceIn(0, bitmap.width - 1)
            val xIn = (if (isLeft) xBorder + inOff else xBorder - inOff).coerceIn(0, bitmap.width - 1)
            if (xOut == xIn) return true // flush against page edge
            var hits = 0
            val yStart = top + (boxH * 0.15f).roundToInt()
            val yEnd = bottom - 1 - (boxH * 0.15f).roundToInt()
            if (yEnd <= yStart) return false
            for (s in 0 until samples) {
                val y = (yStart + ((yEnd - yStart) * s) / (samples - 1)).coerceIn(0, bitmap.height - 1)
                val borderPx = bitmap.getPixel(xBorder.coerceIn(0, bitmap.width - 1), y)
                val inPx = bitmap.getPixel(xIn, y)
                val outPx = bitmap.getPixel(xOut, y)
                if (colorDist(inPx, outPx) >= 36 || colorDist(borderPx, inPx) >= 42) hits++
            }
            return hits >= 5
        }

        fun checkHorizontalEdge(yBorder: Int, isTop: Boolean): Boolean {
            val yOut = (if (isTop) yBorder - outOff else yBorder + outOff).coerceIn(0, bitmap.height - 1)
            val yIn = (if (isTop) yBorder + inOff else yBorder - inOff).coerceIn(0, bitmap.height - 1)
            if (yOut == yIn) return true
            var hits = 0
            val xStart = left + (boxW * 0.15f).roundToInt()
            val xEnd = right - 1 - (boxW * 0.15f).roundToInt()
            if (xEnd <= xStart) return false
            for (s in 0 until samples) {
                val x = (xStart + ((xEnd - xStart) * s) / (samples - 1)).coerceIn(0, bitmap.width - 1)
                val borderPx = bitmap.getPixel(x, yBorder.coerceIn(0, bitmap.height - 1))
                val inPx = bitmap.getPixel(x, yIn)
                val outPx = bitmap.getPixel(x, yOut)
                if (colorDist(inPx, outPx) >= 36 || colorDist(borderPx, inPx) >= 42) hits++
            }
            return hits >= 5
        }

        if (checkVerticalEdge(left, isLeft = true)) sharpEdges++
        if (checkVerticalEdge(right - 1, isLeft = false)) sharpEdges++
        if (checkHorizontalEdge(top, isTop = true)) sharpEdges++
        if (checkHorizontalEdge(bottom - 1, isTop = false)) sharpEdges++

        val qualifies = (sharpEdges >= 3) || (isTintedInterior && sharpEdges >= 2)
        if (!qualifies) return null

        val rows = 16
        return ContourScanlineProfile(
            rowLeftFractions = FloatArray(rows) { 0.02f },
            rowRightFractions = FloatArray(rows) { 0.98f },
            opticalCenterX = 0.5f,
            opticalCenterY = 0.5f
        )
    }

    /**
     * Extracts a normalized [ContourScanlineProfile] from a downsampled grayscale grid
     * (`luma[y * gridW + x]` in `0..255`) using 2-pass exact Euclidean Distance Transform.
     */
    fun extractFromLuminanceGrid(
        luma: IntArray,
        gridW: Int,
        gridH: Int,
        seedX: Int = gridW / 2,
        seedY: Int = gridH / 2,
        isDarkBubble: Boolean = false,
        anchorLeft: Int = -1,
        anchorTop: Int = -1,
        anchorRight: Int = -1,
        anchorBottom: Int = -1,
        marginScale: Float = 1.0f
    ): ContourScanlineProfile? {
        if (gridW < 8 || gridH < 8 || luma.size < gridW * gridH) return null

        val safeSeedX = seedX.coerceIn(1, gridW - 2)
        val safeSeedY = seedY.coerceIn(1, gridH - 2)

        // Estimate interior background luminance using the median of a 5x5 patch around the seed.
        val patch = IntArray(25)
        var patchCount = 0
        for (dy in -2..2) {
            val y = (safeSeedY + dy).coerceIn(0, gridH - 1)
            for (dx in -2..2) {
                val x = (safeSeedX + dx).coerceIn(0, gridW - 1)
                patch[patchCount++] = luma[y * gridW + x]
            }
        }
        patch.sort(0, patchCount)
        // Min/max pooling metin vuruşlarını 5x5 merkeze yayabildiği için açık balonlarda
        // üst %80 persentil (siyah harfi eler), koyu balonlarda alt %20 persentil (beyaz harfi eler) kullanılır.
        val seedLum = if (isDarkBubble) {
            patch[1.coerceAtMost(patchCount - 1)]
        } else {
            patch[(patchCount - 2).coerceAtLeast(0)]
        }

        // Çağıran isDarkBubble=false verdiyse yoğun siyah metin yüzünden polariteyi karanlığa çevirme!
        val isLight = !isDarkBubble && seedLum >= 125
        val isDark = isDarkBubble

        fun isInteriorPixel(lum: Int): Boolean = when {
            isLight -> {
                val tol = if (seedLum < 225) 40 else 58
                lum >= max(125, seedLum - tol)
            }
            isDark -> if (seedLum <= 110) {
                lum <= min(168, seedLum + 58)
            } else {
                val clampedSeed = seedLum.coerceAtMost(195)
                abs(lum - clampedSeed) <= 48 && lum < 215
            }
            else -> abs(lum - seedLum) <= 44
        }

        val hasAnchorRect = anchorLeft >= 1 && anchorTop >= 1 &&
            anchorRight >= anchorLeft && anchorBottom >= anchorTop &&
            anchorRight < gridW - 1 && anchorBottom < gridH - 1

        // Multi-seed BFS flood-fill. When an OCR anchor rectangle is known, mark its interior
        // cells as guaranteed bubble interior so residual un-erased glyphs inside the text area
        // can never block flood-fill or push the translated text into a narrow side pocket.
        val total = gridW * gridH
        val mask = BooleanArray(total)
        val queue = IntArray(total)
        var head = 0
        var tail = 0

        if (hasAnchorRect) {
            val anchorArea = (anchorRight - anchorLeft + 1) * (anchorBottom - anchorTop + 1)
            val isSmallOrFlat = min(gridW, gridH) <= 28 || gridW > gridH * 2
            val useEllipticalAnchor = !isSmallOrFlat && anchorArea > total * 0.42f
            val aCx = (anchorLeft + anchorRight) * 0.5f
            val aCy = (anchorTop + anchorBottom) * 0.5f
            val aRx = max(1f, (anchorRight - anchorLeft + 1) * 0.5f)
            val aRy = max(1f, (anchorBottom - anchorTop + 1) * 0.5f)
            for (sy in anchorTop..anchorBottom) {
                val rowOffset = sy * gridW
                val ny = (sy - aCy) / aRy
                for (sx in anchorLeft..anchorRight) {
                    val nx = (sx - aCx) / aRx
                    val distSq = nx * nx + ny * ny
                    if (useEllipticalAnchor && distSq > 1.0f) continue
                    val idx = rowOffset + sx
                    if (!useEllipticalAnchor && distSq > 0.72f && !isInteriorPixel(luma[idx])) continue
                    if (!mask[idx]) {
                        mask[idx] = true
                        queue[tail++] = idx
                    }
                }
            }
        } else {
            for (dy in -2..2) {
                val sy = (safeSeedY + dy).coerceIn(0, gridH - 1)
                for (dx in -2..2) {
                    val sx = (safeSeedX + dx).coerceIn(0, gridW - 1)
                    val idx = sy * gridW + sx
                    if (!mask[idx] && isInteriorPixel(luma[idx])) {
                        mask[idx] = true
                        queue[tail++] = idx
                    }
                }
            }
        }
        if (tail == 0) return null

        while (head < tail) {
            val idx = queue[head++]
            val x = idx % gridW
            val y = idx / gridW
            if (x > 0) {
                val n = idx - 1
                if (!mask[n] && isInteriorPixel(luma[n])) {
                    mask[n] = true
                    queue[tail++] = n
                }
            }
            if (x + 1 < gridW) {
                val n = idx + 1
                if (!mask[n] && isInteriorPixel(luma[n])) {
                    mask[n] = true
                    queue[tail++] = n
                }
            }
            if (y > 0) {
                val n = idx - gridW
                if (!mask[n] && isInteriorPixel(luma[n])) {
                    mask[n] = true
                    queue[tail++] = n
                }
            }
            if (y + 1 < gridH) {
                val n = idx + gridW
                if (!mask[n] && isInteriorPixel(luma[n])) {
                    mask[n] = true
                    queue[tail++] = n
                }
            }
        }

        // Reject contours where BFS leaked out through a broken/missing outline onto the
        // outer bounding-box perimeter (e.g. a white oval sitting on a white page background).
        // Returning null lets the caller fall back cleanly to the analytical oval envelope
        // instead of misclassifying the leaked box as a rectangular narration frame.
        var topLeak = 0
        var botLeak = 0
        var leftLeak = 0
        var rightLeak = 0
        for (x in 0 until gridW) {
            if (mask[x]) topLeak++
            if (mask[(gridH - 1) * gridW + x]) botLeak++
        }
        for (y in 1 until gridH - 1) {
            if (mask[y * gridW]) leftLeak++
            if (mask[y * gridW + (gridW - 1)]) rightLeak++
        }
        val leakedPerimeterCells = topLeak + botLeak + leftLeak + rightLeak
        val perimeterCells = 2 * gridW + 2 * (gridH - 2)
        val singleSideLeak = maxOf(
            topLeak.toFloat() / gridW,
            botLeak.toFloat() / gridW,
            leftLeak.toFloat() / max(1, gridH - 2),
            rightLeak.toFloat() / max(1, gridH - 2)
        )
        if ((perimeterCells > 0 && leakedPerimeterCells.toFloat() / perimeterCells.toFloat() > MAX_PERIMETER_LEAK_RATIO) ||
            singleSideLeak > 0.65f
        ) {
            return null
        }

        // Bridge only tiny interior glyph strokes (useful if an un-inpainted bitmap is passed),
        // requiring vertical interior neighbors so spiky bubble walls are never bridged over.
        val maxBridge = max(2, (gridW * MAX_GLYPH_BRIDGE_RATIO).toInt())
        for (y in 1 until gridH - 1) {
            val rowOffset = y * gridW
            var x = 0
            while (x < gridW) {
                if (!mask[rowOffset + x]) {
                    x++
                    continue
                }
                while (x < gridW && mask[rowOffset + x]) x++
                val gapStart = x
                while (x < gridW && !mask[rowOffset + x]) x++
                val gapEnd = x
                val gapLen = gapEnd - gapStart
                if (gapEnd < gridW && gapLen in 1..maxBridge) {
                    var hasVerticalInterior = false
                    for (gx in gapStart until gapEnd) {
                        if (mask[(y - 1) * gridW + gx] || mask[(y + 1) * gridW + gx]) {
                            hasVerticalInterior = true
                            break
                        }
                    }
                    if (hasVerticalInterior) {
                        for (gx in gapStart until gapEnd) {
                            mask[rowOffset + gx] = true
                        }
                    }
                }
            }
        }

        // 2-Pass Exact Euclidean Distance Transform (EDT) on the interior mask.
        // Outer boundary cells (x=0, x=gridW-1, y=0, y=gridH-1) act as distance-0 walls so
        // the safe interior always respects both inked borders and bounding-box edges.
        val dist = computeEuclideanDistanceTransform(mask, gridW, gridH)
        var maxDist = 0f
        var peakY = gridH / 2
        var peakX = gridW / 2
        for (y in 1 until gridH - 1) {
            val rowOffset = y * gridW
            for (x in 1 until gridW - 1) {
                val d = dist[rowOffset + x]
                if (d > maxDist) {
                    maxDist = d
                    peakX = x
                    peakY = y
                }
            }
        }
        if (maxDist < 1.5f) return null

        val safeMarginScale = if (marginScale.isFinite()) marginScale.coerceIn(0.35f, 1.5f) else 1.0f
        val baseMargin = max(1.1f, min(maxDist * 0.18f, 2.2f))
        val safeDistThreshold = (baseMargin * safeMarginScale).coerceAtLeast(0.95f)

        val safeInterior = BooleanArray(total)
        var safeCount = 0
        for (idx in 0 until total) {
            if (dist[idx] >= safeDistThreshold) {
                safeInterior[idx] = true
                safeCount++
            }
        }
        if (safeCount.toFloat() / total.toFloat() < MIN_USABLE_AREA_RATIO) {
            return null
        }

        // Extract the longest contiguous interior run for each row (BallonsTranslator widest continuous span).
        // If multiple interior pockets exist on a row (e.g. narrow speech tail vs main chamber),
        // prioritize the chamber run containing or closest to peakX (optical Chebyshev center).
        val rawLeft = FloatArray(gridH) { 0.5f }
        val rawRight = FloatArray(gridH) { 0.5f }
        val minSpanCells = max(2, (gridW * MIN_SPAN_WIDTH_RATIO).toInt())

        for (y in 0 until gridH) {
            val rowOffset = y * gridW
            var bestStart = -1
            var bestLen = 0
            var x = 0
            while (x < gridW) {
                if (!safeInterior[rowOffset + x]) {
                    x++
                    continue
                }
                val runStart = x
                while (x < gridW && safeInterior[rowOffset + x]) x++
                val runLen = x - runStart
                val containsPeakX = peakX in runStart until x
                val isBetter = when {
                    bestStart < 0 -> true
                    containsPeakX && runLen >= minSpanCells && runLen >= bestLen * 0.70f -> true
                    !containsPeakX && (bestStart <= peakX && bestStart + bestLen >= peakX) -> false
                    runLen > bestLen -> true
                    else -> false
                }
                if (isBetter) {
                    bestLen = runLen
                    bestStart = runStart
                }
            }
            if (bestStart >= 0 && bestLen >= minSpanCells) {
                rawLeft[y] = bestStart.toFloat() / gridW.toFloat()
                rawRight[y] = (bestStart + bestLen).toFloat() / gridW.toFloat()
            }
        }

        // Select the contiguous vertical chamber containing the EDT visual center (peakY),
        // or the longest contiguous chamber if peakY fell outside minSpanCells.
        var bestRunStart = -1
        var bestRunLen = 0
        var peakRunStart = -1
        var peakRunLen = 0
        var ry = 0
        while (ry < gridH) {
            if (rawRight[ry] <= rawLeft[ry]) {
                ry++
                continue
            }
            val runStart = ry
            while (ry < gridH && rawRight[ry] > rawLeft[ry]) ry++
            val runLen = ry - runStart
            if (peakY in runStart until ry) {
                peakRunStart = runStart
                peakRunLen = runLen
            }
            if (runLen > bestRunLen) {
                bestRunLen = runLen
                bestRunStart = runStart
            }
        }
        val chosenStart = if (peakRunStart >= 0 && peakRunLen >= bestRunLen * 0.6f) peakRunStart else bestRunStart
        val chosenLen = if (peakRunStart >= 0 && peakRunLen >= bestRunLen * 0.6f) peakRunLen else bestRunLen
        if (chosenStart < 0 || chosenLen.toFloat() / gridH.toFloat() < MIN_VALID_ROW_RATIO) {
            return null
        }

        val chosenEnd = chosenStart + chosenLen
        // Sanity check: reject trapped side-pocket contours whose median width is too narrow.
        val validWidths = FloatArray(chosenLen) { i ->
            val r = chosenStart + i
            rawRight[r] - rawLeft[r]
        }
        validWidths.sort()
        if (validWidths[validWidths.size / 2] < MIN_MEDIAN_SPAN_RATIO) {
            return null
        }
        for (y in 0 until gridH) {
            if (y < chosenStart || y >= chosenEnd) {
                rawLeft[y] = 0.5f
                rawRight[y] = 0.5f
            }
        }

        // 5-row median filter inside the valid vertical chamber removes multi-row leak spikes.
        if (chosenLen >= 5) {
            val medL = FloatArray(chosenLen)
            val medR = FloatArray(chosenLen)
            val winL = FloatArray(5)
            val winR = FloatArray(5)
            for (i in 0 until chosenLen) {
                val y = chosenStart + i
                for (k in -2..2) {
                    val ny = (y + k).coerceIn(chosenStart, chosenEnd - 1)
                    winL[k + 2] = rawLeft[ny]
                    winR[k + 2] = rawRight[ny]
                }
                winL.sort()
                winR.sort()
                medL[i] = winL[2]
                medR[i] = winR[2].coerceAtLeast(medL[i])
            }
            for (i in 0 until chosenLen) {
                val y = chosenStart + i
                rawLeft[y] = medL[i]
                rawRight[y] = medR[i]
            }
        }

        // Gentle 3-row conservative smoothing (narrower of neighbors) prevents 1-cell jagged spikes.
        val smoothLeft = FloatArray(gridH)
        val smoothRight = FloatArray(gridH)
        for (y in 0 until gridH) {
            if (rawRight[y] <= rawLeft[y]) {
                smoothLeft[y] = 0.5f
                smoothRight[y] = 0.5f
                continue
            }
            var l = rawLeft[y]
            var r = rawRight[y]
            if (y > 0 && rawRight[y - 1] > rawLeft[y - 1]) {
                l = (l * 0.7f + max(l, rawLeft[y - 1]) * 0.3f)
                r = (r * 0.7f + min(r, rawRight[y - 1]) * 0.3f)
            }
            if (y + 1 < gridH && rawRight[y + 1] > rawLeft[y + 1]) {
                l = (l * 0.7f + max(l, rawLeft[y + 1]) * 0.3f)
                r = (r * 0.7f + min(r, rawRight[y + 1]) * 0.3f)
            }
            if (r > l) {
                smoothLeft[y] = l.coerceIn(0f, 1f)
                smoothRight[y] = r.coerceIn(smoothLeft[y], 1f)
            } else {
                smoothLeft[y] = rawLeft[y]
                smoothRight[y] = rawRight[y]
            }
        }

        val optCenterX = (peakX.toFloat() + 0.5f) / gridW.toFloat()
        val optCenterY = (peakY.toFloat() + 0.5f) / gridH.toFloat()
        return ContourScanlineProfile(
            rowLeftFractions = smoothLeft,
            rowRightFractions = smoothRight,
            opticalCenterX = optCenterX,
            opticalCenterY = optCenterY
        )
    }

    /**
     * Exact O(W * H) 2D Euclidean Distance Transform (Felzenszwalb & Huttenlocher).
     * Returns Euclidean distance in grid cells from each interior pixel to the nearest
     * exterior pixel or grid boundary.
     */
    internal fun computeEuclideanDistanceTransform(
        mask: BooleanArray,
        gridW: Int,
        gridH: Int
    ): FloatArray {
        val total = gridW * gridH
        val temp = FloatArray(total)
        val maxDim = max(gridW, gridH)
        val f = FloatArray(maxDim)
        val d = FloatArray(maxDim)
        val v = IntArray(maxDim)
        val z = FloatArray(maxDim + 1)

        // Pass 1: transform along columns
        for (x in 0 until gridW) {
            for (y in 0 until gridH) {
                val isBorder = (x == 0 || x == gridW - 1 || y == 0 || y == gridH - 1)
                f[y] = if (!isBorder && mask[y * gridW + x]) EDT_INF else 0f
            }
            edt1d(f, gridH, d, v, z)
            for (y in 0 until gridH) {
                temp[y * gridW + x] = d[y]
            }
        }

        // Pass 2: transform along rows
        val dist = FloatArray(total)
        for (y in 0 until gridH) {
            val rowOffset = y * gridW
            for (x in 0 until gridW) {
                f[x] = temp[rowOffset + x]
            }
            edt1d(f, gridW, d, v, z)
            for (x in 0 until gridW) {
                dist[rowOffset + x] = sqrt(d[x].coerceAtLeast(0f))
            }
        }
        return dist
    }

    private fun edt1d(
        f: FloatArray,
        n: Int,
        d: FloatArray,
        v: IntArray,
        z: FloatArray
    ) {
        var k = 0
        v[0] = 0
        z[0] = Float.NEGATIVE_INFINITY
        z[1] = Float.POSITIVE_INFINITY
        for (q in 1 until n) {
            val fq = f[q]
            while (true) {
                val r = v[k]
                val s = ((fq + q * q) - (f[r] + r * r)) / (2f * (q - r))
                if (s <= z[k]) {
                    k--
                    if (k < 0) {
                        k = 0
                        v[0] = q
                        z[0] = Float.NEGATIVE_INFINITY
                        z[1] = Float.POSITIVE_INFINITY
                        break
                    }
                } else {
                    k++
                    v[k] = q
                    z[k] = s
                    z[k + 1] = Float.POSITIVE_INFINITY
                    break
                }
            }
        }
        var idx = 0
        for (q in 0 until n) {
            while (z[idx + 1] < q.toFloat()) idx++
            val r = v[idx]
            val diff = q - r
            d[q] = diff * diff + f[r]
        }
    }
}
