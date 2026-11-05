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
}
