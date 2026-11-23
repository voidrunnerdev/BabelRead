package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PageFontHarmonizerTest {

    @Test
    fun `computes median baseline across normal dialogue bubbles`() {
        val samples = listOf(
            PageFontHarmonizer.BubbleSample(10f, "Uzun bir manga konuşma cümlesi", true, true),
            PageFontHarmonizer.BubbleSample(12f, "Diğer normal diyalog balonu metni", true, true),
            PageFontHarmonizer.BubbleSample(14f, "Üçüncü normal konuşma balonu metni", true, true),
            PageFontHarmonizer.BubbleSample(24f, "Dur!", true, true) // too short for baseline
        )
        val base = PageFontHarmonizer.computePageBaselineSp(samples)
        assertNotNull(base)
        assertEquals(12f, base!!, 0.01f)
    }

    @Test
    fun `caps oversized small bubble while preserving short shouts`() {
        val baseSp = 11f
        val cap = PageFontHarmonizer.harmonizedCapSp(
            currentSp = 18f,
            text = "Ne diyorsunuz?",
            pageBaseSp = baseSp
        )
        assertNotNull(cap)
        assertEquals(11f * 1.15f, cap!!, 0.01f)

        assertNull(
            "Short exclamation shout should stay exempt",
            PageFontHarmonizer.harmonizedCapSp(20f, "DUR!", baseSp)
        )
        assertNull(
            "Short !? shout should stay exempt",
            PageFontHarmonizer.harmonizedCapSp(20f, "NE!?", baseSp)
        )
        assertNull(
            "Harmonizer must never return a cap larger than currentSp",
            PageFontHarmonizer.harmonizedCapSp(9.6f, "Normal bir diyalog", 8.0f)
        )
        assertNull(
            "Bubble already within harmonic band should not be re-laid out",
            PageFontHarmonizer.harmonizedCapSp(12f, "Tamam o zaman", baseSp)
        )
    }

    @Test
    fun `two-bubble page anchors to the denser bubble instead of averaging with the outlier`() {
        val samples = listOf(
            PageFontHarmonizer.BubbleSample(11f, "5 Şınav Daha Yapabilirsen Sana Bir Öpücük Vereceğim", true, true),
            PageFontHarmonizer.BubbleSample(19f, "Arsa ne diyorsunuz?", true, true)
        )
        val base = PageFontHarmonizer.computePageBaselineSp(samples)
        assertNotNull(base)
        assertEquals(11f, base!!, 0.01f)
        val cap = PageFontHarmonizer.harmonizedCapSp(19f, "Arsa ne diyorsunuz?", base)
        assertNotNull(cap)
        assertEquals(11f * 1.15f, cap!!, 0.01f)
    }
}
