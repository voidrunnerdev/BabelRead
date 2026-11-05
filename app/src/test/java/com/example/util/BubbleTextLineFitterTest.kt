package com.example.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleTextLineFitterTest {

    private val envelope = BubbleLineEnvelope(
        bounds = EnvelopeBounds(0f, 0f, 300f, 200f),
        horizontalInsetPx = 4f,
        verticalInsetPx = 4f
    )

    private fun fit(
        text: String,
        targetEnvelope: BubbleLineEnvelope = envelope,
        minSp: Float = 8f,
        maxSp: Float = 24f,
        allowBrokenWords: Boolean = false,
        locale: Locale = Locale.ENGLISH
    ): BubbleTextFitResult = BubbleTextLineFitter.fit(
        text = text,
        envelope = targetEnvelope,
        locale = locale,
        minSp = minSp,
        maxSp = maxSp,
        spToPx = { sp -> sp * 2f },
        measurer = testMeasurer(),
        density = 2f,
        allowBrokenWords = allowBrokenWords
    )

    @Test
    fun `short text fits on one centered line`() {
        val result = fit("hello")

        assertTrue(result.fitsInside)
        assertEquals(1, result.lineCount)
        val layout = result.layout!!
        val line = layout.lines.single()
        assertEquals(envelope.centerX, line.leftPx + line.widthPx / 2f, 0.5f)
        assertTrue(line.leftPx >= envelope.contentLeft)
        assertTrue(line.leftPx + line.widthPx <= envelope.contentRight)
    }

    @Test
    fun `long text wraps using per band widths`() {
        val result = fit("the quick brown fox jumps over the lazy dog again and again")

        assertTrue(result.fitsInside)
        assertTrue(result.lineCount > 1)
        val layout = result.layout!!
        val lineHeight = result.totalHeightPx / result.lineCount
        val blockTop = envelope.centerY - result.totalHeightPx / 2f
        layout.lines.forEachIndexed { index, line ->
            val bandWidth = envelope.widthAtBand(
                blockTop + index * lineHeight,
                blockTop + (index + 1) * lineHeight
            ) * 0.985f
            assertTrue(line.text.isNotBlank())
            assertTrue(line.widthPx <= bandWidth + 0.5f)
            assertTrue(line.leftPx >= envelope.contentLeft - 0.5f)
            assertTrue(line.leftPx + line.widthPx <= envelope.contentRight + 0.5f)
        }
    }

    @Test
    fun `unbreakable word is rejected without explicit fallback`() {
        val narrowEnvelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 120f, 200f),
            horizontalInsetPx = 1f,
            verticalInsetPx = 1f
        )

        val result = fit(
            text = "supercalifragilisticexpialidocious",
            targetEnvelope = narrowEnvelope,
            minSp = 10f
        )

        assertFalse(result.fitsInside)
        assertEquals(BubbleTextRejection.NO_BREAK_OPPORTUNITY, result.rejection)
    }

    @Test
    fun `unbreakable word can split only when explicitly allowed`() {
        val narrowEnvelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 120f, 200f),
            horizontalInsetPx = 1f,
            verticalInsetPx = 1f
        )

        val result = fit(
            text = "supercalifragilisticexpialidocious",
            targetEnvelope = narrowEnvelope,
            minSp = 10f,
            allowBrokenWords = true
        )

        assertTrue(result.fitsInside)
        assertTrue(result.lineCount > 1)
        assertTrue(result.layout!!.lines.all { it.text.isNotBlank() })
    }

    @Test
    fun `cjk text uses locale break opportunities without spaces`() {
        val result = fit(
            text = "こんにちは世界これはテストです",
            targetEnvelope = BubbleLineEnvelope(
                bounds = EnvelopeBounds(0f, 0f, 180f, 160f)
            ),
            minSp = 6f,
            locale = Locale.JAPANESE
        )

        assertTrue(result.fitsInside)
        assertTrue(result.lineCount > 1)
        assertTrue(result.layout!!.lines.all { it.text.isNotBlank() })
    }

    @Test
    fun `height limit rejects text that cannot form one line`() {
        val result = fit(
            text = "hello",
            targetEnvelope = BubbleLineEnvelope(
                bounds = EnvelopeBounds(0f, 0f, 300f, 20f)
            ),
            minSp = 12f
        )

        assertFalse(result.fitsInside)
        assertEquals(BubbleTextRejection.TOO_MANY_LINES, result.rejection)
    }

    @Test
    fun `unsupported shape is rejected instead of guessed`() {
        val result = fit(
            text = "hello",
            targetEnvelope = BubbleLineEnvelope(
                bounds = EnvelopeBounds(0f, 0f, 300f, 200f),
                shape = EnvelopeShape(kind = EnvelopeShapeKind.ROUNDED_RECTANGLE)
            )
        )

        assertFalse(result.fitsInside)
        assertEquals(BubbleTextRejection.UNSUPPORTED_SHAPE, result.rejection)
    }

    @Test
    fun `empty text is rejected`() {
        val result = fit("   ")

        assertFalse(result.fitsInside)
        assertEquals(BubbleTextRejection.EMPTY_TEXT, result.rejection)
    }

    private fun testMeasurer(): BubbleTextMeasurer = BubbleTextMeasurer(
        measureWidth = { text, textSizePx -> text.length * textSizePx * 0.55f },
        metricsForSize = { textSizePx ->
            TextLineMetrics(
                ascentPx = -0.8f * textSizePx,
                descentPx = 0.2f * textSizePx
            )
        }
    )
}
