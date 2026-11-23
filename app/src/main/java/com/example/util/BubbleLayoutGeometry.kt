package com.example.util

import android.graphics.Rect

/**
 * Rectangles handled here are in the bitmap coordinate space used by OCR and
 * bubble detection. ReaderScreen converts them to dp only after layout.
 */
object BubbleLayoutGeometry {

    private const val MIN_GROWTH_RATIO = 1.10f
    private const val OVAL_FILL_RATIO = 0.35f
    private const val EDGE_MARGIN_RATIO = 0.04f

    /**
     * Largest centered rectangle that is safely inside the rounded balloon.
     * This is the 70% inscribed-ellipse area, additionally inset by a small
     * detector margin.
     */
    private fun safeOval(bubble: Rect): Rect? {
        if (bubble.isEmpty) return null
        val minSide = minOf(bubble.width(), bubble.height()).coerceAtLeast(0)
        val margin = (minSide * EDGE_MARGIN_RATIO).toInt().coerceAtMost(minSide / 4).coerceAtLeast(0)
        val safe = Rect(
            bubble.left + margin,
            bubble.top + margin,
            bubble.right - margin,
            bubble.bottom - margin
        )

        val centerX = bubble.exactCenterX()
        val centerY = bubble.exactCenterY()
        val ovalSafe = Rect(
            (centerX - bubble.width() * OVAL_FILL_RATIO).toInt().coerceAtLeast(bubble.left),
            (centerY - bubble.height() * OVAL_FILL_RATIO).toInt().coerceAtLeast(bubble.top),
            (centerX + bubble.width() * OVAL_FILL_RATIO).toInt().coerceAtMost(bubble.right),
            (centerY + bubble.height() * OVAL_FILL_RATIO).toInt().coerceAtMost(bubble.bottom)
        )
        if (ovalSafe.isEmpty) return null
        val result = Rect(ovalSafe)
        if (!result.intersect(safe) || result.isEmpty) return null
        return result
    }

    /**
     * Returns the balloon interior that should be used for translated text.
     *
     * Growth is meaningful when either axis grows by at least 10%. Requiring
     * both axes used to reject tall/narrow (or wide/short) balloons whose OCR
     * anchor happened to be close to one bubble edge. The result is never
     * smaller than the anchor on either axis: a grown axis uses the safe oval
     * dimension, while a non-grown axis keeps the anchor dimension. The
     * combined rectangle is centered in the bubble.
     *
     * A partially outside anchor is rejected instead of clipped. Clipping would
     * silently shrink the source text and bring back the original P0 problem.
     *
     * A text box may therefore extend outside the 70% safe oval when preserving
     * readability. Backgrounds and other visual sources must clamp to
     * [clampToSafeOval] at draw time.
     */
    fun centeredLayoutBox(anchor: Rect, bubble: Rect): Rect? {
        if (anchor.isEmpty || bubble.isEmpty) return null
        val effectiveBubble = if (bubble.contains(anchor)) {
            bubble
        } else if (Rect.intersects(bubble, anchor)) {
            // BallonsTranslator _cover_text_region: merge protruding OCR anchor with bubble
            // bounds so text region slightly crossing detected edges is safely included.
            Rect(bubble).apply { union(anchor) }
        } else {
            return null
        }
        val oval = safeOval(effectiveBubble) ?: return null

        val growsWidth = oval.width() >= anchor.width() * MIN_GROWTH_RATIO
        val growsHeight = oval.height() >= anchor.height() * MIN_GROWTH_RATIO
        if (!growsWidth && !growsHeight) return Rect(anchor)

        val layoutWidth = if (growsWidth) oval.width() else anchor.width()
        val layoutHeight = if (growsHeight) oval.height() else anchor.height()
        return centeredInside(effectiveBubble, layoutWidth, layoutHeight)
    }

    /**
     * Returns the full visible bubble only when the detector box and OCR anchor
     * agree. The result is page-clipped and never used to clip or shrink the
     * OCR text itself. Set [requireDistinctBubble] when the caller needs a real
     * detector bubble rather than the TextBlockObj's boundingBox fallback.
     */
    fun reliableBubbleBounds(
        bubble: Rect,
        anchor: Rect,
        pageBounds: Rect,
        requireDistinctBubble: Boolean = false
    ): Rect? {
        if (bubble.isEmpty || anchor.isEmpty || pageBounds.isEmpty) return null
        if (requireDistinctBubble && bubble == anchor) return null
        val clipped = Rect(bubble)
        if (!clipped.intersect(pageBounds) || clipped.isEmpty) return null

        val effectiveBubble = if (clipped.contains(anchor)) {
            clipped
        } else if (Rect.intersects(clipped, anchor)) {
            val interW = (minOf(clipped.right, anchor.right) - maxOf(clipped.left, anchor.left)).coerceAtLeast(0)
            val interH = (minOf(clipped.bottom, anchor.bottom) - maxOf(clipped.top, anchor.top)).coerceAtLeast(0)
            val anchorArea = (anchor.width().toLong() * anchor.height().toLong()).coerceAtLeast(1L)
            val interArea = interW.toLong() * interH.toLong()
            if (interArea >= anchorArea * 0.35f || clipped.contains(anchor.centerX(), anchor.centerY())) {
                Rect(clipped).apply { union(anchor) }
            } else {
                return null
            }
        } else {
            return null
        }
        if (requireDistinctBubble && effectiveBubble == anchor) return null
        return effectiveBubble
    }

    /**
     * Prepares a translated layout box without clipping it to the bubble.
     * Only page bounds may clip it. Recentering is only attempted when the
     * layout is already fully inside a reliable bubble; it is then a no-op and
     * cannot turn a fallback anchor into a smaller box.
     */
    fun prepareBubbleTextLayoutBox(
        layout: Rect,
        bubble: Rect,
        anchor: Rect,
        pageBounds: Rect
    ): Rect {
        val pageClipped = Rect(layout)
        if (!pageClipped.isEmpty && !pageBounds.isEmpty) {
            if (!pageClipped.intersect(pageBounds) || pageClipped.isEmpty) {
                pageClipped.set(layout)
            }
        }
        if (pageClipped.isEmpty) return pageClipped

        val reliableBubble = reliableBubbleBounds(bubble, anchor, pageBounds)
        return if (reliableBubble != null && reliableBubble.contains(pageClipped)) {
            recenterInside(pageClipped, reliableBubble)
        } else {
            pageClipped
        }
    }

    /**
     * Bounds of the 70% oval used to keep backgrounds and mask sources inside
     * the visible balloon. Text layout may intentionally exceed these bounds.
     */
    fun safeTextBounds(bubble: Rect): Rect? = safeOval(bubble)

    /**
     * Clips an arbitrary layout/background source to the safe oval bounds.
     * Returns null only when the bubble itself is unusable.
     */
    fun clampToSafeOval(box: Rect, bubble: Rect): Rect? {
        val oval = safeTextBounds(bubble) ?: return null
        return clipTo(box, oval)
    }

    /**
     * Clips [box] to [bounds] and centers the clipped result in [bounds] when
     * its size allows it. Already-contained boxes are returned unchanged; this
     * keeps a clamped layout from being stranded at the edge of a balloon or page.
     */
    fun recenterInside(box: Rect, bounds: Rect): Rect {
        val result = Rect(box)
        if (bounds.isEmpty) return result
        if (box.isEmpty) return Rect(bounds)
        if (!result.intersect(bounds)) return Rect(bounds)
        if (result.isEmpty) return Rect(bounds)
        if (result == box) return result
        if (result.width() > bounds.width() || result.height() > bounds.height()) return result

        result.offset(bounds.centerX() - result.centerX(), bounds.centerY() - result.centerY())
        if (result.left < bounds.left) result.offset(bounds.left - result.left, 0)
        if (result.top < bounds.top) result.offset(0, bounds.top - result.top)
        if (result.right > bounds.right) result.offset(bounds.right - result.right, 0)
        if (result.bottom > bounds.bottom) result.offset(0, bounds.bottom - result.bottom)
        return result
    }

    private fun centeredInside(bubble: Rect, width: Int, height: Int): Rect {
        val boundedWidth = width.coerceIn(1, bubble.width())
        val boundedHeight = height.coerceIn(1, bubble.height())
        val left = (bubble.centerX() - boundedWidth / 2)
            .coerceIn(bubble.left, bubble.right - boundedWidth)
        val top = (bubble.centerY() - boundedHeight / 2)
            .coerceIn(bubble.top, bubble.bottom - boundedHeight)
        return Rect(left, top, left + boundedWidth, top + boundedHeight)
    }

    private fun clipTo(box: Rect, bounds: Rect): Rect {
        val result = Rect(box)
        if (!result.intersect(bounds) || result.isEmpty) return Rect(bounds)
        return result
    }
}
