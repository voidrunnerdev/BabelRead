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
    fun `turkish broken words use syllable boundaries with visible hyphen`() {
        val narrowEnvelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 120f, 200f),
            horizontalInsetPx = 1f,
            verticalInsetPx = 1f
        )

        val result = fit(
            text = "karşılaşabileceğimiz",
            targetEnvelope = narrowEnvelope,
            minSp = 10f,
            allowBrokenWords = true,
            locale = Locale("tr", "TR")
        )

        assertTrue(result.fitsInside)
        assertTrue(result.lineCount > 1)
        assertTrue(result.layout!!.lines.first().text.endsWith("-"))
    }

    @Test
    fun `contour scanline rectangular box fits larger font than ellipse`() {
        val lefts = FloatArray(20) { row -> if (row in 1..18) 0.05f else 0.5f }
        val rights = FloatArray(20) { row -> if (row in 1..18) 0.95f else 0.5f }
        val contourEnvelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 200f, 140f),
            shape = EnvelopeShape(kind = EnvelopeShapeKind.CONTOUR_SCANLINE),
            contourProfile = ContourScanlineProfile(lefts, rights)
        )
        val ellipseEnvelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 200f, 140f)
        )
        val sample = "Bu uzun anlatı kutusu metni dikdörtgen balonun üst ve alt köşelerini kullanır"

        val contourFit = fit(sample, targetEnvelope = contourEnvelope, minSp = 6f, maxSp = 20f, locale = Locale("tr", "TR"))
        val ellipseFit = fit(sample, targetEnvelope = ellipseEnvelope, minSp = 6f, maxSp = 20f, locale = Locale("tr", "TR"))

        assertTrue(contourFit.fitsInside)
        assertTrue(ellipseFit.fitsInside)
        assertTrue(
            "Expected contour (${contourFit.fontSizeSp}) > ellipse (${ellipseFit.fontSizeSp})",
            contourFit.fontSizeSp > ellipseFit.fontSizeSp
        )
    }

    @Test
    fun `dp line breaker balances lines instead of leaving a tiny orphan word`() {
        val rectLefts = FloatArray(20) { 0.05f }
        val rectRights = FloatArray(20) { 0.95f }
        val boxEnvelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 200f, 100f),
            shape = EnvelopeShape(kind = EnvelopeShapeKind.CONTOUR_SCANLINE),
            contourProfile = ContourScanlineProfile(rectLefts, rectRights)
        )
        // Greedy at 2 lines packs 3 words on line 1 and leaves a lonely "go" on line 2;
        // DP balances into 2+2 words across the two lines.
        val result = fit(
            text = "alpha beta gamma go",
            targetEnvelope = boxEnvelope,
            minSp = 10f,
            maxSp = 10f
        )

        assertTrue(result.fitsInside)
        assertEquals(2, result.lineCount)
        assertEquals("alpha beta", result.layout!!.lines[0].text)
        assertEquals("gamma go", result.layout!!.lines[1].text)
    }

    @Test
    fun `optical vertical centering shifts ink mass toward bubble center without leaving bounds`() {
        val asymmetricMeasurer = BubbleTextMeasurer(
            measureWidth = { text, textSizePx -> text.length * textSizePx * 0.55f },
            metricsForSize = { textSizePx -> TextLineMetrics(-0.85f * textSizePx, 0.25f * textSizePx) },
            // Simulate uppercase diacritic-heavy ink sitting higher above baseline (-0.80..0.0)
            visualBoundsForLine = { _, textSizePx -> (-0.80f * textSizePx) to 0.0f }
        )
        val result = BubbleTextLineFitter.fit(
            text = "İSTANBUL",
            envelope = envelope,
            locale = Locale("tr", "TR"),
            minSp = 12f,
            maxSp = 12f,
            spToPx = { it * 2f },
            measurer = asymmetricMeasurer,
            density = 2f
        )
        assertTrue(result.fitsInside)
        val line = result.layout!!.lines.single()
        val visualCenter = line.baselinePx - 0.40f * result.fontSizePx
        assertEquals(envelope.centerY, visualCenter, 2.0f)
    }

    @Test
    fun `empty text is rejected`() {
        val result = fit("   ")

        assertFalse(result.fitsInside)
        assertEquals(BubbleTextRejection.EMPTY_TEXT, result.rejection)
    }

    @Test
    fun `dp breaker penalizes trailing Turkish conjunctions from hanging at line end`() {
        val boxEnvelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 150f, 100f)
        )
        // With text "Ahmet ve Mehmet", "ve" should not be left hanging at end of line 1 alone
        val result = fit(
            text = "Ahmet ve Mehmet",
            targetEnvelope = boxEnvelope,
            minSp = 12f,
            maxSp = 12f,
            locale = Locale("tr", "TR")
        )
        assertTrue(result.fitsInside)
        if (result.lineCount == 2) {
            val firstLine = result.layout!!.lines[0].text.trim()
            assertFalse("Line 1 should not end with isolated 've'", firstLine.endsWith(" ve"))
            assertFalse("Line 1 should not be just 've'", firstLine == "ve")
        }
    }

    @Test
    fun `turkish proper nouns with apostrophe split cleanly without double hyphen`() {
        val narrowEnvelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 120f, 200f),
            horizontalInsetPx = 1f,
            verticalInsetPx = 1f
        )
        val result = fit(
            text = "İstanbul'da",
            targetEnvelope = narrowEnvelope,
            minSp = 10f,
            allowBrokenWords = true,
            locale = Locale("tr", "TR")
        )
        assertTrue(result.fitsInside)
        assertTrue(result.lineCount > 1)
        val firstLine = result.layout!!.lines.first().text
        assertTrue("First line should be İstanbul' or ending with hyphen", firstLine.endsWith("'") || firstLine.endsWith("-"))
        assertFalse("Should not have double hyphen or '-", firstLine.endsWith("'-"))
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
