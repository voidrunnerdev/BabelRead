package com.example.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCRv5 mobile recognizer with DBNet line detection.
 *
 * A text block is split into line crops by the PP-OCRv5 detector when its
 * asset is available, falling back to the heuristic segmenter otherwise. Each
 * line is then run through the PP-OCRv5 CTC recognizer and mapped back to the
 * original page coordinates.
 */
class PpOcrV5Engine(
    context: Context,
    private val variant: Variant = Variant.MULTILINGUAL
) {
    enum class Variant(
        val modelAssets: List<String>,
        val dictAsset: String,
        val cacheFileName: String,
        val expectedBytes: Long?,
        val englishOnly: Boolean
    ) {
        MULTILINGUAL(
            modelAssets = listOf("models/ppocrv5_rec.onnx"),
            dictAsset = "models/ppocrv5_dict.txt",
            cacheFileName = "ppocrv5_rec_v1.onnx",
            expectedBytes = 16_534_782L,
            englishOnly = false
        ),
        ENGLISH_ONLY(
            modelAssets = listOf("models/en_PP-OCRv5_mobile_rec.onnx", "models/en_ppocrv5_rec.onnx"),
            dictAsset = "models/en_ppocrv5_dict.txt",
            cacheFileName = "en_ppocrv5_rec_v1.onnx",
            expectedBytes = 7_848_423L,
            englishOnly = true
        )
    }

    companion object {
        private const val TAG = "BabelRead_PpOcr"
        private const val MIN_GENERIC_MODEL_BYTES = 100_000L
        private const val REC_HEIGHT = 48
        private const val MIN_REC_WIDTH = 320
        private const val MAX_REC_WIDTH = 3200
        private const val MIN_LINE_CONFIDENCE = 0.65f
        private const val MIN_EN_LINE_CONFIDENCE = 0.55f
        private const val MIN_EN_SHORT_LINE_CONFIDENCE = 0.50f
        private const val MIN_CJK_LINE_CONFIDENCE = 0.45f
        private const val DARK_REGION_MEDIAN_LUMA = 95f
        private const val COLORED_REGION_MAX_MEDIAN_LUMA = 198f
        private const val LIGHT_TEXT_MIN_P95_LUMA = 205f
        private const val LIGHT_TEXT_MIN_CONTRAST = 42f
        private const val NON_WHITE_BORDER_MIN_FRACTION = 0.45f
        private val LEGACY_PARSEQ_CACHE_FILES = listOf("parseq_rec_v1.onnx", "parseq_rec_v2_090.onnx")

        /**
         * Lightweight existence check for Settings UI without initializing ONNX Runtime
         * or extracting the model from assets.
         */
        fun hasModelAsset(context: Context, variant: Variant): Boolean {
            val app = context.applicationContext
            val cached = File(app.filesDir, variant.cacheFileName)
            val expected = variant.expectedBytes
            val cacheValid = cached.exists() && if (expected != null) {
                cached.length() == expected
            } else {
                cached.length() >= MIN_GENERIC_MODEL_BYTES
            }
            if (cacheValid) return true

            return variant.modelAssets.any { assetPath ->
                try {
                    app.assets.open(assetPath).use { true }
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }

    internal data class LineResult(
        val text: String,
        val confidence: Float
    )

    private data class BlockAttempt(
        val results: List<TranslationService.RectAndText>,
        val lineCount: Int,
        val vertical: Boolean
    )

    private val appContext = context.applicationContext
    private val lineSegmenter = TextLineSegmenter()
    private val detector = PpOcrV5Detector(appContext)
    private val sessionMutex = Mutex()
    private val sessionLock = Any()

    @Volatile
    private var ortEnv: OrtEnvironment? = null

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var dictionary: List<String>? = null

    @Volatile
    private var initFailed = false

    @Volatile
    private var isClosed = false

    fun isReady(): Boolean = (session != null && dictionary != null) || ensureInitialized()

    /**
     * Loads the recognizer ahead of the first page so model extraction and ORT
     * session creation do not block the translation pipeline.
     */
    suspend fun prewarm(): Boolean = withContext(Dispatchers.IO) {
        val loaded = ensureInitialized()
        if (loaded) detector.prewarm()
        loaded
    }

    suspend fun detectMissingPageLines(
        bitmap: Bitmap,
        excludeRegions: List<Rect>,
        includeInvertedPass: Boolean = true
    ): List<PpOcrV5Detector.DetectedLine> = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled || bitmap.width < 8 || bitmap.height < 8) return@withContext emptyList()
        if (!ensureInitialized()) return@withContext emptyList()
        detector.detectMissingPageLines(bitmap, excludeRegions, includeInvertedPass)
    }

    suspend fun recognizeBlock(
        bitmap: Bitmap,
        region: Rect,
        originLanguage: String
    ): List<TranslationService.RectAndText> = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled || region.isEmpty) return@withContext emptyList()
        val primaryLanguage = normalizeLanguage(originLanguage) ?: return@withContext emptyList()
        if (!ensureInitialized()) return@withContext emptyList()

        val pageRegion = Rect(region)
        if (!pageRegion.intersect(0, 0, bitmap.width, bitmap.height) || pageRegion.isEmpty) {
            return@withContext emptyList()
        }

        val isDarkRegion = isDarkBackgroundRegion(bitmap, pageRegion)
        val workBitmap: Bitmap
        val workRegion: Rect
        val offsetX: Int
        val offsetY: Int

        if (isDarkRegion) {
            val regionCrop = try {
                Bitmap.createBitmap(
                    bitmap,
                    pageRegion.left,
                    pageRegion.top,
                    pageRegion.width(),
                    pageRegion.height()
                )
            } catch (_: Exception) {
                return@withContext emptyList()
            }
            workBitmap = try {
                invertHighContrastBitmap(regionCrop)
            } finally {
                if (regionCrop !== bitmap && !regionCrop.isRecycled) regionCrop.recycle()
            }
            workRegion = Rect(0, 0, workBitmap.width, workBitmap.height)
            offsetX = pageRegion.left
            offsetY = pageRegion.top
        } else {
            workBitmap = bitmap
            workRegion = pageRegion
            offsetX = 0
            offsetY = 0
        }

        try {
            var regionComponentsChecked = false
            var regionHasValidComponents = false
            fun ensureValidComponents(): Boolean {
                if (!regionComponentsChecked) {
                    regionHasValidComponents = hasValidTextRegionComponents(workBitmap, workRegion)
                    regionComponentsChecked = true
                }
                return regionHasValidComponents
            }

            val narrowVerticalPill = isNarrowVerticalPill(workRegion)

            suspend fun recognize(
                language: String,
                forceVertical: Boolean = false,
                forceHorizontal: Boolean = false
            ): BlockAttempt {
                val effectiveHorizontal = forceHorizontal || (variant.englishOnly && !forceVertical)
                val effectiveVertical = forceVertical && (!variant.englishOnly || narrowVerticalPill)
                val detected = if (!effectiveVertical && !forceHorizontal) {
                    detector.detect(workBitmap, workRegion)
                } else {
                    emptyList()
                }
                val lines = when {
                    detected.isNotEmpty() && !needsNarrowVerticalRecovery(workRegion, detected) -> {
                        detectedToLines(detected, language, effectiveVertical, effectiveHorizontal)
                    }
                    ensureValidComponents() -> {
                        val segmented = lineSegmenter.segment(
                            workBitmap,
                            workRegion,
                            language,
                            forceVertical = effectiveVertical,
                            forceHorizontal = effectiveHorizontal
                        )
                        if (detected.isEmpty()) {
                            segmented
                        } else {
                            mergeDetectedAndSegmentedLines(
                                detectedToLines(detected, language, effectiveVertical, effectiveHorizontal),
                                segmented
                            )
                        }
                    }
                    detected.isNotEmpty() -> {
                        detectedToLines(detected, language, effectiveVertical, effectiveHorizontal)
                    }
                    else -> emptyList()
                }
                val vertical = if (lines.isEmpty()) {
                    (!variant.englishOnly && isJapanese(primaryLanguage) && workRegion.height() > workRegion.width() * 1.8f) ||
                        (narrowVerticalPill && forceVertical)
                } else {
                    lines.count { it.vertical } * 2 >= lines.size
                }
                if (lines.isEmpty()) return BlockAttempt(emptyList(), 0, vertical)

                val results = ArrayList<TranslationService.RectAndText>(lines.size)
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    val recognized = recognizeLine(workBitmap, line.rect, line.vertical) ?: continue
                    if (!acceptsLine(recognized, language)) {
                        Log.d(TAG, "PP-OCRv5 rejected low-confidence line: '${recognized.text}' (${recognized.confidence}, $language)")
                        continue
                    }
                    if (recognized.text.isNotBlank()) {
                        val pageRect = Rect(line.rect).apply { offset(offsetX, offsetY) }
                        results.add(TranslationService.RectAndText(pageRect, recognized.text))
                    }
                }
                return BlockAttempt(results, lines.size, vertical)
            }

            val primary = recognize(primaryLanguage)
            val shouldRetryOpposite = (narrowVerticalPill && primary.results.isEmpty()) ||
                (!variant.englishOnly && (primary.lineCount == 0 || primary.results.size < primary.lineCount))
            if (!shouldRetryOpposite) {
                return@withContext primary.results
            }

            // Some lines may be rejected in the detected direction. Retry the same
            // language in the opposite direction and keep the successful lines.
            val retryVertical = if (narrowVerticalPill && variant.englishOnly) true else !primary.vertical
            val fallback = recognize(
                primaryLanguage,
                forceVertical = retryVertical,
                forceHorizontal = !retryVertical
            )
            if (fallback.results.isEmpty()) return@withContext primary.results

            val merged = ArrayList(primary.results)
            val seen = HashSet<Rect>()
            for (result in primary.results) seen.add(Rect(result.rect))
            for (result in fallback.results) {
                val rect = Rect(result.rect)
                if (!seen.add(rect)) continue
                if (overlapsExisting(rect, merged)) continue
                merged.add(result)
            }
            if (merged.size > primary.results.size) {
                Log.d(
                    TAG,
                    "PP-OCRv5 recovered ${merged.size - primary.results.size} lines with " +
                        "${if (retryVertical) "vertical" else "horizontal"} retry"
                )
            }
            merged
        } finally {
            if (workBitmap !== bitmap && !workBitmap.isRecycled) {
                workBitmap.recycle()
            }
        }
    }

    private fun detectedToLines(
        detected: List<PpOcrV5Detector.DetectedLine>,
        language: String,
        forceVertical: Boolean,
        forceHorizontal: Boolean
    ): List<SegmentedTextLine> {
        val japanese = isJapanese(language)
        val lines = detected.map { line ->
            val rect = Rect(line.rect)
            val vertical = when {
                forceVertical -> true
                forceHorizontal -> false
                japanese -> rect.height() > rect.width() * 1.5f
                else -> false
            }
            SegmentedTextLine(rect, vertical)
        }
        val vertical = lines.count { it.vertical } * 2 >= lines.size
        return if (vertical) {
            lines.sortedByDescending { it.rect.right }
        } else {
            sortHorizontalLinesInReadingOrder(lines)
        }
    }

    internal fun isNarrowVerticalPill(region: Rect): Boolean =
        region.width() in 1 until 90 && region.height() > region.width() * 2.2f

    internal fun needsNarrowVerticalRecovery(
        region: Rect,
        detected: List<PpOcrV5Detector.DetectedLine>
    ): Boolean {
        if (!isNarrowVerticalPill(region)) return false
        if (detected.isEmpty()) return true
        if (detected.size == 1) {
            val box = detected[0].rect
            val regionArea = region.width().toLong() * region.height().toLong()
            val boxArea = box.width().toLong() * box.height().toLong()
            return (box.height() < region.height() * 0.30f && box.width() < region.width() * 0.75f) ||
                (regionArea > 0L && boxArea * 100L < regionArea * 12L)
        }
        return false
    }

    private fun mergeDetectedAndSegmentedLines(
        detected: List<SegmentedTextLine>,
        segmented: List<SegmentedTextLine>
    ): List<SegmentedTextLine> {
        if (segmented.isEmpty()) return detected
        if (detected.isEmpty()) return segmented
        val merged = ArrayList<SegmentedTextLine>(segmented)
        for (det in detected) {
            val detArea = det.rect.width().toLong() * det.rect.height().toLong()
            if (detArea <= 0L) continue
            val overlaps = merged.any { existing ->
                val inter = Rect(det.rect)
                if (!inter.intersect(existing.rect)) return@any false
                val interArea = inter.width().toLong() * inter.height().toLong()
                val existArea = (existing.rect.width().toLong() * existing.rect.height().toLong()).coerceAtLeast(1L)
                interArea * 2L >= detArea || interArea * 2L >= existArea
            }
            if (!overlaps) merged.add(det)
        }
        return sortHorizontalLinesInReadingOrder(merged)
    }

    private fun overlapsExisting(
        rect: Rect,
        existing: List<TranslationService.RectAndText>
    ): Boolean {
        val candidateArea = rect.width().toLong() * rect.height()
        if (candidateArea <= 0L) return true
        for (item in existing) {
            val overlap = Rect(rect)
            if (!overlap.intersect(item.rect)) continue
            val overlapArea = overlap.width().toLong() * overlap.height()
            val existingArea = item.rect.width().toLong() * item.rect.height()
            if (overlapArea * 2 >= candidateArea || overlapArea * 2 >= existingArea) {
                return true
            }
        }
        return false
    }

    internal fun acceptsLine(line: LineResult, language: String): Boolean {
        val trimmed = line.text.trim()
        if (trimmed.isEmpty()) return false
        if (variant.englishOnly) {
            val alphaNumCount = trimmed.count { it.isLetterOrDigit() }
            if (alphaNumCount == 0) return false
            val minConfidence = if (alphaNumCount <= 4) MIN_EN_SHORT_LINE_CONFIDENCE else MIN_EN_LINE_CONFIDENCE
            return line.confidence >= minConfidence
        }
        if (line.confidence >= MIN_LINE_CONFIDENCE) return true
        if (!isCjkLanguage(language)) return false
        val hasCjk = trimmed.any { ch ->
            ch.code in 0x3040..0x9FAF || ch.code in 0xAC00..0xD7AF
        }
        return hasCjk && line.confidence >= MIN_CJK_LINE_CONFIDENCE
    }

    private fun isJapanese(language: String): Boolean =
        language.equals("Japonca", ignoreCase = true) ||
            language.equals("Japanese", ignoreCase = true)

    private fun isCjkLanguage(language: String): Boolean =
        isJapanese(language) ||
            language.equals("Korece", ignoreCase = true) ||
            language.equals("Korean", ignoreCase = true) ||
            language.equals("Çince", ignoreCase = true) ||
            language.equals("Chinese", ignoreCase = true)

    private fun normalizeLanguage(originLanguage: String): String? {
        val norm = originLanguage.trim().lowercase(java.util.Locale.ROOT).replace('ı', 'i').replace("i\u0307", "i")
        if (variant.englishOnly) {
            return when (norm) {
                "ingilizce", "english", "en", "yerel", "bilinmiyor", "otomatik", "auto" -> "İngilizce"
                else -> null
            }
        }
        return when (norm) {
            "ingilizce", "english", "en" -> "İngilizce"
            "japonca", "japanese", "ja" -> "Japonca"
            "korece", "korean", "ko" -> "Korece"
            "çince", "cince", "chinese", "zh" -> "Çince"
            "yerel", "bilinmiyor", "otomatik", "auto" -> "Japonca"
            else -> null
        }
    }

    private suspend fun recognizeLine(
        bitmap: Bitmap,
        rect: Rect,
        vertical: Boolean
    ): LineResult? = sessionMutex.withLock {
        if (bitmap.isRecycled) return@withLock null
        val safeRect = Rect(rect)
        if (!safeRect.intersect(0, 0, bitmap.width, bitmap.height)) return@withLock null
        if (safeRect.width() < 4 || safeRect.height() < 4) return@withLock null

        val crop = try {
            Bitmap.createBitmap(bitmap, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
        } catch (_: Exception) {
            return@withLock null
        }

        val oriented = if (vertical) {
            try {
                Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, Matrix().apply { postRotate(-90f) }, true)
            } catch (_: Exception) {
                crop
            }
        } else {
            crop
        }

        val prepared = prepareLineBitmapForRecognition(oriented)

        try {
            runRecognition(prepared)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "PP-OCRv5 line recognition failed: ${t.message}")
            null
        } finally {
            if (prepared !== oriented && prepared !== crop && prepared !== bitmap && !prepared.isRecycled) {
                prepared.recycle()
            }
            if (oriented !== crop && crop !== bitmap && !crop.isRecycled) crop.recycle()
            if (oriented !== bitmap && !oriented.isRecycled) oriented.recycle()
        }
    }

    /**
     * Trims excessive vertical whitespace left by DBNet unclipping and ensures a
     * small horizontal/vertical background margin so condensed words (e.g. "SPOT US")
     * keep enough horizontal resolution and edge breathing room for CTC spaces.
     */
    internal fun prepareLineBitmapForRecognition(crop: Bitmap): Bitmap {
        if (crop.isRecycled || crop.width < 4 || crop.height < 4) return crop
        val w = crop.width
        val h = crop.height
        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)

        val borderLumas = ArrayList<Int>(2 * (w + h))
        val borderColors = ArrayList<Int>(2 * (w + h))
        for (x in 0 until w) {
            val topPx = pixels[x]
            borderLumas.add(pixelLumaInt(topPx))
            borderColors.add(topPx)
            if (h > 1) {
                val botPx = pixels[(h - 1) * w + x]
                borderLumas.add(pixelLumaInt(botPx))
                borderColors.add(botPx)
            }
        }
        for (y in 1 until h - 1) {
            val leftPx = pixels[y * w]
            borderLumas.add(pixelLumaInt(leftPx))
            borderColors.add(leftPx)
            if (w > 1) {
                val rightPx = pixels[y * w + (w - 1)]
                borderLumas.add(pixelLumaInt(rightPx))
                borderColors.add(rightPx)
            }
        }
        if (borderLumas.isEmpty()) return crop

        val sortedLumas = borderLumas.sorted()
        val bgLuma = sortedLumas[sortedLumas.size / 2]
        if (bgLuma < 170) return crop

        val bgColor = if (bgLuma >= 235) {
            Color.WHITE
        } else {
            var bestColor = Color.WHITE
            var bestDiff = Int.MAX_VALUE
            for (i in borderLumas.indices) {
                val diff = kotlin.math.abs(borderLumas[i] - bgLuma)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestColor = borderColors[i]
                }
            }
            bestColor
        }

        val inkThreshold = bgLuma - 28
        var minInkX = w
        var maxInkX = -1
        var minInkY = h
        var maxInkY = -1
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                if (pixelLumaInt(pixels[rowOffset + x]) < inkThreshold) {
                    if (x < minInkX) minInkX = x
                    if (x > maxInkX) maxInkX = x
                    if (y < minInkY) minInkY = y
                    if (y > maxInkY) maxInkY = y
                }
            }
        }
        if (minInkX > maxInkX || minInkY > maxInkY) return crop

        val inkH = maxInkY - minInkY + 1
        val desiredPadY = max(2, (inkH * 0.12f).roundToInt().coerceAtMost(4))
        val candTop = (minInkY - desiredPadY).coerceAtLeast(0)
        val candBottom = (maxInkY + desiredPadY + 1).coerceAtMost(h)
        val candH = candBottom - candTop
        val shouldTrimY = candH >= 8 && candH <= (h * 0.85f).roundToInt()
        val trimTop = if (shouldTrimY) candTop else 0
        val trimBottom = if (shouldTrimY) candBottom else h
        val sliceH = trimBottom - trimTop
        val addTop = (2 - (minInkY - trimTop)).coerceAtLeast(0)
        val addBottom = (2 - (trimBottom - 1 - maxInkY)).coerceAtLeast(0)
        val outH = sliceH + addTop + addBottom

        val desiredPadX = max(3, min(6, (outH * 0.15f).roundToInt()))
        val addLeft = (desiredPadX - minInkX).coerceAtLeast(0)
        val addRight = (desiredPadX - (w - 1 - maxInkX)).coerceAtLeast(0)

        val baseBitmap = if (addLeft == 0 && addRight == 0 && addTop == 0 && addBottom == 0 && trimTop == 0 && trimBottom == h) {
            crop
        } else {
            val outW = w + addLeft + addRight
            val outPixels = IntArray(outW * outH) { bgColor }
            for (y in 0 until sliceH) {
                val srcOffset = (trimTop + y) * w
                val dstOffset = (addTop + y) * outW + addLeft
                System.arraycopy(pixels, srcOffset, outPixels, dstOffset, w)
            }
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).apply {
                setPixels(outPixels, 0, outW, 0, 0, outW, outH)
            }
        }

        val needsSharpen = crop.height < 44 || crop.width < 120 ||
            baseBitmap.height < 44 || baseBitmap.width < 120
        if (!needsSharpen) return baseBitmap

        val sharpened = sharpenLineBitmapForOcr(baseBitmap)
        if (baseBitmap !== crop && sharpened !== baseBitmap && !baseBitmap.isRecycled) {
            baseBitmap.recycle()
        }
        return sharpened
    }

    /**
     * Applies gentle adaptive contrast stretching and a 3x3 unsharp mask on small or
     * compressed line crops so narrow word spaces (e.g. "SPOT US") remain distinct.
     */
    internal fun sharpenLineBitmapForOcr(source: Bitmap): Bitmap {
        if (source.isRecycled || source.width < 4 || source.height < 4) return source
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        if (pixels.isEmpty()) return source

        val histogram = IntArray(256)
        for (px in pixels) {
            histogram[pixelLumaInt(px).coerceIn(0, 255)]++
        }
        val total = pixels.size
        fun percentile(p: Int): Int {
            val target = ((total - 1) * p) / 100
            var cumulative = 0
            for (lum in 0 until 256) {
                cumulative += histogram[lum]
                if (cumulative > target) return lum
            }
            return 255
        }

        val lowLum = percentile(2)
        val highLum = percentile(98)
        val span = highLum - lowLum
        val stretch = span in 16..135
        val stretched = if (stretch) {
            val scale = 255f / span.toFloat()
            IntArray(pixels.size) { idx ->
                val px = pixels[idx]
                val r = ((((px shr 16) and 0xFF) - lowLum) * scale).roundToInt().coerceIn(0, 255)
                val g = ((((px shr 8) and 0xFF) - lowLum) * scale).roundToInt().coerceIn(0, 255)
                val b = (((px and 0xFF) - lowLum) * scale).roundToInt().coerceIn(0, 255)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        } else {
            pixels
        }

        val amount = 0.35f
        val outPixels = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - 1) * w
            val y1 = y * w
            val y2 = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                val x0 = max(0, x - 1)
                val x1 = x
                val x2 = min(w - 1, x + 1)

                val p00 = stretched[y0 + x0]
                val p01 = stretched[y0 + x1]
                val p02 = stretched[y0 + x2]
                val p10 = stretched[y1 + x0]
                val p11 = stretched[y1 + x1]
                val p12 = stretched[y1 + x2]
                val p20 = stretched[y2 + x0]
                val p21 = stretched[y2 + x1]
                val p22 = stretched[y2 + x2]

                val blurR = (((p00 shr 16) and 0xFF) + 2 * ((p01 shr 16) and 0xFF) + ((p02 shr 16) and 0xFF) +
                    2 * ((p10 shr 16) and 0xFF) + 4 * ((p11 shr 16) and 0xFF) + 2 * ((p12 shr 16) and 0xFF) +
                    ((p20 shr 16) and 0xFF) + 2 * ((p21 shr 16) and 0xFF) + ((p22 shr 16) and 0xFF)) / 16f
                val blurG = (((p00 shr 8) and 0xFF) + 2 * ((p01 shr 8) and 0xFF) + ((p02 shr 8) and 0xFF) +
                    2 * ((p10 shr 8) and 0xFF) + 4 * ((p11 shr 8) and 0xFF) + 2 * ((p12 shr 8) and 0xFF) +
                    ((p20 shr 8) and 0xFF) + 2 * ((p21 shr 8) and 0xFF) + ((p22 shr 8) and 0xFF)) / 16f
                val blurB = ((p00 and 0xFF) + 2 * (p01 and 0xFF) + (p02 and 0xFF) +
                    2 * (p10 and 0xFF) + 4 * (p11 and 0xFF) + 2 * (p12 and 0xFF) +
                    (p20 and 0xFF) + 2 * (p21 and 0xFF) + (p22 and 0xFF)) / 16f

                val cR = (p11 shr 16) and 0xFF
                val cG = (p11 shr 8) and 0xFF
                val cB = p11 and 0xFF

                val outR = (cR + amount * (cR - blurR)).roundToInt().coerceIn(0, 255)
                val outG = (cG + amount * (cG - blurG)).roundToInt().coerceIn(0, 255)
                val outB = (cB + amount * (cB - blurB)).roundToInt().coerceIn(0, 255)
                outPixels[y1 + x1] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(outPixels, 0, w, 0, 0, w, h)
        }
    }

    private fun runRecognition(bitmap: Bitmap): LineResult? {
        val session = session ?: return null
        val dictionary = dictionary ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null

        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val resizedWidth = ceil(REC_HEIGHT * ratio).toInt()
            .coerceIn(1, MAX_REC_WIDTH)
        val targetWidth = resizedWidth.coerceAtLeast(MIN_REC_WIDTH)
        val scaled = try {
            Bitmap.createScaledBitmap(bitmap, resizedWidth, REC_HEIGHT, true)
        } catch (_: Exception) {
            return null
        }

        try {
            val pixels = IntArray(resizedWidth * REC_HEIGHT)
            scaled.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, REC_HEIGHT)
            val planeSize = targetWidth * REC_HEIGHT
            val buffer = ByteBuffer.allocateDirect(planeSize * 3 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            for (index in 0 until planeSize * 3) buffer.put(index, 0f)

            for (y in 0 until REC_HEIGHT) {
                val sourceRow = y * resizedWidth
                val targetRow = y * targetWidth
                for (x in 0 until resizedWidth) {
                    val color = pixels[sourceRow + x]
                    val targetIndex = targetRow + x
                    buffer.put(targetIndex, ((color and 0xFF) / 127.5f) - 1.0f)
                    buffer.put(planeSize + targetIndex, (((color shr 8) and 0xFF) / 127.5f) - 1.0f)
                    buffer.put(planeSize * 2 + targetIndex, (((color shr 16) and 0xFF) / 127.5f) - 1.0f)
                }
            }
            buffer.position(0)

            synchronized(sessionLock) {
                if (isClosed) return null
                val activeSession = this.session ?: return null
                val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
                val shape = longArrayOf(1, 3, REC_HEIGHT.toLong(), targetWidth.toLong())
                OnnxTensor.createTensor(env, buffer, shape).use { input ->
                    activeSession.run(mapOf(activeSession.inputNames.first() to input)).use { result ->
                        val output = result.get(0) as? OnnxTensor ?: return null
                        val outputShape = output.info.shape
                        if (outputShape.size != 3) return null
                        val timesteps = outputShape[1].toInt()
                        val classCount = outputShape[2].toInt()
                        if (timesteps <= 0 || classCount < dictionary.size + 1) return null
                        val values = output.floatBuffer
                        return decodeCtc(values, timesteps, classCount, dictionary)
                    }
                }
            }
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
    }

    internal fun decodeCtc(
        values: FloatBuffer,
        timesteps: Int,
        classCount: Int,
        dictionary: List<String>
    ): LineResult {
        val tokens = ArrayList<String>()
        val confidences = ArrayList<Float>()
        var lastIndex = -1
        for (t in 0 until timesteps) {
            val base = t * classCount
            var bestIndex = 0
            var bestValue = values.get(base)
            for (c in 1 until classCount) {
                val value = values.get(base + c)
                if (value > bestValue) {
                    bestValue = value
                    bestIndex = c
                }
            }
            if (bestIndex != 0 && bestIndex != lastIndex) {
                val token = when {
                    bestIndex <= dictionary.size -> dictionary[bestIndex - 1]
                    bestIndex == dictionary.size + 1 -> " "
                    else -> null
                }
                if (token != null && (token != " " || tokens.isNotEmpty())) {
                    tokens.add(token)
                    confidences.add(bestValue)
                }
            }
            lastIndex = bestIndex
        }
        while (tokens.isNotEmpty() && tokens.last() == " ") {
            tokens.removeAt(tokens.lastIndex)
            confidences.removeAt(confidences.lastIndex)
        }
        if (tokens.isEmpty()) return LineResult("", 0f)

        val builder = StringBuilder(tokens.size)
        var confidenceSum = 0.0
        for (i in tokens.indices) {
            builder.append(tokens[i])
            confidenceSum += confidences[i]
        }
        val text = builder.toString().trim()
        val confidence = if (text.isNotEmpty()) {
            (confidenceSum / tokens.size).toFloat()
        } else {
            0f
        }
        return LineResult(text, confidence)
    }

    internal fun isDarkBackgroundRegion(bitmap: Bitmap, region: Rect): Boolean {
        if (bitmap.isRecycled || region.isEmpty) return false
        val safe = Rect(region)
        if (!safe.intersect(0, 0, bitmap.width, bitmap.height) || safe.isEmpty) return false

        val w = safe.width()
        val h = safe.height()
        val stepX = max(1, w / 32)
        val stepY = max(1, h / 32)
        val samples = ArrayList<Float>(1024)

        var y = safe.top
        while (y < safe.bottom) {
            var x = safe.left
            while (x < safe.right) {
                samples.add(pixelLuma(bitmap.getPixel(x, y)))
                x += stepX
            }
            y += stepY
        }
        if (samples.isEmpty()) return false
        samples.sort()
        val medianLuma = samples[samples.size / 2]
        if (medianLuma >= COLORED_REGION_MAX_MEDIAN_LUMA) return false
        val p90Luma = samples[((samples.size - 1) * 9) / 10]
        val p95Luma = samples[((samples.size - 1) * 95) / 100]

        val darkCandidate = medianLuma < DARK_REGION_MEDIAN_LUMA && p90Luma > 125f
        val coloredLightTextCandidate = medianLuma < COLORED_REGION_MAX_MEDIAN_LUMA &&
            p95Luma >= LIGHT_TEXT_MIN_P95_LUMA &&
            (p95Luma - medianLuma) >= LIGHT_TEXT_MIN_CONTRAST
        if (!darkCandidate && !coloredLightTextCandidate) return false

        // Verify outer perimeter is predominantly dark/non-white so a tight
        // black-on-white crop with dense ink is not misclassified as a dark bubble.
        var borderSamples = 0
        var darkBorderSamples = 0
        var nonWhiteBorderSamples = 0
        val borderStepX = max(1, w / 24)
        val borderStepY = max(1, h / 24)
        var bx = safe.left
        while (bx < safe.right) {
            val topLum = pixelLuma(bitmap.getPixel(bx, safe.top))
            if (topLum < DARK_REGION_MEDIAN_LUMA) darkBorderSamples++
            if (topLum < COLORED_REGION_MAX_MEDIAN_LUMA) nonWhiteBorderSamples++
            borderSamples++
            if (h > 1) {
                val botLum = pixelLuma(bitmap.getPixel(bx, safe.bottom - 1))
                if (botLum < DARK_REGION_MEDIAN_LUMA) darkBorderSamples++
                if (botLum < COLORED_REGION_MAX_MEDIAN_LUMA) nonWhiteBorderSamples++
                borderSamples++
            }
            bx += borderStepX
        }
        var by = safe.top
        while (by < safe.bottom) {
            val leftLum = pixelLuma(bitmap.getPixel(safe.left, by))
            if (leftLum < DARK_REGION_MEDIAN_LUMA) darkBorderSamples++
            if (leftLum < COLORED_REGION_MAX_MEDIAN_LUMA) nonWhiteBorderSamples++
            borderSamples++
            if (w > 1) {
                val rightLum = pixelLuma(bitmap.getPixel(safe.right - 1, by))
                if (rightLum < DARK_REGION_MEDIAN_LUMA) darkBorderSamples++
                if (rightLum < COLORED_REGION_MAX_MEDIAN_LUMA) nonWhiteBorderSamples++
                borderSamples++
            }
            by += borderStepY
        }
        if (borderSamples <= 0) return false
        val darkBorderFraction = darkBorderSamples.toFloat() / borderSamples
        val nonWhiteBorderFraction = nonWhiteBorderSamples.toFloat() / borderSamples
        return (darkCandidate && darkBorderFraction >= 0.40f) ||
            (coloredLightTextCandidate && nonWhiteBorderFraction >= NON_WHITE_BORDER_MIN_FRACTION)
    }

    internal fun invertHighContrastBitmap(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        if (pixels.isEmpty()) {
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val histogram = IntArray(256)
        for (pixel in pixels) {
            histogram[pixelLumaInt(pixel).coerceIn(0, 255)]++
        }
        val total = pixels.size
        fun percentile(p: Int): Float {
            val target = ((total - 1) * p) / 100
            var cumulative = 0
            for (lum in 0 until 256) {
                cumulative += histogram[lum]
                if (cumulative > target) return lum.toFloat()
            }
            return 255f
        }
        val medianLuma = percentile(50)
        val p95Luma = percentile(95)
        val p98Luma = percentile(98)
        val fgAnchor = max((p95Luma + p98Luma) * 0.5f, (medianLuma + LIGHT_TEXT_MIN_CONTRAST).coerceAtMost(255f))
            .coerceAtMost(255f)
        val span = max(1f, fgAnchor - medianLuma)
        val bgAnchor = (medianLuma + span * 0.12f).coerceAtMost(fgAnchor - 1f)
        val denom = max(1f, fgAnchor - bgAnchor)

        for (i in pixels.indices) {
            val gray = pixelLuma(pixels[i])
            val norm = ((gray - bgAnchor) / denom).coerceIn(0f, 1f)
            val out = ((1f - norm) * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(out, out, out)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    internal fun hasValidTextRegionComponents(bitmap: Bitmap, region: Rect): Boolean {
        if (bitmap.isRecycled || region.isEmpty) return false
        val safe = Rect(region)
        if (!safe.intersect(0, 0, bitmap.width, bitmap.height) || safe.isEmpty) return false
        if (safe.width() < 8 || safe.height() < 8) return false

        val crop = try {
            Bitmap.createBitmap(bitmap, safe.left, safe.top, safe.width(), safe.height())
        } catch (_: Exception) {
            return false
        }
        val maxSide = max(crop.width, crop.height)
        val work = if (maxSide > 320) {
            val scale = 320f / maxSide
            try {
                Bitmap.createScaledBitmap(
                    crop,
                    max(8, (crop.width * scale).roundToInt()),
                    max(8, (crop.height * scale).roundToInt()),
                    true
                )
            } catch (_: Exception) {
                crop
            }
        } else {
            crop
        }

        try {
            val w = work.width
            val h = work.height
            val pixels = IntArray(w * h)
            work.getPixels(pixels, 0, w, 0, 0, w, h)
            val gray = IntArray(pixels.size) { idx -> pixelLumaInt(pixels[idx]) }
            val minGray = gray.minOrNull() ?: 255
            val maxGray = gray.maxOrNull() ?: 0
            if (maxGray - minGray < 35) return false

            val threshold = otsuThreshold(gray)
            var darkCount = 0
            var lightCount = 0
            var darkBorderCount = 0
            var lightBorderCount = 0
            for (y in 0 until h) {
                val rowOffset = y * w
                for (x in 0 until w) {
                    val isDarkPx = gray[rowOffset + x] < threshold
                    if (isDarkPx) darkCount++ else lightCount++
                    if (x == 0 || x == w - 1 || y == 0 || y == h - 1) {
                        if (isDarkPx) darkBorderCount++ else lightBorderCount++
                    }
                }
            }
            val lightOnDark = darkBorderCount > lightBorderCount && darkCount > lightCount

            var inkCount = 0
            var inkSum = 0L
            var bgCount = 0
            var bgSum = 0L
            val ink = BooleanArray(gray.size)
            for (i in gray.indices) {
                val isInk = if (lightOnDark) gray[i] >= threshold else gray[i] < threshold
                if (isInk) {
                    ink[i] = true
                    inkCount++
                    inkSum += gray[i]
                } else {
                    bgCount++
                    bgSum += gray[i]
                }
            }
            if (inkCount < 10 || bgCount == 0) return false
            val contrast = if (lightOnDark) {
                (inkSum.toDouble() / inkCount) - (bgSum.toDouble() / bgCount)
            } else {
                (bgSum.toDouble() / bgCount) - (inkSum.toDouble() / inkCount)
            }
            if (contrast < 35.0) return false

            val visited = BooleanArray(ink.size)
            val queue = IntArray(ink.size)
            val totalPixels = w * h
            var glyphCount = 0

            for (start in ink.indices) {
                if (!ink[start] || visited[start]) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                visited[start] = true
                var area = 0
                var minX = w
                var maxX = -1
                var minY = h
                var maxY = -1

                while (head < tail) {
                    val idx = queue[head++]
                    val x = idx % w
                    val y = idx / w
                    area++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y

                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny < 0 || ny >= h) continue
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            if (nx < 0 || nx >= w) continue
                            val next = ny * w + nx
                            if (ink[next] && !visited[next]) {
                                visited[next] = true
                                queue[tail++] = next
                            }
                        }
                    }
                }

                val compW = maxX - minX + 1
                val compH = maxY - minY + 1
                val isLongBorder = (compW > w * 0.75f && compH <= 3) ||
                    (compH > h * 0.75f && compW <= 3)
                if (!isLongBorder && area in 5..(totalPixels / 6) && compW >= 2 && compH >= 4) {
                    glyphCount++
                    if (glyphCount >= 2) return true
                }
            }
            return false
        } finally {
            if (work !== crop && !work.isRecycled) work.recycle()
            if (crop !== bitmap && !crop.isRecycled) crop.recycle()
        }
    }

    private fun otsuThreshold(gray: IntArray): Int {
        val histogram = IntArray(256)
        for (value in gray) histogram[value.coerceIn(0, 255)]++
        val total = gray.size
        var sum = 0L
        for (i in 0 until 256) sum += i.toLong() * histogram[i]
        var sumBackground = 0L
        var weightBackground = 0
        var bestVariance = -1.0
        var bestThreshold = 127
        for (t in 0 until 256) {
            weightBackground += histogram[t]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += t.toLong() * histogram[t]
            val meanBackground = sumBackground.toDouble() / weightBackground
            val meanForeground = (sum - sumBackground).toDouble() / weightForeground
            val diff = meanBackground - meanForeground
            val variance = weightBackground.toDouble() * weightForeground * diff * diff
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = t
            }
        }
        return bestThreshold.coerceIn(1, 254)
    }

    private fun pixelLumaInt(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun pixelLuma(color: Int): Float = pixelLumaInt(color).toFloat()

    fun close() {
        synchronized(sessionLock) {
            isClosed = true
            val old = session
            session = null
            runCatching { old?.close() }
        }
        runCatching { detector.close() }
    }

    private fun ensureInitialized(): Boolean {
        if (isClosed) return false
        if (session != null && dictionary != null) return true
        if (initFailed) return false
        synchronized(sessionLock) {
            if (isClosed) return false
            if (session != null && dictionary != null) return true
            if (initFailed) return false
            try {
                cleanupLegacyParSeqCaches()
                val dict = readDictionary()
                val modelFile = ensureModelFile()
                if (modelFile == null) {
                    initFailed = true
                    return false
                }
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                }
                val loaded = try {
                    env.createSession(modelFile.absolutePath, options)
                } finally {
                    options.close()
                }
                ortEnv = env
                dictionary = dict
                session = loaded
                Log.d(TAG, "PP-OCRv5 recognizer loaded (${dict.size} dictionary entries)")
                return true
            } catch (t: Throwable) {
                initFailed = true
                Log.e(TAG, "PP-OCRv5 recognizer could not be loaded: ${t.message}", t)
                return false
            }
        }
    }

    private fun cleanupLegacyParSeqCaches() {
        for (fileName in LEGACY_PARSEQ_CACHE_FILES) {
            val legacyFile = File(appContext.filesDir, fileName)
            if (legacyFile.exists()) {
                runCatching { legacyFile.delete() }
            }
            val legacyTmp = File(appContext.filesDir, "$fileName.tmp")
            if (legacyTmp.exists()) {
                runCatching { legacyTmp.delete() }
            }
        }
    }

    private fun readDictionary(): List<String> {
        return appContext.assets.open(variant.dictAsset).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines().map { it.trimEnd('\r', '\n') }
        }
    }

    private fun isModelSizeValid(size: Long): Boolean {
        val expected = variant.expectedBytes
        return if (expected != null) size == expected else size >= MIN_GENERIC_MODEL_BYTES
    }

    private fun resolveAssetLength(assetPath: String): Long? = try {
        appContext.assets.openFd(assetPath).use { it.length }
    } catch (_: Exception) {
        null
    }

    private fun ensureModelFile(): File? {
        val target = File(appContext.filesDir, variant.cacheFileName)
        val matchedAsset = variant.modelAssets.firstOrNull { path ->
            try {
                appContext.assets.open(path).close()
                true
            } catch (_: Exception) {
                false
            }
        }
        val expectedSize = variant.expectedBytes ?: matchedAsset?.let { resolveAssetLength(it) }
        if (target.exists() && isModelSizeValid(target.length()) &&
            (expectedSize == null || target.length() == expectedSize)
        ) {
            return target
        }

        val temporary = File(appContext.filesDir, "${variant.cacheFileName}.tmp")
        return try {
            val sourceAsset = matchedAsset ?: return null
            appContext.assets.open(sourceAsset).use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            val copied = true
            if (!copied || !isModelSizeValid(temporary.length())) {
                temporary.delete()
                return null
            }
            if (target.exists() && !target.delete()) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            } else if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            if (isModelSizeValid(target.length())) target else null
        } catch (t: Throwable) {
            temporary.delete()
            Log.e(TAG, "PP-OCRv5 (${variant.name}) model copy failed: ${t.message}", t)
            null
        }
    }

    private fun sortHorizontalLinesInReadingOrder(
        lines: List<SegmentedTextLine>
    ): List<SegmentedTextLine> {
        if (lines.size <= 1) return lines
        val byY = lines.sortedWith(compareBy<SegmentedTextLine> { it.rect.centerY() }.thenBy { it.rect.left })
        val rows = ArrayList<MutableList<SegmentedTextLine>>()
        for (line in byY) {
            val cy = line.rect.exactCenterY()
            val h = line.rect.height().coerceAtLeast(1).toFloat()
            val targetRow = rows.lastOrNull()?.takeIf { row ->
                var sumCy = 0f
                var sumH = 0f
                for (item in row) {
                    sumCy += item.rect.exactCenterY()
                    sumH += item.rect.height().coerceAtLeast(1)
                }
                val avgCy = sumCy / row.size
                val avgH = sumH / row.size
                val minH = kotlin.math.min(avgH, h).coerceAtLeast(1f)
                kotlin.math.abs(cy - avgCy) <= minH * 0.50f
            }
            if (targetRow != null) targetRow.add(line) else rows.add(mutableListOf(line))
        }
        return rows.flatMap { row -> row.sortedBy { it.rect.left } }
    }
}
