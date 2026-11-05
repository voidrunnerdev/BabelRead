package com.example.util

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BubbleLayoutGeometryTest {

    @Test
    fun `one-axis growth must not shrink the anchor on the other axis`() {
        val bubble = Rect(0, 0, 300, 100)
        val anchor = Rect(100, 10, 200, 90)

        val result = BubbleLayoutGeometry.centeredLayoutBox(anchor, bubble)!!

        assertEquals(Rect(45, 10, 255, 90), result)
        assertTrue(result.width() > anchor.width())
        assertEquals(anchor.height(), result.height())
        assertTrue(bubble.contains(result))
    }

    @Test
    fun `vertical-only growth keeps the anchor width`() {
        val bubble = Rect(0, 0, 100, 300)
        val anchor = Rect(16, 100, 84, 200)

        val result = BubbleLayoutGeometry.centeredLayoutBox(anchor, bubble)!!

        assertEquals(Rect(16, 45, 84, 255), result)
        assertEquals(anchor.width(), result.width())
        assertTrue(result.height() > anchor.height())
        assertTrue(bubble.contains(result))
    }

    @Test
    fun `no meaningful growth keeps the ocr anchor`() {
        val bubble = Rect(0, 0, 200, 200)
        val anchor = Rect(35, 35, 165, 165)

        assertEquals(anchor, BubbleLayoutGeometry.centeredLayoutBox(anchor, bubble))
    }

    @Test
    fun `thin bubble grows width without shrinking the anchor height`() {
        val bubble = Rect(0, 0, 200, 20)
        val anchor = Rect(50, 0, 150, 20)

        val result = BubbleLayoutGeometry.centeredLayoutBox(anchor, bubble)!!

        assertEquals(Rect(30, 0, 170, 20), result)
        assertTrue(result.width() >= anchor.width())
        assertEquals(anchor.height(), result.height())
        assertTrue(bubble.contains(result))
    }

    @Test
    fun `partially outside anchor is rejected instead of clipped`() {
        val bubble = Rect(0, 0, 100, 100)
        val anchor = Rect(90, 90, 150, 150)

        assertNull(BubbleLayoutGeometry.centeredLayoutBox(anchor, bubble))
    }

    @Test
    fun `contained anchor keeps its exact size at the bubble edge`() {
        val bubble = Rect(0, 0, 100, 100)
        val anchor = Rect(0, 0, 100, 100)

        assertEquals(anchor, BubbleLayoutGeometry.centeredLayoutBox(anchor, bubble))
    }

    @Test
    fun `reliable bubble is page clipped and requires the ocr anchor inside`() {
        val page = Rect(0, 0, 400, 300)
        val bubble = Rect(20, 30, 450, 280)
        val anchor = Rect(100, 100, 160, 160)

        assertEquals(
            Rect(20, 30, 400, 280),
            BubbleLayoutGeometry.reliableBubbleBounds(bubble, anchor, page)
        )
        assertNull(
            BubbleLayoutGeometry.reliableBubbleBounds(
                Rect(20, 30, 120, 130),
                Rect(150, 150, 200, 200),
                page
            )
        )
        assertEquals(
            Rect(100, 100, 160, 160),
            BubbleLayoutGeometry.reliableBubbleBounds(
                Rect(100, 100, 160, 160),
                Rect(100, 100, 160, 160),
                page
            )
        )
        assertNull(
            BubbleLayoutGeometry.reliableBubbleBounds(
                Rect(100, 100, 160, 160),
                Rect(100, 100, 160, 160),
                page,
                requireDistinctBubble = true
            )
        )
    }

    @Test
    fun `partially covered anchor is rejected for p1 and expansion`() {
        val bubble = Rect(0, 0, 100, 100)
        val anchor = Rect(70, 70, 110, 90)
        val page = Rect(0, 0, 200, 200)

        assertNull(
            BubbleLayoutGeometry.reliableBubbleBounds(bubble, anchor, page)
        )
        assertNull(
            BubbleLayoutGeometry.reliableBubbleBounds(
                bubble,
                anchor,
                page,
                requireDistinctBubble = true
            )
        )
    }

    @Test
    fun `page clipped bubble equal to anchor is rejected for p1`() {
        val page = Rect(0, 0, 100, 100)
        val bubble = Rect(0, 0, 120, 100)
        val anchor = Rect(0, 0, 100, 100)

        assertEquals(
            anchor,
            BubbleLayoutGeometry.reliableBubbleBounds(bubble, anchor, page)
        )
        assertNull(
            BubbleLayoutGeometry.reliableBubbleBounds(
                bubble,
                anchor,
                page,
                requireDistinctBubble = true
            )
        )
    }

    @Test
    fun `fallback layout partially outside the bubble is not shrunk by prep`() {
        val page = Rect(0, 0, 200, 200)
        val bubble = Rect(0, 0, 100, 100)
        val fallback = Rect(90, 90, 150, 150)

        assertEquals(
            fallback,
            BubbleLayoutGeometry.prepareBubbleTextLayoutBox(
                layout = fallback,
                bubble = bubble,
                anchor = fallback,
                pageBounds = page
            )
        )
    }

    @Test
    fun `disjoint fallback is not replaced by the bubble bounds`() {
        val page = Rect(0, 0, 200, 200)
        val bubble = Rect(0, 0, 100, 100)
        val fallback = Rect(120, 120, 180, 180)

        assertEquals(
            fallback,
            BubbleLayoutGeometry.prepareBubbleTextLayoutBox(
                layout = fallback,
                bubble = bubble,
                anchor = Rect(30, 30, 70, 70),
                pageBounds = page
            )
        )
    }

    @Test
    fun `anchor outside the bubble is rejected`() {
        val bubble = Rect(0, 0, 100, 100)
        val anchor = Rect(150, 150, 200, 200)

        assertNull(BubbleLayoutGeometry.centeredLayoutBox(anchor, bubble))
    }

    @Test
    fun `safe text bounds expose the background cap`() {
        assertEquals(
            Rect(45, 15, 255, 85),
            BubbleLayoutGeometry.safeTextBounds(Rect(0, 0, 300, 100))
        )
    }

    @Test
    fun `background source for a grown text box still clamps to the safe oval`() {
        val bubble = Rect(0, 0, 300, 100)
        val anchor = Rect(100, 10, 200, 90)
        val grown = BubbleLayoutGeometry.centeredLayoutBox(anchor, bubble)!!

        val background = BubbleLayoutGeometry.clampToSafeOval(grown, bubble)!!

        assertEquals(Rect(45, 15, 255, 85), background)
        assertTrue(background.height() < grown.height())
        assertTrue(bubble.contains(background))
    }

    @Test
    fun `background source is clipped to the safe oval`() {
        val bubble = Rect(0, 0, 100, 100)
        val staleLayout = Rect(0, 0, 100, 100)

        val result = BubbleLayoutGeometry.clampToSafeOval(staleLayout, bubble)!!

        assertEquals(Rect(15, 15, 85, 85), result)
        assertTrue(bubble.contains(result))
    }

    @Test
    fun `disjoint background source is replaced by the safe oval`() {
        val bubble = Rect(0, 0, 100, 100)
        val staleLayout = Rect(300, 300, 380, 360)

        val result = BubbleLayoutGeometry.clampToSafeOval(staleLayout, bubble)!!

        assertEquals(Rect(15, 15, 85, 85), result)
    }

    @Test
    fun `recenter clips and centers a stranded box inside bounds`() {
        val bounds = Rect(100, 100, 300, 300)
        val stranded = Rect(90, 120, 190, 220)

        assertEquals(Rect(155, 150, 245, 250), BubbleLayoutGeometry.recenterInside(stranded, bounds))
    }

    @Test
    fun `recenter leaves an already-contained box untouched`() {
        val bounds = Rect(100, 100, 300, 300)
        val contained = Rect(120, 140, 180, 200)

        assertEquals(contained, BubbleLayoutGeometry.recenterInside(contained, bounds))
    }

    @Test
    fun `recenter maps empty and disjoint boxes to the full bounds`() {
        val bounds = Rect(100, 100, 300, 300)
        val empty = Rect(150, 150, 150, 180)
        val disjoint = Rect(400, 400, 450, 450)

        assertEquals(bounds, BubbleLayoutGeometry.recenterInside(empty, bounds))
        assertEquals(bounds, BubbleLayoutGeometry.recenterInside(disjoint, bounds))
    }

    @Test
    fun `recenter never returns a box outside bounds`() {
        val bounds = Rect(0, 0, 100, 100)
        val oversized = Rect(-50, -50, 150, 150)

        assertEquals(bounds, BubbleLayoutGeometry.recenterInside(oversized, bounds))
    }
}
