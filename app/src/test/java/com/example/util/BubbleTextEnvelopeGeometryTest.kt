package com.example.util

import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BubbleTextEnvelopeGeometryTest {

    @Test
    fun `off-center target box cannot shrink the real bubble envelope`() {
        val page = Rect(0, 0, 400, 300)
        val bubble = Rect(20, 30, 340, 230)
        val offCenterTarget = Rect(30, 180, 120, 220)
        val reliableBubble = BubbleLayoutGeometry.reliableBubbleBounds(
            bubble = bubble,
            anchor = offCenterTarget,
            pageBounds = page,
            requireDistinctBubble = true
        )!!
        val paddingPx = 6f
        val envelope = BubbleTextLayoutEngine.envelopeForBubble(
            bubbleBounds = RectF(
                0f,
                0f,
                reliableBubble.width().toFloat(),
                reliableBubble.height().toFloat()
            ),
            horizontalPaddingDp = paddingPx,
            verticalPaddingDp = paddingPx,
            density = 1f
        )!!
        assertEquals(
            BubbleTextEnvelopeGeometry.forBubble(
                bubbleWidthPx = reliableBubble.width().toFloat(),
                bubbleHeightPx = reliableBubble.height().toFloat(),
                horizontalPaddingPx = paddingPx,
                verticalPaddingPx = paddingPx,
                safeInsetPx = 2f
            ),
            envelope
        )

        val fit = BubbleTextLineFitter.fit(
            text = "A wide translated line",
            envelope = envelope,
            locale = java.util.Locale.ENGLISH,
            minSp = 8f,
            maxSp = 24f,
            spToPx = { sp -> sp },
            measurer = BubbleTextMeasurer(
                measureWidth = { text, textSizePx -> text.length * textSizePx * 0.55f },
                metricsForSize = { textSizePx ->
                    TextLineMetrics(-0.8f * textSizePx, 0.2f * textSizePx)
                }
            ),
            density = 1f
        )

        assertTrue(fit.fitsInside)
        assertEquals(bubble.exactCenterX(), reliableBubble.left + paddingPx + envelope.centerX, 0.5f)
        val line = fit.layout!!.lines.single()
        assertEquals(envelope.centerX, line.leftPx + line.widthPx / 2f, 0.5f)
        assertTrue(line.leftPx >= envelope.contentLeft)
        assertTrue(line.leftPx + line.widthPx <= envelope.contentRight)
        assertTrue(line.widthPx <= envelope.widthAtBand(
            line.topPx,
            line.topPx + line.heightPx
        ) * 0.985f + 0.5f)
        assertTrue(fit.maxLineWidthPx > offCenterTarget.width().toFloat() - paddingPx * 2f)
    }
}
