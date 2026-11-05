package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutPolicyTest {

    @Test
    fun `subtitle expands vertically only when required`() {
        assertEquals(
            OverlayLayoutPolicy.VerticalBounds(50f, 40f),
            OverlayLayoutPolicy.expandSubtitleToRequiredHeight(
                top = 50f,
                height = 40f,
                requiredHeight = 30f,
                pageHeight = 200f
            )
        )
        assertEquals(
            OverlayLayoutPolicy.VerticalBounds(40f, 60f),
            OverlayLayoutPolicy.expandSubtitleToRequiredHeight(
                top = 50f,
                height = 40f,
                requiredHeight = 60f,
                pageHeight = 200f
            )
        )
    }

    @Test
    fun `no-expansion subtitle still stays inside the page`() {
        assertEquals(
            OverlayLayoutPolicy.VerticalBounds(60f, 40f),
            OverlayLayoutPolicy.expandSubtitleToRequiredHeight(
                top = 190f,
                height = 40f,
                requiredHeight = 30f,
                pageHeight = 100f
            )
        )
    }

    @Test
    fun `subtitle fit prefers larger hyphenated text over the minimum`() {
        assertEquals(
            OverlayLayoutPolicy.SubtitleFitChoice.HYPHENATED,
            OverlayLayoutPolicy.chooseSubtitleFit(
                original = OverlayLayoutPolicy.SubtitleFit(fitsInside = false, fontSizeSp = 6f),
                hyphenated = OverlayLayoutPolicy.SubtitleFit(fitsInside = true, fontSizeSp = 8f),
                minimum = OverlayLayoutPolicy.SubtitleFit(fitsInside = false, fontSizeSp = 6f)
            )
        )
        assertEquals(
            OverlayLayoutPolicy.SubtitleFitChoice.MINIMUM,
            OverlayLayoutPolicy.chooseSubtitleFit(
                original = OverlayLayoutPolicy.SubtitleFit(fitsInside = false, fontSizeSp = 10f),
                hyphenated = OverlayLayoutPolicy.SubtitleFit(fitsInside = false, fontSizeSp = 8f),
                minimum = OverlayLayoutPolicy.SubtitleFit(fitsInside = true, fontSizeSp = 6f)
            )
        )
        assertEquals(
            OverlayLayoutPolicy.SubtitleFitChoice.FALLBACK,
            OverlayLayoutPolicy.chooseSubtitleFit(
                original = OverlayLayoutPolicy.SubtitleFit(fitsInside = false, fontSizeSp = 10f),
                hyphenated = OverlayLayoutPolicy.SubtitleFit(fitsInside = true, fontSizeSp = 7f),
                minimum = OverlayLayoutPolicy.SubtitleFit(fitsInside = false, fontSizeSp = 6f)
            )
        )
    }

    @Test
    fun `subtitle retries at the readable minimum including 6sp`() {
        assertTrue(OverlayLayoutPolicy.shouldShrinkSubtitleToMinimum(fitsInside = false, fontSizeSp = 8f))
        assertTrue(OverlayLayoutPolicy.shouldShrinkSubtitleToMinimum(fitsInside = false, fontSizeSp = 6f))
        assertFalse(OverlayLayoutPolicy.shouldShrinkSubtitleToMinimum(fitsInside = true, fontSizeSp = 8f))
        assertFalse(OverlayLayoutPolicy.shouldShrinkSubtitleToMinimum(fitsInside = false, fontSizeSp = 5.9f))
    }

    @Test
    fun `no background is drawn without renderable text`() {
        assertFalse(
            OverlayLayoutPolicy.shouldDrawBackground(
                hasText = false,
                hasLayout = false,
                fitsInside = true,
                forceTextToFitBox = false
            )
        )
        assertFalse(
            OverlayLayoutPolicy.shouldDrawBackground(
                hasText = true,
                hasLayout = false,
                fitsInside = true,
                forceTextToFitBox = false
            )
        )
        assertFalse(
            OverlayLayoutPolicy.shouldDrawBackground(
                hasText = true,
                hasLayout = true,
                fitsInside = false,
                forceTextToFitBox = false
            )
        )
        assertTrue(
            OverlayLayoutPolicy.shouldDrawBackground(
                hasText = true,
                hasLayout = true,
                fitsInside = true,
                forceTextToFitBox = false
            )
        )
        assertTrue(
            OverlayLayoutPolicy.shouldDrawBackground(
                hasText = true,
                hasLayout = true,
                fitsInside = false,
                forceTextToFitBox = true
            )
        )
    }

    @Test
    fun `forced draw requires a readable size`() {
        assertTrue(OverlayLayoutPolicy.canForceTextToFit(fontSizeSp = 6f, drawScale = 0.7f))
        assertFalse(OverlayLayoutPolicy.canForceTextToFit(fontSizeSp = 5.9f, drawScale = 0.8f))
        assertFalse(OverlayLayoutPolicy.canForceTextToFit(fontSizeSp = 10f, drawScale = 0.69f))
    }
}
