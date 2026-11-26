package com.example.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleContourExtractorTest {

    @Test
    fun `extracts wide rectangular narration box contour`() {
        val w = 32
        val h = 32
        val luma = IntArray(w * h) { 20 } // dark border
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                luma[y * w + x] = 250 // clean white interior
            }
        }

        val profile = BubbleContourExtractor.extractFromLuminanceGrid(luma, w, h)
        assertNotNull(profile)
        // Near top row (y=4) of a rectangular box should still be wide (> 70% width),
        // unlike an ellipse which collapses near poles.
        val topSpan = profile!!.rowRightFractions[4] - profile.rowLeftFractions[4]
        val midSpan = profile.rowRightFractions[16] - profile.rowLeftFractions[16]
        assertTrue("Expected wide top span, got $topSpan", topSpan > 0.70f)
        assertTrue("Expected wide mid span, got $midSpan", midSpan > 0.70f)
    }

    @Test
    fun `extracts asymmetric chamber shifted to the right`() {
        val w = 36
        val h = 24
        val luma = IntArray(w * h) { 15 }
        // Bubble chamber occupies x in [12..33], y in [2..21]
        for (y in 2 until 22) {
            for (x in 12 until 34) {
                luma[y * w + x] = 248
            }
        }

        val profile = BubbleContourExtractor.extractFromLuminanceGrid(
            luma = luma,
            gridW = w,
            gridH = h,
            seedX = 22,
            seedY = 12
        )
        assertNotNull(profile)
        val midCenter = (profile!!.rowLeftFractions[12] + profile.rowRightFractions[12]) / 2f
        assertTrue("Expected right-shifted center > 0.58, got $midCenter", midCenter > 0.58f)
    }

    @Test
    fun `extracts dark speech bubble with white outer border`() {
        val w = 30
        val h = 30
        val luma = IntArray(w * h) { 245 } // white page background
        for (y in 3 until h - 3) {
            for (x in 3 until w - 3) {
                luma[y * w + x] = 18 // dark bubble interior
            }
        }

        val profile = BubbleContourExtractor.extractFromLuminanceGrid(
            luma = luma,
            gridW = w,
            gridH = h,
            isDarkBubble = true
        )
        assertNotNull(profile)
        val midSpan = profile!!.rowRightFractions[15] - profile.rowLeftFractions[15]
        assertTrue("Expected dark bubble interior span > 0.60, got $midSpan", midSpan > 0.60f)
    }

    @Test
    fun `bridges internal text strokes inside white bubble`() {
        val w = 32
        val h = 32
        val luma = IntArray(w * h) { 20 }
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                luma[y * w + x] = 250
            }
        }
        // Simulate a dark glyph stroke inside the middle of the bubble
        for (y in 12..18) {
            luma[y * w + 16] = 25
            luma[y * w + 17] = 25
        }

        val profile = BubbleContourExtractor.extractFromLuminanceGrid(luma, w, h, seedX = 16, seedY = 15)
        assertNotNull(profile)
        val midSpan = profile!!.rowRightFractions[15] - profile.rowLeftFractions[15]
        assertTrue("Expected bridged span > 0.70, got $midSpan", midSpan > 0.70f)
    }

    @Test
    fun `returns null on tiny or noisy unusable interior`() {
        val w = 24
        val h = 24
        val luma = IntArray(w * h) { 20 }
        // Only a tiny 3x3 white speck in center
        for (y in 11..13) {
            for (x in 11..13) {
                luma[y * w + x] = 250
            }
        }
        assertNull(BubbleContourExtractor.extractFromLuminanceGrid(luma, w, h))
    }

    @Test
    fun `keeps only contiguous main chamber without internal zero-width gaps`() {
        val w = 32
        val h = 32
        val luma = IntArray(w * h) { 20 }
        // Main chamber y in [6..28], plus a disconnected tiny slit at y in [2..3]
        for (y in 6..28) {
            for (x in 3..28) luma[y * w + x] = 250
        }
        for (y in 2..3) {
            for (x in 10..22) luma[y * w + x] = 250
        }

        val profile = BubbleContourExtractor.extractFromLuminanceGrid(luma, w, h, seedX = 16, seedY = 16)
        assertNotNull(profile)
        for (r in profile!!.firstValidRow..profile.lastValidRow) {
            assertTrue(
                "Row $r inside valid range must have positive span",
                profile.rowRightFractions[r] > profile.rowLeftFractions[r]
            )
        }
    }

    @Test
    fun `dense un-erased text inside anchorBox does not trap contour in a side pocket`() {
        val w = 36
        val h = 36
        val luma = IntArray(w * h) { 20 }
        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) luma[y * w + x] = 250
        }
        // Heavy dark text block covering the right half of the interior (x in 14..30, y in 10..26)
        for (y in 10..26) {
            for (x in 14..30) luma[y * w + x] = 25
        }

        val profile = BubbleContourExtractor.extractFromLuminanceGrid(
            luma = luma,
            gridW = w,
            gridH = h,
            seedX = 18,
            seedY = 18,
            anchorLeft = 6,
            anchorTop = 8,
            anchorRight = 30,
            anchorBottom = 28
        )
        assertNotNull(profile)
        val midSpan = profile!!.rowRightFractions[18] - profile.rowLeftFractions[18]
        assertTrue("Expected full interior width > 0.70 despite residual glyphs, got $midSpan", midSpan > 0.70f)
    }

    @Test
    fun `euclidean distance transform computes exact peak distance at chamber center`() {
        val w = 20
        val h = 20
        val mask = BooleanArray(w * h) { false }
        for (y in 2 until 18) {
            for (x in 2 until 18) mask[y * w + x] = true
        }
        val dist = BubbleContourExtractor.computeEuclideanDistanceTransform(mask, w, h)
        // Center (10, 10) is 9 cells from exterior x=1 / y=1 and 8 cells from x=18 / y=18 -> exact 8.0f
        assertTrue("Expected center distance ~8.0, got ${dist[10 * w + 10]}", kotlin.math.abs(dist[10 * w + 10] - 8.0f) < 0.01f)
        // Corner interior pixel (2, 2) is 1 cell from (1, 2) -> exact 1.0f
        assertTrue("Expected edge distance ~1.0, got ${dist[2 * w + 2]}", kotlin.math.abs(dist[2 * w + 2] - 1.0f) < 0.01f)
    }

    @Test
    fun `tight oval bubble preserves curved corners even when anchorBox covers most of the bubble`() {
        val w = 36
        val h = 36
        val luma = IntArray(w * h) { 20 }
        val cx = w / 2f
        val cy = h / 2f
        val rx = 15f
        val ry = 15f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                if (dx * dx + dy * dy <= 1f) luma[y * w + x] = 250
            }
        }
        val profile = BubbleContourExtractor.extractFromLuminanceGrid(
            luma = luma,
            gridW = w,
            gridH = h,
            anchorLeft = 2,
            anchorTop = 2,
            anchorRight = w - 3,
            anchorBottom = h - 3
        )
        assertNotNull(profile)
        assertTrue("Tight oval should not be flattened into a rectangle", !profile!!.isRectangularLike)
    }

    @Test
    fun `rejects borderless white-on-white leak instead of misclassifying as rectangle`() {
        val w = 32
        val h = 32
        val luma = IntArray(w * h) { 250 } // no dark border at all (leaked to outer perimeter)
        assertNull(BubbleContourExtractor.extractFromLuminanceGrid(luma, w, h))
    }

    @Test
    fun `round white bubble on white page does not leak when anchor corners touch outside outline`() {
        val w = 36
        val h = 36
        val luma = IntArray(w * h) { 250 } // white page outside
        val cx = w / 2f
        val cy = h / 2f
        val rOuter = 15.5f
        val rInner = 13.5f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d in rInner..rOuter) {
                    luma[y * w + x] = 20 // inked round bubble outline
                }
            }
        }
        // Rectangular anchor covering ~56% of area (<= 70%) whose 4 corners sit outside the circle
        val profile = BubbleContourExtractor.extractFromLuminanceGrid(
            luma = luma,
            gridW = w,
            gridH = h,
            anchorLeft = 5,
            anchorTop = 5,
            anchorRight = 30,
            anchorBottom = 30
        )
        assertNotNull("Round bubble must not return null from anchor corner leak", profile)
        assertFalse(profile!!.isRectangularLike)
    }

    @Test
    fun `colored medium-dark bubble with isDarkBubble extracts usable contour`() {
        val w = 32
        val h = 32
        val luma = IntArray(w * h) { 250 } // white outer page/border
        val cx = w / 2f
        val cy = h / 2f
        val rx = 13f
        val ry = 13f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                if (dx * dx + dy * dy <= 1f) {
                    luma[y * w + x] = 152 // purple/translucent colored bubble interior
                }
            }
        }
        val profile = BubbleContourExtractor.extractFromLuminanceGrid(
            luma = luma,
            gridW = w,
            gridH = h,
            isDarkBubble = true
        )
        assertNotNull("Medium-dark/colored bubble must extract usable contour", profile)
        assertFalse(profile!!.isRectangularLike)
    }

    @Test
    fun `round bubble inside tail-padded bounding box extracts usable contour`() {
        val w = 32
        val h = 44
        val luma = IntArray(w * h) { 250 }
        val cx = w / 2f
        val cy = 12f
        val rOuter = 10.5f
        val rInner = 8.5f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d in rInner..rOuter) luma[y * w + x] = 20
            }
        }
        val profile = BubbleContourExtractor.extractFromLuminanceGrid(
            luma = luma,
            gridW = w,
            gridH = h,
            seedX = 16,
            seedY = 12
        )
        assertNotNull("Round bubble with extra tail height must still extract contour", profile)
        assertFalse(profile!!.isRectangularLike)
    }
}
