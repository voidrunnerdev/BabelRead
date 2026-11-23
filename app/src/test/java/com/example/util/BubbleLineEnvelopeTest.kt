package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleLineEnvelopeTest {

    @Test
    fun `ellipse half width is largest at the vertical center`() {
        val envelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 200f, 100f),
            horizontalInsetPx = 10f,
            verticalInsetPx = 5f
        )

        assertEquals(90f, envelope.halfWidthAtY(50f), 0.01f)
        assertEquals(0f, envelope.halfWidthAtY(5f), 0.01f)
        assertEquals(0f, envelope.halfWidthAtY(95f), 0.01f)
        assertTrue(envelope.halfWidthAtBand(45f, 50f) < envelope.halfWidthAtY(50f))
    }

    @Test
    fun `line band uses the narrowest sampled position`() {
        val envelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 200f, 100f)
        )

        val bandWidth = envelope.halfWidthAtBand(10f, 40f)

        assertEquals(envelope.halfWidthAtY(10f), bandWidth, 0.01f)
        assertTrue(bandWidth < envelope.halfWidthAtBand(40f, 60f))
    }

    @Test
    fun `non ellipse shapes remain metadata only`() {
        val envelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 200f, 100f),
            shape = EnvelopeShape(
                kind = EnvelopeShapeKind.ROUNDED_RECTANGLE,
                cornerRadiusPx = 12f
            )
        )

        assertEquals(EnvelopeShapeKind.ROUNDED_RECTANGLE, envelope.shape.kind)
        assertEquals(12f, envelope.shape.cornerRadiusPx, 0.01f)
        assertEquals(0f, envelope.halfWidthAtY(50f), 0.01f)
    }

    @Test
    fun `contour scanline envelope supports asymmetric spans and vertical chamber bounds`() {
        val lefts = FloatArray(10) { row -> if (row in 2..7) 0.30f else 0.50f }
        val rights = FloatArray(10) { row -> if (row in 2..7) 0.90f else 0.50f }
        val profile = ContourScanlineProfile(lefts, rights)
        val envelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 200f, 100f),
            shape = EnvelopeShape(kind = EnvelopeShapeKind.CONTOUR_SCANLINE),
            contourProfile = profile
        )

        assertTrue(envelope.isSupportedShape)
        assertTrue(envelope.isUsable)
        assertEquals(20f, envelope.contentTop, 0.01f)
        assertEquals(80f, envelope.contentBottom, 0.01f)
        val span = envelope.spanAtBand(30f, 50f)
        assertEquals(60f, span.leftPx, 0.01f)
        assertEquals(180f, span.rightPx, 0.01f)
        assertEquals(120f, span.centerX, 0.01f)
    }

    @Test
    fun `contour scanline safely ignores NaN row fractions`() {
        val lefts = floatArrayOf(0.1f, Float.NaN, 0.1f)
        val rights = floatArrayOf(0.9f, 0.8f, 0.9f)
        val profile = ContourScanlineProfile(lefts, rights)
        val envelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 200f, 90f),
            shape = EnvelopeShape(kind = EnvelopeShapeKind.CONTOUR_SCANLINE),
            contourProfile = profile
        )
        val midSpan = envelope.spanAtY(45f)
        assertTrue(midSpan.leftPx.isFinite())
        assertTrue(midSpan.rightPx.isFinite())
        assertEquals(0f, midSpan.widthPx, 0.01f)
    }
}
