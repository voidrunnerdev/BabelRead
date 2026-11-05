package com.example.util

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class EnvelopeShapeKind {
    ELLIPSE,
    ROUNDED_RECTANGLE
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

/**
 * Describes the interior of a bubble as a per-line available-width source.
 * Coordinates are in the same space as the caller; the fitter uses local
 * canvas coordinates so no bitmap/dp conversion happens here.
 *
 * [shape] is intentionally metadata-only for now. The fitter accepts an
 * ellipse and rejects other shapes rather than guessing at an unsafe width.
 */
data class BubbleLineEnvelope(
    val bounds: EnvelopeBounds,
    val horizontalInsetPx: Float = 0f,
    val verticalInsetPx: Float = 0f,
    val shape: EnvelopeShape = EnvelopeShape()
) {
    private val insetX: Float get() = horizontalInsetPx.coerceAtLeast(0f)
    private val insetY: Float get() = verticalInsetPx.coerceAtLeast(0f)

    val contentLeft: Float get() = bounds.left + insetX
    val contentTop: Float get() = bounds.top + insetY
    val contentRight: Float get() = bounds.right - insetX
    val contentBottom: Float get() = bounds.bottom - insetY
    val contentWidth: Float get() = (contentRight - contentLeft).coerceAtLeast(0f)
    val contentHeight: Float get() = (contentBottom - contentTop).coerceAtLeast(0f)
    val centerX: Float get() = (contentLeft + contentRight) / 2f
    val centerY: Float get() = (contentTop + contentBottom) / 2f
    val radiusX: Float get() = (contentWidth / 2f).coerceAtLeast(0f)
    val radiusY: Float get() = (contentHeight / 2f).coerceAtLeast(0f)

    val isUsable: Boolean
        get() = bounds.left.isFinite() &&
            bounds.top.isFinite() &&
            bounds.right.isFinite() &&
            bounds.bottom.isFinite() &&
            radiusX > 0.5f &&
            radiusY > 0.5f

    fun halfWidthAtY(y: Float): Float {
        if (!isUsable || shape.kind != EnvelopeShapeKind.ELLIPSE || !y.isFinite()) return 0f
        val normalizedY = ((y - centerY) / radiusY).coerceIn(-1f, 1f)
        return radiusX * sqrt((1f - normalizedY * normalizedY).coerceAtLeast(0f))
    }

    fun halfWidthAtBand(topY: Float, bottomY: Float): Float {
        if (!isUsable || shape.kind != EnvelopeShapeKind.ELLIPSE) return 0f
        if (!topY.isFinite() || !bottomY.isFinite()) return 0f
        val start = min(topY, bottomY)
        val end = max(topY, bottomY)
        if (end < contentTop || start > contentBottom) return 0f
        val clampedStart = start.coerceIn(contentTop, contentBottom)
        val clampedEnd = end.coerceIn(contentTop, contentBottom)
        return minOf(
            halfWidthAtY(clampedStart),
            halfWidthAtY((clampedStart + clampedEnd) / 2f),
            halfWidthAtY(clampedEnd)
        )
    }

    fun widthAtBand(topY: Float, bottomY: Float): Float = halfWidthAtBand(topY, bottomY) * 2f
}
