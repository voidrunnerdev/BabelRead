package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BubbleTypesettingQualityTest {

    @Test
    fun contourExtractor_rejectsLeakedWhiteBackgroundBubble() {
        // Simulates a white bubble whose border is broken on >50% of the perimeter
        val w = 32
        val h = 32
        val luma = IntArray(w * h) { 250 }
        val profile = BubbleContourExtractor.extractFromLuminanceGrid(
            luma = luma,
            gridW = w,
            gridH = h,
            seedX = 16,
            seedY = 16,
            isDarkBubble = false
        )
        assertNull("Leaked white-on-white bubble must fall back cleanly instead of producing a fake box", profile)
    }

    @Test
    fun contourExtractor_eliminatesMultiRowSpikeViaMedianFilter() {
        // Closed dark-bordered oval with a synthetic 2-row horizontal spike protruding on the left
        val w = 40
        val h = 40
        val luma = IntArray(w * h) { 30 } // dark exterior
        val cx = 22f
        val cy = 20f
        val rx = 12f
        val ry = 14f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x - cx) / rx
                val dy = (y - cy) / ry
                if (dx * dx + dy * dy <= 1f) {
                    luma[y * w + x] = 250
                }
            }
        }
        // Inject a 2-row-tall narrow leak beak reaching x=2 on rows 19..20
        for (y in 19..20) {
            for (x in 2..15) {
                luma[y * w + x] = 250
            }
        }
        val profile = BubbleContourExtractor.extractFromLuminanceGrid(
            luma = luma,
            gridW = w,
            gridH = h,
            seedX = 22,
            seedY = 20,
            isDarkBubble = false
        )
        assertNotNull(profile)
        val p = profile!!
        // Rows 19..20 should NOT protrude to x=2 (0.05) thanks to EDT + 5-row median filter
        assertTrue(
            "Spike beak at row 19 should be filtered out (left=${p.rowLeftFractions[19]})",
            p.rowLeftFractions[19] > 0.22f
        )
    }

    @Test
    fun bubbleTextLineFitter_neverChopsShortWordsOrDropsHyphens() {
        val envelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 90f, 220f),
            horizontalInsetPx = 2f,
            verticalInsetPx = 2f,
            shape = EnvelopeShape(EnvelopeShapeKind.ELLIPSE)
        )
        val measurer = BubbleTextMeasurer(
            measureWidth = { text, sizePx -> text.length * sizePx * 0.56f },
            metricsForSize = { sizePx -> TextLineMetrics(-sizePx * 0.85f, sizePx * 0.25f) }
        )
        val result = BubbleTextLineFitter.fit(
            text = "HAYIR, O ÇOK GENÇ, ONLARIN KARDEŞLERİ OLMALI.",
            envelope = envelope,
            locale = Locale("tr", "TR"),
            minSp = 6.0f,
            maxSp = 18.0f,
            spToPx = { it * 2f },
            measurer = measurer,
            density = 2f,
            allowBrokenWords = true
        )
        assertTrue("Text should fit cleanly inside vertical oval", result.fitsInside)
        val lines = result.layout?.lines.orEmpty().map { it.text }
        assertTrue(lines.isNotEmpty())
        // Ensure short words like HAYIR, GENÇ, ÇOK are never split mid-word without a hyphen
        val joined = lines.joinToString(" ")
        assertFalse("Must not chop HAYIR into HAY / IR: $lines", lines.any { it == "HAY" || it == "IR," })
        assertFalse("Must not chop GENÇ into GEN / Ç: $lines", lines.any { it == "GEN" || it == "Ç," })
        assertTrue(joined.contains("HAYIR"))
        assertTrue(joined.contains("GENÇ"))
    }

    @Test
    fun pageFontHarmonizer_capsGiantOneWordBubbleToMatchDialogueRhythm() {
        val baseline = PageFontHarmonizer.computePageBaselineSp(
            listOf(
                PageFontHarmonizer.BubbleSample(12f, "BUNA İHTİYACIM YOK, DAHA İYİ YAPIYORUM.", true, true),
                PageFontHarmonizer.BubbleSample(13f, "YOKSA SİZ DE BİRAZ İSTİYOR MUSUNUZ?", true, true),
                PageFontHarmonizer.BubbleSample(24f, "ORADA.", true, true)
            )
        )
        assertNotNull(baseline)
        val cap = PageFontHarmonizer.harmonizedCapSp(24f, "ORADA.", baseline!!)
        assertNotNull("Giant 24sp 'ORADA.' should be harmonized down", cap)
        assertTrue("Harmonized cap should be <= 15sp, was $cap", cap!! <= 15f)
    }

    @Test
    fun turkishSyllabifier_neverSplitsShortWords() {
        assertEquals(emptyList<Int>(), TurkishSyllabifier.syllableBreakOffsets("YANI"))
        assertEquals(emptyList<Int>(), TurkishSyllabifier.syllableBreakOffsets("BAŞKAN"))
        assertEquals(emptyList<Int>(), TurkishSyllabifier.syllableBreakOffsets("HAZIRLA"))
        assertTrue(TurkishSyllabifier.syllableBreakOffsets("İSTİYORSUNUZ").isNotEmpty())
    }

    @Test
    fun bubbleLineEnvelope_contourScanlineDomeTipNeverCollapsesPillBubble() {
        // Short flat pill bubble ("BU BU") whose extreme top/bottom dome rows are narrow
        val lefts = floatArrayOf(0.47f, 0.12f, 0.05f, 0.05f, 0.12f, 0.47f)
        val rights = floatArrayOf(0.53f, 0.88f, 0.95f, 0.95f, 0.88f, 0.53f)
        val profile = ContourScanlineProfile(lefts, rights, 0.5f, 0.5f)
        val envelope = BubbleLineEnvelope(
            bounds = EnvelopeBounds(0f, 0f, 100f, 36f),
            horizontalInsetPx = 4f,
            verticalInsetPx = 2f,
            shape = EnvelopeShape(EnvelopeShapeKind.CONTOUR_SCANLINE),
            contourProfile = profile
        )
        val span = envelope.spanAtBand(2f, 34f)
        assertTrue(
            "Dome tip row must not collapse pill bubble band to 0px (width=${span.widthPx})",
            span.widthPx >= 60f
        )
    }

    @Test
    fun turkishSyllabifier_respectsApostropheWithoutIllegalHyphen() {
        val soft = TurkishSyllabifier.insertSoftHyphens("Ankara'nın")
        assertFalse("Must not insert soft hyphen right before apostrophe: $soft", soft.contains("\u00AD'"))
        assertFalse("Must not insert visible-hyphen soft-hyphen right after apostrophe (TDK): $soft", soft.contains("'\u00AD"))
        assertTrue("Should allow zero-width break after apostrophe: $soft", soft.contains("'\u200B"))
        assertNull("Short ?! shout must be exempt from harmonizer cap", PageFontHarmonizer.harmonizedCapSp(22f, "NE?!", 12f))
    }
}
