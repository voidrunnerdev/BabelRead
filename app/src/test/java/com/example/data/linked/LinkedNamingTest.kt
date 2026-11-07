package com.example.data.linked

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkedNamingTest {
    @Test
    fun cleanTitleFollowsLocalImportConventions() {
        assertEquals("One Piece", LinkedNaming.cleanTitle("One_Piece.cbz"))
        assertEquals("Bölüm 1", LinkedNaming.cleanTitle("Bölüm-1"))
        assertEquals("Yerel Manga", LinkedNaming.cleanTitle("   "))
    }

    @Test
    fun chapterNumberPrefersExplicitMarkers() {
        assertEquals(12.0, LinkedNaming.chapterNumber("Bölüm 12", fallback = 1), 0.0)
        assertEquals(3.5, LinkedNaming.chapterNumber("Chapter 3,5", fallback = 1), 0.0)
        assertEquals(7.0, LinkedNaming.chapterNumber("Ch. 7 - Final", fallback = 1), 0.0)
        assertEquals(42.0, LinkedNaming.chapterNumber("Manga 42", fallback = 1), 0.0)
    }

    @Test
    fun chapterNumberFallsBackWhenNoNumberExists() {
        assertEquals(4.0, LinkedNaming.chapterNumber("Ekstra", fallback = 4), 0.0)
    }
}
