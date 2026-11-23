package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurkishSyllabifierTest {

    @Test
    fun `splits agglutinative Turkish words at syllable boundaries`() {
        val word = "karşılaşabileceğimiz"
        val offsets = TurkishSyllabifier.syllableBreakOffsets(word)
        val parts = buildList {
            var prev = 0
            for (o in offsets) {
                add(word.substring(prev, o))
                prev = o
            }
            add(word.substring(prev))
        }
        assertEquals(
            listOf("kar", "şı", "la", "şa", "bi", "le", "ce", "ği", "miz"),
            parts
        )
    }

    @Test
    fun `respects three-character minimum on both sides to prevent ugly two-letter stubs`() {
        // Short words like "oynamak" (7 chars) or "sizinle" (7 chars) should stay whole,
        // and breaks like "OY-namak" or "Sİ-zinle" (< 3 chars on either side) are rejected.
        assertTrue(TurkishSyllabifier.syllableBreakOffsets("oynamak").isEmpty())
        assertTrue(TurkishSyllabifier.syllableBreakOffsets("sizinle").isEmpty())
        val offsets = TurkishSyllabifier.syllableBreakOffsets("oynamak", minWordLength = 7)
        // Only "oyna-mak" (4+3) is valid; "oy-namak" (2+5) is rejected.
        assertEquals(listOf(4), offsets)
    }

    @Test
    fun `handles three-consonant clusters cleanly`() {
        val word = "elektrik"
        val offsets = TurkishSyllabifier.syllableBreakOffsets(word)
        val parts = buildList {
            var prev = 0
            for (o in offsets) {
                add(word.substring(prev, o))
                prev = o
            }
            add(word.substring(prev))
        }
        assertEquals(listOf("elekt", "rik"), parts)
    }

    @Test
    fun `inserts soft hyphens only into eligible words and preserves punctuation`() {
        val result = TurkishSyllabifier.insertSoftHyphens("Bunu başarabiliriz!")
        // "ba-şa-ra-bi-li-riz": "ba-" (2 chars) is rejected; starts at "başa-ra-bi-li-riz"
        assertEquals("Bunu başa\u00ADra\u00ADbi\u00ADli\u00ADriz!", result)
    }

    @Test
    fun `handles uppercase Turkish words and ignores all-vowel screams`() {
        assertTrue(TurkishSyllabifier.syllableBreakOffsets("KARŞILAŞABİLECEĞİMİZ").isNotEmpty())
        assertTrue(TurkishSyllabifier.syllableBreakOffsets("AAAAAAA").isEmpty())
    }

    @Test
    fun `preserves apostrophes in proper nouns and splits cleanly`() {
        val word = "İstanbul'da"
        val offsets = TurkishSyllabifier.syllableBreakOffsets(word)
        assertTrue("Expected non-empty syllable breaks for İstanbul'da", offsets.isNotEmpty())
        assertTrue("Apostrophe split should be included", offsets.contains(9)) // "İstanbul'" and "da"
        val softHyphenated = TurkishSyllabifier.insertSoftHyphens("Yarın İstanbul'da buluşuruz.")
        assertTrue(softHyphenated.contains("İstanbul'\u00ADda"))
    }
}
