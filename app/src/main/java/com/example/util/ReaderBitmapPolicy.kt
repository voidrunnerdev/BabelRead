package com.example.util

import kotlin.math.max

object ReaderBitmapPolicy {
    const val MAX_BITMAP_DIMENSION = 8192
    const val FULL_RES_MAX_PIXELS = 8_000_000L
    const val DISPLAY_MAX_PIXELS = 8_000_000L

    private const val MIB = 1024L * 1024L
    private const val MIN_DISPLAY_CACHE_BYTES = 12L * MIB
    private const val MAX_DISPLAY_CACHE_BYTES = 96L * MIB
    private const val MIN_FULL_RES_CACHE_BYTES = 8L * MIB
    private const val MAX_FULL_RES_CACHE_BYTES = 64L * MIB
    private const val MAX_SAMPLE_SIZE = 1 shl 14

    fun displayCacheBytes(maxMemoryBytes: Long): Long =
        (maxMemoryBytes / 4L).coerceIn(MIN_DISPLAY_CACHE_BYTES, MAX_DISPLAY_CACHE_BYTES)

    fun fullResCacheBytes(maxMemoryBytes: Long): Long =
        (maxMemoryBytes / 8L).coerceIn(MIN_FULL_RES_CACHE_BYTES, MAX_FULL_RES_CACHE_BYTES)

    fun displayTargetWidthPx(screenWidthPx: Int, quality: String): Int {
        val factor = when (quality) {
            "Düşük" -> 0.75f
            "Yüksek" -> 1.5f
            else -> 1.0f
        }
        return (screenWidthPx.coerceAtLeast(1) * factor).toInt().coerceAtLeast(1)
    }

    fun chooseSampleSize(
        width: Int,
        height: Int,
        targetWidthPx: Int,
        maxPixels: Long,
        maxDimension: Int = MAX_BITMAP_DIMENSION
    ): Int {
        if (width <= 0 || height <= 0) return 4
        var sample = 1
        if (targetWidthPx > 0) {
            val minWidth = (targetWidthPx * 9 / 10).coerceAtLeast(1)
            while (sample < MAX_SAMPLE_SIZE) {
                val nextWidth = width / (sample * 2)
                if (nextWidth < minWidth) break
                sample *= 2
            }
        }
        while (sample < MAX_SAMPLE_SIZE && pixelsAt(width, height, sample) > maxPixels) {
            sample *= 2
        }
        while (sample < MAX_SAMPLE_SIZE &&
            max(width / sample, height / sample) > maxDimension
        ) {
            sample *= 2
        }
        return sample
    }

    fun cacheEntryFits(entryBytes: Long, maxBytes: Long): Boolean =
        entryBytes > 0L && entryBytes <= maxBytes

    private fun pixelsAt(width: Int, height: Int, sample: Int): Long =
        (width / sample).toLong() * (height / sample).toLong()
}
