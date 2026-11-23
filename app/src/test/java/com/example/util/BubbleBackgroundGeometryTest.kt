package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleBackgroundGeometryTest {

    @Test
    fun `fallback with bubble equal to anchor does not qualify for safe background`() {
        val fallback = OverlayBackgroundBounds(0f, 0f, 100f, 100f)
        val safeOval = OverlayBackgroundBounds(15f, 15f, 85f, 85f)

        val safeBackground = BubbleBackgroundGeometry.safeOvalForBackground(
            reliableBubble = fallback,
            anchor = fallback,
            preparedLayout = fallback,
            placed = fallback,
            safeOval = safeOval
        )

        assertNull(safeBackground)
        assertEquals(
            fallback,
            BubbleBackgroundGeometry.resolveSource(
                placed = fallback,
                source = fallback,
                safe = safeBackground
            )
        )
    }

    @Test
    fun `page clipped bubble equal to anchor does not qualify for safe background`() {
        val pageClippedBubble = OverlayBackgroundBounds(0f, 0f, 100f, 100f)
        val anchor = OverlayBackgroundBounds(0f, 0f, 100f, 100f)
        val safeOval = OverlayBackgroundBounds(15f, 15f, 85f, 85f)

        val safeBackground = BubbleBackgroundGeometry.safeOvalForBackground(
            reliableBubble = pageClippedBubble,
            anchor = anchor,
            preparedLayout = anchor,
            placed = anchor,
            safeOval = safeOval
        )

        assertNull(safeBackground)
        assertEquals(
            anchor,
            BubbleBackgroundGeometry.resolveSource(
                placed = anchor,
                source = anchor,
                safe = safeBackground
            )
        )
    }

    @Test
    fun `fallback layout outside the reliable bubble does not qualify for safe background`() {
        val reliableBubble = OverlayBackgroundBounds(0f, 0f, 100f, 100f)
        val anchor = OverlayBackgroundBounds(20f, 20f, 80f, 80f)
        val fallback = OverlayBackgroundBounds(90f, 90f, 150f, 150f)
        val safeOval = OverlayBackgroundBounds(15f, 15f, 85f, 85f)

        val safeBackground = BubbleBackgroundGeometry.safeOvalForBackground(
            reliableBubble = reliableBubble,
            anchor = anchor,
            preparedLayout = fallback,
            placed = fallback,
            safeOval = safeOval
        )

        assertNull(safeBackground)
        val background = BubbleBackgroundGeometry.resolveSource(
            placed = fallback,
            source = fallback,
            safe = safeBackground
        )
        assertEquals(fallback, background)
    }

    @Test
    fun `safe background is allowed when reliable bubble covers the fallback`() {
        val reliableBubble = OverlayBackgroundBounds(0f, 0f, 100f, 100f)
        val fallback = OverlayBackgroundBounds(20f, 20f, 80f, 80f)
        val safeOval = OverlayBackgroundBounds(15f, 15f, 85f, 85f)

        val safeBackground = BubbleBackgroundGeometry.safeOvalForBackground(
            reliableBubble = reliableBubble,
            anchor = fallback,
            preparedLayout = fallback,
            placed = fallback,
            safeOval = safeOval
        )

        assertEquals(safeOval, safeBackground)
    }

    @Test
    fun `partially outside fallback layout keeps a background over its text`() {
        val bubble = OverlayBackgroundBounds(0f, 0f, 100f, 100f)
        val fallback = OverlayBackgroundBounds(80f, 20f, 140f, 80f)

        val background = BubbleBackgroundGeometry.resolveSource(
            placed = fallback,
            source = fallback,
            safe = null
        )

        assertTrue(!bubble.contains(fallback))
        assertTrue(background.contains(fallback))
        assertEquals(fallback, background)
    }

    @Test
    fun `fully disjoint fallback layout keeps a background over its text`() {
        val bubble = OverlayBackgroundBounds(0f, 0f, 100f, 100f)
        val fallback = OverlayBackgroundBounds(120f, 120f, 180f, 180f)

        val background = BubbleBackgroundGeometry.resolveSource(
            placed = fallback,
            source = fallback,
            safe = null
        )

        assertTrue(!bubble.contains(fallback))
        assertTrue(background.contains(fallback))
        assertEquals(fallback, background)
    }

    @Test
    fun `safe oval that misses the placed box falls back to the merged source`() {
        val placed = OverlayBackgroundBounds(50f, 50f, 110f, 100f)
        val source = OverlayBackgroundBounds(40f, 40f, 120f, 90f)
        val safeOval = OverlayBackgroundBounds(15f, 15f, 85f, 85f)

        val background = BubbleBackgroundGeometry.resolveSource(
            placed = placed,
            source = source,
            safe = safeOval
        )

        assertEquals(OverlayBackgroundBounds(40f, 40f, 120f, 100f), background)
        assertTrue(background.contains(placed))
        assertTrue(background.contains(source))
    }

    @Test
    fun `safe oval is kept when it covers the placed box`() {
        val placed = OverlayBackgroundBounds(30f, 30f, 70f, 70f)
        val safeOval = OverlayBackgroundBounds(15f, 15f, 85f, 85f)

        val background = BubbleBackgroundGeometry.resolveSource(
            placed = placed,
            source = placed,
            safe = safeOval
        )

        assertEquals(placed, background)
        assertTrue(safeOval.contains(background))
    }
}
