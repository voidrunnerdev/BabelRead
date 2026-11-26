package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `rounded rectangle shape is supported and spans full content width`() {
        val envelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 200f, 100f),
            horizontalInsetPx = 10f,
            verticalInsetPx = 5f,
            shape = EnvelopeShape(
                kind = EnvelopeShapeKind.ROUNDED_RECTANGLE,
                cornerRadiusPx = 12f
            )
        )

        assertEquals(EnvelopeShapeKind.ROUNDED_RECTANGLE, envelope.shape.kind)
        assertEquals(12f, envelope.shape.cornerRadiusPx, 0.01f)
        assertTrue(envelope.isSupportedShape)
        assertTrue(envelope.isUsable)
        assertEquals(90f, envelope.halfWidthAtY(50f), 0.01f)
        val band = envelope.spanAtBand(10f, 90f)
        assertEquals(10f, band.leftPx, 0.01f)
        assertEquals(190f, band.rightPx, 0.01f)

        val fit = BubbleTextLineFitter.fit(
            text = "Dikdörtgen balon metni",
            envelope = envelope,
            locale = java.util.Locale("tr", "TR"),
            minSp = 8f,
            maxSp = 20f,
            spToPx = { it },
            measurer = BubbleTextMeasurer(
                measureWidth = { s, sz -> s.length * sz * 0.55f },
                metricsForSize = { sz -> TextLineMetrics(-0.8f * sz, 0.2f * sz) }
            ),
            density = 1f
        )
        assertTrue(fit.fitsInside)
        assertEquals(null, fit.rejection)

        val unsupportedContour = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 200f, 100f),
            shape = EnvelopeShape(kind = EnvelopeShapeKind.CONTOUR_SCANLINE),
            contourProfile = null
        )
        assertFalse(unsupportedContour.isSupportedShape)
        assertEquals(0f, unsupportedContour.halfWidthAtY(50f), 0.01f)
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

    @Test
    fun `hourglass and bilobed snowman profiles are never classified rectangular`() {
        val rows = 40
        // Symmetric snowman/hourglass: wide top lobe, pinched waist, wide bottom lobe
        val lefts = FloatArray(rows)
        val rights = FloatArray(rows)
        for (r in 0 until rows) {
            val t = r.toFloat() / (rows - 1).toFloat()
            val halfW = when {
                t < 0.35f -> 0.40f
                t < 0.65f -> 0.24f // narrow waist
                else -> 0.42f
            }
            lefts[r] = 0.5f - halfW
            rights[r] = 0.5f + halfW
        }
        val hourglass = ContourScanlineProfile(lefts, rights)
        assertTrue(hourglass.isUsable)
        assertFalse("Hourglass profile must not be rectangular", hourglass.isRectangularLike)

        // Off-center pinched waist (narrowest at 32% height, wide at 12%/50%/88%)
        val offCenterLefts = FloatArray(rows) { r ->
            val t = r.toFloat() / (rows - 1).toFloat()
            if (t in 0.28f..0.38f) 0.26f else 0.08f
        }
        val offCenterRights = FloatArray(rows) { r ->
            val t = r.toFloat() / (rows - 1).toFloat()
            if (t in 0.28f..0.38f) 0.74f else 0.92f
        }
        assertFalse(
            "Off-center pinched bilobed profile must not be rectangular",
            ContourScanlineProfile(offCenterLefts, offCenterRights).isRectangularLike
        )

        // S-shifted bilobed profile (width stays constant, edges drift strongly)
        val shiftedLefts = FloatArray(rows) { r -> if (r < rows / 2) 0.06f else 0.24f }
        val shiftedRights = FloatArray(rows) { r -> if (r < rows / 2) 0.76f else 0.94f }
        assertFalse(
            "S-shifted bilobed profile must not be rectangular",
            ContourScanlineProfile(shiftedLefts, shiftedRights).isRectangularLike
        )

        // True rectangular narration box stays rectangular
        val rectLefts = FloatArray(rows) { r -> if (r == 0 || r == rows - 1) 0.12f else 0.06f }
        val rectRights = FloatArray(rows) { r -> if (r == 0 || r == rows - 1) 0.88f else 0.94f }
        assertTrue(ContourScanlineProfile(rectLefts, rectRights).isRectangularLike)
    }

    @Test
    fun `contour scanline applies horizontalInsetPx inside curved contour edges`() {
        val lefts = FloatArray(10) { 0.15f }
        val rights = FloatArray(10) { 0.85f }
        val profile = ContourScanlineProfile(lefts, rights)
        val envelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 200f, 100f),
            horizontalInsetPx = 6f,
            verticalInsetPx = 4f,
            shape = EnvelopeShape(kind = EnvelopeShapeKind.CONTOUR_SCANLINE),
            contourProfile = profile
        )
        val span = envelope.spanAtBand(30f, 60f)
        // 0.15 * 200 + 6 = 36, 0.85 * 200 - 6 = 164
        assertEquals(36f, span.leftPx, 0.01f)
        assertEquals(164f, span.rightPx, 0.01f)
    }
}
