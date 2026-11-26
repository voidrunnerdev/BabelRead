package com.example.util

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class EnvelopeShapeKind {
    ELLIPSE,
    ROUNDED_RECTANGLE,
    CONTOUR_SCANLINE
}

data class EnvelopeShape(
    val kind: EnvelopeShapeKind = EnvelopeShapeKind.ELLIPSE,
    val cornerRadiusPx: Float = 0f
)

data class EnvelopeBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
}

data class ScanlineSpan(
    val leftPx: Float,
    val rightPx: Float
) {
    val widthPx: Float get() = (rightPx - leftPx).coerceAtLeast(0f)
    val centerX: Float get() = (leftPx + rightPx) / 2f
}

/**
 * Resolution-independent per-row horizontal spans (`0f..1f` across bubble width)
 * extracted from the actual speech bubble interior pixels.
 */
class ContourScanlineProfile(
    val rowLeftFractions: FloatArray,
    val rowRightFractions: FloatArray,
    val opticalCenterX: Float = 0.5f,
    val opticalCenterY: Float = 0.5f
) {
    val rowCount: Int get() = min(rowLeftFractions.size, rowRightFractions.size)

    val firstValidRow: Int
    val lastValidRow: Int

    init {
        var first = -1
        var last = -1
        for (i in 0 until rowCount) {
            if (rowRightFractions[i] > rowLeftFractions[i]) {
                if (first < 0) first = i
                last = i
            }
        }
        firstValidRow = first
        lastValidRow = last
    }

    val isUsable: Boolean get() = rowCount > 0 && firstValidRow >= 0 && lastValidRow >= firstValidRow

    val topFraction: Float
        get() = if (isUsable) firstValidRow.toFloat() / rowCount.toFloat() else 0f

    val bottomFraction: Float
        get() = if (isUsable) (lastValidRow + 1).toFloat() / rowCount.toFloat() else 1f

    /** True when all interior rows (~10%..90% band) stay uniformly wide with low edge drift (e.g., rectangular narration box). */
    val isRectangularLike: Boolean
        get() {
            if (!isUsable) return false
            val spanRows = lastValidRow - firstValidRow + 1
            if (spanRows < 4) return false
            val marginRows = if (spanRows >= 8) {
                ((spanRows - 1) * 0.10f).toInt().coerceAtLeast(1)
            } else {
                0
            }
            val bandStart = (firstValidRow + marginRows).coerceIn(firstValidRow, lastValidRow)
            val bandEnd = (lastValidRow - marginRows).coerceIn(bandStart, lastValidRow)
            var minW = Float.POSITIVE_INFINITY
            var maxW = 0f
            var minLeft = Float.POSITIVE_INFINITY
            var maxLeft = Float.NEGATIVE_INFINITY
            var minRight = Float.POSITIVE_INFINITY
            var maxRight = Float.NEGATIVE_INFINITY
            for (r in bandStart..bandEnd) {
                val lf = rowLeftFractions[r]
                val rf = rowRightFractions[r]
                if (!lf.isFinite() || !rf.isFinite() || rf <= lf) return false
                val w = rf - lf
                if (w < minW) minW = w
                if (w > maxW) maxW = w
                if (lf < minLeft) minLeft = lf
                if (lf > maxLeft) maxLeft = lf
                if (rf < minRight) minRight = rf
                if (rf > maxRight) maxRight = rf
            }
            if (!minW.isFinite() || maxW <= 0f) return false
            val leftSpread = maxLeft - minLeft
            val rightSpread = maxRight - minRight
            return minW > 0.50f &&
                minW >= maxW * 0.84f &&
                leftSpread <= 0.12f &&
                rightSpread <= 0.12f
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContourScanlineProfile) return false
        return rowLeftFractions.contentEquals(other.rowLeftFractions) &&
            rowRightFractions.contentEquals(other.rowRightFractions) &&
            opticalCenterX == other.opticalCenterX &&
            opticalCenterY == other.opticalCenterY
    }

    override fun hashCode(): Int {
        var result = rowLeftFractions.contentHashCode()
        result = 31 * result + rowRightFractions.contentHashCode()
        result = 31 * result + opticalCenterX.hashCode()
        result = 31 * result + opticalCenterY.hashCode()
        return result
    }
}

/**
 * Builds an organic [Path] representing the continuous contour
 * profile of the speech bubble within the target bounding box.
 */
fun buildContourPath(profile: ContourScanlineProfile, bounds: RectF): Path {
    val path = Path()
    if (!profile.isUsable || bounds.width() <= 0f || bounds.height() <= 0f) {
        path.addOval(bounds, Path.Direction.CW)
        return path
    }
    val totalRows = profile.rowCount
    val stepY = bounds.height() / totalRows
    val startRow = profile.firstValidRow
    val endRow = profile.lastValidRow

    // 1. Tepe kubbe merkezi
    val topMidX = bounds.left + (profile.rowLeftFractions[startRow] + profile.rowRightFractions[startRow]) * 0.5f * bounds.width()
    val topY = bounds.top + startRow * stepY
    path.moveTo(topMidX, topY)

    // 2. Sağ kenar (yukarıdan aşağıya pürüzsüz kuadratik Bézier eğrisi)
    var prevX = topMidX
    var prevY = topY
    for (r in startRow..endRow) {
        val curX = bounds.left + profile.rowRightFractions[r] * bounds.width()
        val curY = bounds.top + (r + 0.5f) * stepY
        val midX = (prevX + curX) * 0.5f
        val midY = (prevY + curY) * 0.5f
        path.quadTo(prevX, prevY, midX, midY)
        prevX = curX
        prevY = curY
    }

    // 3. Dip kubbe merkezi
    val botMidX = bounds.left + (profile.rowLeftFractions[endRow] + profile.rowRightFractions[endRow]) * 0.5f * bounds.width()
    val botY = bounds.top + (endRow + 1) * stepY
    path.quadTo(prevX, prevY, botMidX, botY)
    prevX = botMidX
    prevY = botY

    // 4. Sol kenar (aşağıdan yukarıya pürüzsüz kuadratik Bézier eğrisi)
    for (r in endRow downTo startRow) {
        val curX = bounds.left + profile.rowLeftFractions[r] * bounds.width()
        val curY = bounds.top + (r + 0.5f) * stepY
        val midX = (prevX + curX) * 0.5f
        val midY = (prevY + curY) * 0.5f
        path.quadTo(prevX, prevY, midX, midY)
        prevX = curX
        prevY = curY
    }

    // 5. Tepe kapanış: son sol noktadan tepe merkezine bağlanıp kapat
    path.quadTo(prevX, prevY, topMidX, topY)
    path.close()
    return path
}

/**
 * Describes the interior of a bubble as a per-line available-width source.
 * Coordinates are in the same space as the caller; the fitter uses local
 * canvas coordinates so no bitmap/dp conversion happens here.
 *
 * Supports analytical [EnvelopeShapeKind.ELLIPSE], [EnvelopeShapeKind.ROUNDED_RECTANGLE],
 * and pixel-extracted [EnvelopeShapeKind.CONTOUR_SCANLINE].
 */
data class BubbleLineEnvelope(
    val bounds: EnvelopeBounds,
    val horizontalInsetPx: Float = 0f,
    val verticalInsetPx: Float = 0f,
    val shape: EnvelopeShape = EnvelopeShape(),
    val contourProfile: ContourScanlineProfile? = null
) {
    private val insetX: Float get() = horizontalInsetPx.coerceAtLeast(0f)
    private val insetY: Float get() = verticalInsetPx.coerceAtLeast(0f)

    private val rawContentLeft: Float get() = bounds.left + insetX
    private val rawContentTop: Float get() = bounds.top + insetY
    private val rawContentRight: Float get() = bounds.right - insetX
    private val rawContentBottom: Float get() = bounds.bottom - insetY

    val contentLeft: Float get() = rawContentLeft
    val contentRight: Float get() = rawContentRight

    val contentTop: Float
        get() = if (shape.kind == EnvelopeShapeKind.CONTOUR_SCANLINE && contourProfile?.isUsable == true) {
            max(rawContentTop, bounds.top + contourProfile.topFraction * bounds.height)
        } else {
            rawContentTop
        }

    val contentBottom: Float
        get() = if (shape.kind == EnvelopeShapeKind.CONTOUR_SCANLINE && contourProfile?.isUsable == true) {
            min(rawContentBottom, bounds.top + contourProfile.bottomFraction * bounds.height)
        } else {
            rawContentBottom
        }

    val contentWidth: Float get() = (contentRight - contentLeft).coerceAtLeast(0f)
    val contentHeight: Float get() = (contentBottom - contentTop).coerceAtLeast(0f)
    val centerX: Float
        get() = if (shape.kind == EnvelopeShapeKind.CONTOUR_SCANLINE && contourProfile?.isUsable == true) {
            bounds.left + contourProfile.opticalCenterX * bounds.width
        } else {
            (contentLeft + contentRight) / 2f
        }
    val centerY: Float get() = (contentTop + contentBottom) / 2f
    val radiusX: Float get() = (contentWidth / 2f).coerceAtLeast(0f)
    val radiusY: Float get() = (contentHeight / 2f).coerceAtLeast(0f)

    val isSupportedShape: Boolean
        get() = shape.kind == EnvelopeShapeKind.ELLIPSE ||
            shape.kind == EnvelopeShapeKind.ROUNDED_RECTANGLE ||
            (shape.kind == EnvelopeShapeKind.CONTOUR_SCANLINE && contourProfile?.isUsable == true)

    val isUsable: Boolean
        get() = bounds.left.isFinite() &&
            bounds.top.isFinite() &&
            bounds.right.isFinite() &&
            bounds.bottom.isFinite() &&
            radiusX > 0.5f &&
            radiusY > 0.5f &&
            (shape.kind != EnvelopeShapeKind.CONTOUR_SCANLINE || contourProfile?.isUsable == true)

    fun spanAtY(y: Float): ScanlineSpan {
        if (!isUsable || !y.isFinite()) return ScanlineSpan(centerX, centerX)
        return when (shape.kind) {
            EnvelopeShapeKind.ELLIPSE -> {
                val normalizedY = ((y - centerY) / radiusY).coerceIn(-1f, 1f)
                val halfW = radiusX * sqrt((1f - normalizedY * normalizedY).coerceAtLeast(0f))
                ScanlineSpan(centerX - halfW, centerX + halfW)
            }
            EnvelopeShapeKind.CONTOUR_SCANLINE -> {
                val profile = contourProfile ?: return ScanlineSpan(centerX, centerX)
                if (!profile.isUsable || bounds.height <= 0f) return ScanlineSpan(centerX, centerX)
                if (y < contentTop || y > contentBottom) return ScanlineSpan(centerX, centerX)
                val fracY = ((y - bounds.top) / bounds.height).coerceIn(0f, 0.9999f)
                val row = floor(fracY * profile.rowCount).toInt().coerceIn(0, profile.rowCount - 1)
                spanForProfileRow(profile, row)
            }
            EnvelopeShapeKind.ROUNDED_RECTANGLE -> ScanlineSpan(contentLeft, contentRight)
        }
    }

    fun spanAtBand(topY: Float, bottomY: Float): ScanlineSpan {
        if (!isUsable || !topY.isFinite() || !bottomY.isFinite()) {
            return ScanlineSpan(centerX, centerX)
        }
        val start = min(topY, bottomY)
        val end = max(topY, bottomY)
        if (end < contentTop || start > contentBottom) return ScanlineSpan(centerX, centerX)
        val clampedStart = start.coerceIn(contentTop, contentBottom)
        val clampedEnd = end.coerceIn(contentTop, contentBottom)

        return when (shape.kind) {
            EnvelopeShapeKind.ELLIPSE -> {
                val rawHalfW = minOf(
                    halfWidthAtY(clampedStart),
                    halfWidthAtY((clampedStart + clampedEnd) / 2f),
                    halfWidthAtY(clampedEnd)
                )
                // Konuşma balonlarında tepe/dip kubbe bantları asla 0px çizgiye düşemez:
                // Tepeye temas eden ilk/son satırların sıfır genişlik yüzünden tüm yerleşimi
                // çökertmesini engellemek için bant ortasından veya en az %35 kubbe tabanı garantilenir.
                val halfW = if (rawHalfW <= 0.5f) {
                    val midY = (clampedStart + clampedEnd) / 2f
                    maxOf(halfWidthAtY(midY), radiusX * 0.35f)
                } else {
                    maxOf(rawHalfW, radiusX * 0.35f)
                }
                ScanlineSpan(centerX - halfW, centerX + halfW)
            }
            EnvelopeShapeKind.CONTOUR_SCANLINE -> {
                val profile = contourProfile ?: return ScanlineSpan(centerX, centerX)
                if (!profile.isUsable || bounds.height <= 0f) return ScanlineSpan(centerX, centerX)
                val startRow = floor(((clampedStart - bounds.top) / bounds.height).coerceIn(0f, 0.9999f) * profile.rowCount)
                    .toInt().coerceIn(profile.firstValidRow, profile.lastValidRow)
                val endRow = (ceil(((clampedEnd - bounds.top) / bounds.height).coerceIn(0f, 1f) * profile.rowCount).toInt() - 1)
                    .coerceIn(startRow, profile.lastValidRow)
                var maxLeft = contentLeft
                var minRight = contentRight
                for (row in startRow..endRow) {
                    val rowSpan = spanForProfileRow(profile, row)
                    if (rowSpan.widthPx <= 0f) return ScanlineSpan(centerX, centerX)
                    if (rowSpan.leftPx > maxLeft) maxLeft = rowSpan.leftPx
                    if (rowSpan.rightPx < minRight) minRight = rowSpan.rightPx
                }
                if (minRight <= maxLeft) ScanlineSpan(centerX, centerX)
                else ScanlineSpan(maxLeft, minRight)
            }
            EnvelopeShapeKind.ROUNDED_RECTANGLE -> ScanlineSpan(contentLeft, contentRight)
        }
    }

    private fun spanForProfileRow(profile: ContourScanlineProfile, row: Int): ScanlineSpan {
        val lf = profile.rowLeftFractions[row]
        val rf = profile.rowRightFractions[row]
        if (!lf.isFinite() || !rf.isFinite() || rf <= lf) return ScanlineSpan(centerX, centerX)
        val left = max(contentLeft, bounds.left + lf * bounds.width + insetX)
        val right = min(contentRight, bounds.left + rf * bounds.width - insetX)
        return if (right <= left) ScanlineSpan(centerX, centerX) else ScanlineSpan(left, right)
    }

    fun halfWidthAtY(y: Float): Float = spanAtY(y).widthPx / 2f

    fun halfWidthAtBand(topY: Float, bottomY: Float): Float = spanAtBand(topY, bottomY).widthPx / 2f

    fun widthAtBand(topY: Float, bottomY: Float): Float = spanAtBand(topY, bottomY).widthPx
}
