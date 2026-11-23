package com.example.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationCacheUrlMatcherTest {

    @Test
    fun `matches identical local paths and rejects different local pages`() {
        assertTrue(TranslationCacheUrlMatcher.matches("/storage/p_001.jpg", "/storage/p_001.jpg"))
        assertFalse(TranslationCacheUrlMatcher.matches("/storage/p_001.jpg", "/storage/p_002.jpg"))
    }

    @Test
    fun `matches remote URLs even when CDN query token or mirror host rotates`() {
        assertTrue(
            TranslationCacheUrlMatcher.matches(
                "https://cdn1.example.com/ch1/page_05.webp?token=aaa111",
                "https://cdn1.example.com/ch1/page_05.webp?token=bbb222"
            )
        )
        assertTrue(
            TranslationCacheUrlMatcher.matches(
                "https://cdn1.example.com/ch1/01.jpg?token=aaa111",
                "https://cdn2.example.com/ch1/01.jpg?token=bbb222"
            )
        )
        assertTrue(
            TranslationCacheUrlMatcher.matches(
                "https://node-a.mangadex.network/data/hash1/x5-abc.png",
                "https://node-b.mangadex.network/data/hash2/x5-abc.png"
            )
        )
        assertFalse(
            TranslationCacheUrlMatcher.matches(
                "https://cdn1.example.com/ch1/page_05.webp?token=aaa",
                "https://cdn1.example.com/ch1/page_06.webp?token=aaa"
            )
        )
    }

    @Test
    fun `rejects generic filenames when remote directory path changes`() {
        assertFalse(
            TranslationCacheUrlMatcher.matches(
                "https://cdn.example.com/v1/01.jpg",
                "https://cdn.example.com/v2/01.jpg"
            )
        )
        assertFalse(
            TranslationCacheUrlMatcher.matches(
                "https://cdn.example.com/ch1/page_05.webp",
                "https://cdn.example.com/ch1_v2/page_05.webp"
            )
        )
    }

    @Test
    fun `sanitizeForStorage strips remote query tokens and preserves local paths`() {
        org.junit.Assert.assertEquals(
            "https://cdn.example.com/ch1/01.webp",
            TranslationCacheUrlMatcher.sanitizeForStorage("https://cdn.example.com/ch1/01.webp?token=secret&sig=123#top")
        )
        org.junit.Assert.assertEquals(
            "/storage/emulated/0/Manga/ch1/01.jpg",
            TranslationCacheUrlMatcher.sanitizeForStorage("/storage/emulated/0/Manga/ch1/01.jpg")
        )
    }
}
