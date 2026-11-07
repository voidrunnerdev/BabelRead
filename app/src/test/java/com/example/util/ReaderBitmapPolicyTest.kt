package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderBitmapPolicyTest {

    @Test
    fun `display cache budget grows with heap and is capped`() {
        assertEquals(16L * 1024L * 1024L, ReaderBitmapPolicy.displayCacheBytes(64L * 1024L * 1024L))
        assertEquals(64L * 1024L * 1024L, ReaderBitmapPolicy.displayCacheBytes(256L * 1024L * 1024L))
        assertEquals(96L * 1024L * 1024L, ReaderBitmapPolicy.displayCacheBytes(1024L * 1024L * 1024L))
    }

    @Test
    fun `full res cache budget is separate and smaller`() {
        assertEquals(8L * 1024L * 1024L, ReaderBitmapPolicy.fullResCacheBytes(64L * 1024L * 1024L))
        assertEquals(32L * 1024L * 1024L, ReaderBitmapPolicy.fullResCacheBytes(256L * 1024L * 1024L))
        assertEquals(64L * 1024L * 1024L, ReaderBitmapPolicy.fullResCacheBytes(1024L * 1024L * 1024L))
    }

    @Test
    fun `cache budgets stay bounded on very small heaps`() {
        assertEquals(12L * 1024L * 1024L, ReaderBitmapPolicy.displayCacheBytes(32L * 1024L * 1024L))
        assertEquals(8L * 1024L * 1024L, ReaderBitmapPolicy.fullResCacheBytes(32L * 1024L * 1024L))
    }

    @Test
    fun `display target width follows quality preference`() {
        assertEquals(810, ReaderBitmapPolicy.displayTargetWidthPx(1080, "Düşük"))
        assertEquals(1080, ReaderBitmapPolicy.displayTargetWidthPx(1080, "Orta"))
        assertEquals(1620, ReaderBitmapPolicy.displayTargetWidthPx(1080, "Yüksek"))
    }

    @Test
    fun `sample size prefers screen width and keeps some zoom headroom`() {
        assertEquals(
            2,
            ReaderBitmapPolicy.chooseSampleSize(
                width = 2000,
                height = 2500,
                targetWidthPx = 1080,
                maxPixels = ReaderBitmapPolicy.DISPLAY_MAX_PIXELS
            )
        )
        assertEquals(
            1,
            ReaderBitmapPolicy.chooseSampleSize(
                width = 2000,
                height = 2500,
                targetWidthPx = 1620,
                maxPixels = ReaderBitmapPolicy.DISPLAY_MAX_PIXELS
            )
        )
    }

    @Test
    fun `pixel and dimension caps still bound tall webtoon pages`() {
        assertEquals(
            4,
            ReaderBitmapPolicy.chooseSampleSize(
                width = 800,
                height = 20000,
                targetWidthPx = 1080,
                maxPixels = ReaderBitmapPolicy.DISPLAY_MAX_PIXELS
            )
        )
    }

    @Test
    fun `full res sampling ignores screen width and keeps eight megapixel cap`() {
        assertEquals(
            2,
            ReaderBitmapPolicy.chooseSampleSize(
                width = 4000,
                height = 3000,
                targetWidthPx = 0,
                maxPixels = ReaderBitmapPolicy.FULL_RES_MAX_PIXELS
            )
        )
    }

    @Test
    fun `invalid image dimensions fall back to a safe sample size`() {
        assertEquals(
            4,
            ReaderBitmapPolicy.chooseSampleSize(0, 1000, 1080, ReaderBitmapPolicy.DISPLAY_MAX_PIXELS)
        )
    }

    @Test
    fun `oversized bitmap entries are rejected instead of clearing a cache`() {
        assertTrue(ReaderBitmapPolicy.cacheEntryFits(1024L, 1024L))
        assertFalse(ReaderBitmapPolicy.cacheEntryFits(1025L, 1024L))
        assertFalse(ReaderBitmapPolicy.cacheEntryFits(0L, 1024L))
        assertFalse(ReaderBitmapPolicy.cacheEntryFits(1024L, 0L))
    }
}
