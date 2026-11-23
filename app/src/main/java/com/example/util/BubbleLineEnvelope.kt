package com.example.util

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

    /** True when the top and bottom bands are nearly as wide as the middle (e.g., rectangular narration box). */
    val isRectangularLike: Boolean
        get() {
            if (!isUsable) return false
            val spanRows = lastValidRow - firstValidRow + 1
            if (spanRows < 4) return false
            val topIdx = (firstValidRow + spanRows / 8).coerceIn(firstValidRow, lastValidRow)
            val midIdx = (firstValidRow + lastValidRow) / 2
            val botIdx = (lastValidRow - spanRows / 8).coerceIn(firstValidRow, lastValidRow)
            val topW = rowRightFractions[topIdx] - rowLeftFractions[topIdx]
            val midW = rowRightFractions[midIdx] - rowLeftFractions[midIdx]
            val botW = rowRightFractions[botIdx] - rowLeftFractions[botIdx]
            return midW > 0.5f && topW >= midW * 0.86f && botW >= midW * 0.86f
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
 * Describes the interior of a bubble as a per-line available-width source.
 * Coordinates are in the same space as the caller; the fitter uses local
 * canvas coordinates so no bitmap/dp conversion happens here.
 *
 * Supports analytical [EnvelopeShapeKind.ELLIPSE] and pixel-extracted
 * [EnvelopeShapeKind.CONTOUR_SCANLINE]. [EnvelopeShapeKind.ROUNDED_RECTANGLE]
 * remains metadata-only.
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
                val halfW = minOf(
                    halfWidthAtY(clampedStart),
                    halfWidthAtY((clampedStart + clampedEnd) / 2f),
                    halfWidthAtY(clampedEnd)
                )
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
        val left = max(contentLeft, bounds.left + lf * bounds.width)
        val right = min(contentRight, bounds.left + rf * bounds.width)
        return if (right <= left) ScanlineSpan(centerX, centerX) else ScanlineSpan(left, right)
    }

    fun halfWidthAtY(y: Float): Float = spanAtY(y).widthPx / 2f

    fun halfWidthAtBand(topY: Float, bottomY: Float): Float = spanAtBand(topY, bottomY).widthPx / 2f

    fun widthAtBand(topY: Float, bottomY: Float): Float = spanAtBand(topY, bottomY).widthPx
}
