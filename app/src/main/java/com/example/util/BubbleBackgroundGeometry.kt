package com.example.util

data class OverlayBackgroundBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun contains(other: OverlayBackgroundBounds): Boolean =
        !isEmpty && !other.isEmpty &&
            left <= other.left &&
            top <= other.top &&
            right >= other.right &&
            bottom >= other.bottom
}

object BubbleBackgroundGeometry {

    fun safeOvalForBackground(
        reliableBubble: OverlayBackgroundBounds?,
        anchor: OverlayBackgroundBounds?,
        preparedLayout: OverlayBackgroundBounds?,
        placed: OverlayBackgroundBounds,
        safeOval: OverlayBackgroundBounds?
    ): OverlayBackgroundBounds? {
        if (reliableBubble == null || anchor == null || preparedLayout == null ||
            safeOval == null || safeOval.isEmpty
        ) {
            return null
        }
        if (reliableBubble == anchor) return null
        if (!reliableBubble.contains(anchor)) return null
        if (!reliableBubble.contains(preparedLayout)) return null
        if (!reliableBubble.contains(placed)) return null
        return safeOval
    }

    fun resolveSource(
        placed: OverlayBackgroundBounds,
        source: OverlayBackgroundBounds? = null,
        safe: OverlayBackgroundBounds? = null
    ): OverlayBackgroundBounds {
        if (placed.isEmpty) return placed

        val merged = if (source == null || source.isEmpty) {
            placed
        } else {
            OverlayBackgroundBounds(
                left = minOf(placed.left, source.left),
                top = minOf(placed.top, source.top),
                right = maxOf(placed.right, source.right),
                bottom = maxOf(placed.bottom, source.bottom)
            )
        }
        if (safe == null || safe.isEmpty) return merged

        val safeResult = OverlayBackgroundBounds(
            left = maxOf(merged.left, safe.left),
            top = maxOf(merged.top, safe.top),
            right = minOf(merged.right, safe.right),
            bottom = minOf(merged.bottom, safe.bottom)
        )
        return if (safeResult.isEmpty || !safeResult.contains(placed)) merged else safeResult
    }
}
