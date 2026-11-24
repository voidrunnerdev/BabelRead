package com.example.util

object BubbleTextEnvelopeGeometry {

    fun forBubble(
        bubbleWidthPx: Float,
        bubbleHeightPx: Float,
        horizontalPaddingPx: Float,
        verticalPaddingPx: Float,
        safeInsetPx: Float,
        contourProfile: ContourScanlineProfile? = null,
        isRectangular: Boolean = false
    ): BubbleLineEnvelope? {
        if (!bubbleWidthPx.isFinite() || !bubbleHeightPx.isFinite()) return null
        if (!horizontalPaddingPx.isFinite() || !verticalPaddingPx.isFinite()) return null
        if (!safeInsetPx.isFinite()) return null

        val paddingX = horizontalPaddingPx.coerceAtLeast(0f)
        val paddingY = verticalPaddingPx.coerceAtLeast(0f)
        val widthPx = bubbleWidthPx - paddingX * 2f
        val heightPx = bubbleHeightPx - paddingY * 2f
        if (widthPx <= 1f || heightPx <= 1f) return null

        val inset = safeInsetPx.coerceAtLeast(0f)
        if (inset * 2f >= widthPx || inset * 2f >= heightPx) return null

        val validContour = contourProfile?.takeIf { it.isUsable }
        val resolvedKind = when {
            isRectangular || validContour?.isRectangularLike == true -> EnvelopeShapeKind.ROUNDED_RECTANGLE
            validContour != null -> EnvelopeShapeKind.CONTOUR_SCANLINE
            else -> EnvelopeShapeKind.ELLIPSE
        }
        return BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, widthPx, heightPx),
            horizontalInsetPx = inset,
            verticalInsetPx = inset,
            shape = EnvelopeShape(kind = resolvedKind),
            contourProfile = validContour
        )
    }
}
