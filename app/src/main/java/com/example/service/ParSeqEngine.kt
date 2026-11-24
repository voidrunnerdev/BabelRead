package com.example.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PARSeq (Permuted Autoregressive Sequence) English OCR engine for Manga & Comics.
 *
 * Uses docTR's optimized 8-bit dynamic INT8 PARSeq recognition ONNX model with
 * a permutation-aware autoregressive decoder that excels at stylized comic lettering
 * (CC Wild Words, Anime Ace, Blambot, all-caps diyaloglar) and irregular bubble layouts.
 */
class ParSeqEngine(private val context: Context) {

    data class LineResult(
        val text: String,
        val confidence: Float
    )

    companion object {
        private const val TAG = "BabelRead_ParSeq"
        private const val MODEL_ASSET = "models/parseq_rec.onnx"
        private const val CACHE_FILE_NAME = "parseq_rec_v1.onnx"
        private const val CONFIG_ASSET = "models/parseq_config.json"
        private const val INPUT_HEIGHT = 32
        private const val INPUT_WIDTH = 128
        private const val MIN_LINE_CONFIDENCE = 0.40f
        private const val MIN_WORD_CONFIDENCE = 0.25f
        private const val DARK_REGION_MEDIAN_LUMA = 95f
        private const val DARK_CROP_BORDER_LUMA = 100f
        private const val INVERT_CONTRAST = 1.85f

        // docTR / PARSeq default vocabulary for 8-bit dynamic model (126 characters + EOS at index 126)
        private const val DEFAULT_VOCAB =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~°£€¥¢฿àâéèêëîïôùûüçÀÂÉÈÊËÎÏÔÙÛÜÇ"

        fun hasModelAsset(context: Context): Boolean {
            return try {
                context.assets.open(MODEL_ASSET).use { it.available() > 0 }
            } catch (_: Exception) {
                false
            }
        }
    }

    private val detector = PpOcrV5Detector(context)
    private val lineSegmenter = TextLineSegmenter()
    private val initLock = Mutex()
    private val sessionLock = Any()
    private var ortEnv: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var vocab: String = DEFAULT_VOCAB
    private var isClosed = false

    fun isReady(): Boolean = session != null || ensureInitialized()

    suspend fun prewarm(): Boolean = withContext(Dispatchers.IO) {
        val loaded = ensureInitialized()
        if (loaded) detector.prewarm()
        loaded
    }

    private fun ensureInitialized(): Boolean {
        if (session != null) return true
        synchronized(sessionLock) {
            if (session != null) return true
            return try {
                loadModel()
            } catch (t: Throwable) {
                Log.e(TAG, "PARSeq initialization failed: ${t.message}", t)
                false
            }
        }
    }

    private fun loadModel(): Boolean {
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        if (!cacheFile.exists() || cacheFile.length() < 100_000L) {
            val copied = copyAssetToFile(MODEL_ASSET, cacheFile)
            if (!copied || !cacheFile.exists() || cacheFile.length() < 100_000L) {
                Log.e(TAG, "PARSeq asset $MODEL_ASSET could not be copied")
                return false
            }
        }

        // Load config / vocab if available
        try {
            context.assets.open(CONFIG_ASSET).bufferedReader().use { reader ->
                val jsonStr = reader.readText()
                val vocabMatch = Regex("\"vocab\"\\s*:\\s*\"([^\"]+)\"").find(jsonStr)
                if (vocabMatch != null) {
                    vocab = vocabMatch.groupValues[1]
                }
            }
        } catch (_: Exception) {
            vocab = DEFAULT_VOCAB
        }

        val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(cacheFile.absolutePath, opts)
        Log.d(TAG, "PARSeq engine loaded successfully (${cacheFile.length()} bytes, vocab=${vocab.length})")
        return true
    }

    private fun copyAssetToFile(assetPath: String, destFile: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
                if (destFile.exists()) destFile.delete()
                tempFile.renameTo(destFile)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to copy $assetPath to ${destFile.absolutePath}: ${t.message}")
            false
        }
    }

    suspend fun recognizeBlock(
        bitmap: Bitmap,
        region: Rect,
        originLanguage: String
    ): List<TranslationService.RectAndText> = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled || region.isEmpty) return@withContext emptyList()
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
            val detected = detector.detect(workBitmap, workRegion)
            val lines = if (detected.isNotEmpty()) {
                // PARSeq is English-only: always horizontal (never rotate -90°) and ordered top-to-bottom.
                detected
                    .sortedWith(compareBy<PpOcrV5Detector.DetectedLine> { it.rect.top }.thenBy { it.rect.left })
                    .map { SegmentedTextLine(Rect(it.rect), vertical = false) }
            } else {
                lineSegmenter.segment(
                    workBitmap,
                    workRegion,
                    originLanguage,
                    forceHorizontal = true
                )
            }
            if (lines.isEmpty()) return@withContext emptyList()

            val results = ArrayList<TranslationService.RectAndText>(lines.size)
            for (line in lines) {
                currentCoroutineContext().ensureActive()
                val recognized = recognizeLine(workBitmap, line.rect) ?: continue
                if (recognized.confidence < MIN_LINE_CONFIDENCE || recognized.text.isBlank()) {
                    Log.d(TAG, "PARSeq rejected low-confidence line: '${recognized.text}' (${recognized.confidence})")
                    continue
                }
                val pageRect = Rect(line.rect).apply { offset(offsetX, offsetY) }
                results.add(TranslationService.RectAndText(pageRect, recognized.text))
            }
            results
        } finally {
            if (workBitmap !== bitmap && !workBitmap.isRecycled) {
                workBitmap.recycle()
            }
        }
    }

    private suspend fun recognizeLine(
        bitmap: Bitmap,
        rect: Rect
    ): LineResult? = initLock.withLock {
        val safeRect = Rect(rect)
        if (!safeRect.intersect(0, 0, bitmap.width, bitmap.height) || safeRect.isEmpty) {
            return@withLock null
        }
        val crop = try {
            Bitmap.createBitmap(bitmap, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
        } catch (_: Exception) {
            return@withLock null
        }

        val normalized = normalizePolarityIfDark(crop)
        try {
            val wordRects = splitLineIntoWords(normalized)
            if (wordRects.size > 1) {
                val words = ArrayList<String>(wordRects.size)
                var weightedConfidenceSum = 0.0
                var totalWeight = 0
                for (wordRect in wordRects) {
                    val wordCrop = try {
                        Bitmap.createBitmap(
                            normalized,
                            wordRect.left,
                            wordRect.top,
                            wordRect.width(),
                            wordRect.height()
                        )
                    } catch (_: Exception) {
                        continue
                    }
                    try {
                        val wordResult = runRecognition(wordCrop)
                        if (wordResult != null && wordResult.text.isNotBlank() && wordResult.confidence >= MIN_WORD_CONFIDENCE) {
                            words.add(wordResult.text)
                            val weight = wordResult.text.length.coerceAtLeast(1)
                            weightedConfidenceSum += wordResult.confidence * weight
                            totalWeight += weight
                        }
                    } finally {
                        if (wordCrop !== normalized && !wordCrop.isRecycled) wordCrop.recycle()
                    }
                }
                if (words.isNotEmpty()) {
                    val joined = words.joinToString(" ")
                    val avgConf = if (totalWeight > 0) (weightedConfidenceSum / totalWeight).toFloat() else 0f
                    return@withLock LineResult(joined, avgConf)
                }
            }

            runRecognition(normalized)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "PARSeq line recognition failed: ${t.message}")
            null
        } finally {
            if (normalized !== crop && !normalized.isRecycled) normalized.recycle()
            if (crop !== bitmap && !crop.isRecycled) crop.recycle()
        }
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
        val p90Luma = samples[((samples.size - 1) * 9) / 10]
        if (medianLuma >= DARK_REGION_MEDIAN_LUMA || p90Luma <= 125f) return false

        // Verify outer perimeter is predominantly dark so a super-tight black-on-white
        // crop with dense ink is not misclassified as a dark bubble.
        var borderSamples = 0
        var darkBorderSamples = 0
        val borderStepX = max(1, w / 24)
        val borderStepY = max(1, h / 24)
        var bx = safe.left
        while (bx < safe.right) {
            if (pixelLuma(bitmap.getPixel(bx, safe.top)) < DARK_REGION_MEDIAN_LUMA) darkBorderSamples++
            borderSamples++
            if (h > 1) {
                if (pixelLuma(bitmap.getPixel(bx, safe.bottom - 1)) < DARK_REGION_MEDIAN_LUMA) darkBorderSamples++
                borderSamples++
            }
            bx += borderStepX
        }
        var by = safe.top
        while (by < safe.bottom) {
            if (pixelLuma(bitmap.getPixel(safe.left, by)) < DARK_REGION_MEDIAN_LUMA) darkBorderSamples++
            borderSamples++
            if (w > 1) {
                if (pixelLuma(bitmap.getPixel(safe.right - 1, by)) < DARK_REGION_MEDIAN_LUMA) darkBorderSamples++
                borderSamples++
            }
            by += borderStepY
        }
        val darkBorderFraction = if (borderSamples > 0) darkBorderSamples.toFloat() / borderSamples else 0f
        return darkBorderFraction >= 0.40f
    }

    internal fun isDarkBackgroundCrop(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        val w = bitmap.width
        val h = bitmap.height

        var borderLumaSum = 0.0
        var borderCount = 0
        val borderStepX = max(1, w / 32)
        val borderStepY = max(1, h / 16)
        var x = 0
        while (x < w) {
            borderLumaSum += pixelLuma(bitmap.getPixel(x, 0))
            borderCount++
            if (h > 1) {
                borderLumaSum += pixelLuma(bitmap.getPixel(x, h - 1))
                borderCount++
            }
            x += borderStepX
        }
        var y = 0
        while (y < h) {
            borderLumaSum += pixelLuma(bitmap.getPixel(0, y))
            borderCount++
            if (w > 1) {
                borderLumaSum += pixelLuma(bitmap.getPixel(w - 1, y))
                borderCount++
            }
            y += borderStepY
        }
        if (borderCount == 0) return false
        val borderAvgLuma = (borderLumaSum / borderCount).toFloat()
        if (borderAvgLuma >= DARK_CROP_BORDER_LUMA) return false

        var maxLuma = 0f
        val stepX = max(1, w / 24)
        val stepY = max(1, h / 16)
        var iy = 0
        while (iy < h) {
            var ix = 0
            while (ix < w) {
                val luma = pixelLuma(bitmap.getPixel(ix, iy))
                if (luma > maxLuma) maxLuma = luma
                ix += stepX
            }
            iy += stepY
        }
        return maxLuma > borderAvgLuma + 30f && maxLuma >= 120f
    }

    internal fun invertHighContrastBitmap(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val color = pixels[i]
            val gray = pixelLuma(color)
            val out = ((127.5f - gray) * INVERT_CONTRAST + 127.5f).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(out, out, out)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    internal fun normalizePolarityIfDark(crop: Bitmap): Bitmap =
        if (isDarkBackgroundCrop(crop)) invertHighContrastBitmap(crop) else crop

    private data class ColumnSpan(
        val startX: Int,
        val endX: Int,
        val inkPixels: Int,
        val minY: Int,
        val maxY: Int
    )

    internal fun splitLineIntoWords(crop: Bitmap): List<Rect> {
        if (crop.isRecycled || crop.width < 4 || crop.height < 4) return emptyList()
        val w = crop.width
        val h = crop.height
        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = IntArray(pixels.size) { idx ->
            val c = pixels[idx]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }
        val minGray = gray.minOrNull() ?: 255
        val maxGray = gray.maxOrNull() ?: 0
        if (maxGray - minGray < 20) return listOf(Rect(0, 0, w, h))

        val threshold = otsuThreshold(gray)
        val ink = BooleanArray(gray.size) { gray[it] < threshold }

        val rowInk = IntArray(h)
        var maxRowInk = 0
        for (y in 0 until h) {
            var count = 0
            val offset = y * w
            for (x in 0 until w) {
                if (ink[offset + x]) count++
            }
            rowInk[y] = count
        }
        val isBorderRow = BooleanArray(h) { y ->
            (y <= 1 || y >= h - 2) && rowInk[y] > (w * 0.75f).toInt()
        }
        for (y in 0 until h) {
            if (!isBorderRow[y] && rowInk[y] > maxRowInk) {
                maxRowInk = rowInk[y]
            }
        }
        if (maxRowInk <= 0) return listOf(Rect(0, 0, w, h))

        val rowActiveThreshold = max(1, (maxRowInk * 0.04f).toInt())
        var inkTop = -1
        var inkBottom = -1
        for (y in 0 until h) {
            if (!isBorderRow[y] && rowInk[y] >= rowActiveThreshold) {
                if (inkTop < 0) inkTop = y
                inkBottom = y
            }
        }
        if (inkTop < 0 || inkBottom < inkTop) return listOf(Rect(0, 0, w, h))
        val inkHeight = (inkBottom - inkTop + 1).coerceAtLeast(1)

        val colInk = IntArray(w)
        val colMinY = IntArray(w) { h }
        val colMaxY = IntArray(w) { -1 }
        for (y in inkTop..inkBottom) {
            if (isBorderRow[y]) continue
            val offset = y * w
            for (x in 0 until w) {
                if (ink[offset + x]) {
                    colInk[x]++
                    if (y < colMinY[x]) colMinY[x] = y
                    if (y > colMaxY[x]) colMaxY[x] = y
                }
            }
        }

        val rawSpans = ArrayList<ColumnSpan>()
        var spanStart = -1
        var spanPixels = 0
        var spanMinY = h
        var spanMaxY = -1
        for (x in 0..w) {
            val active = x < w && colInk[x] > 0
            if (active) {
                if (spanStart < 0) {
                    spanStart = x
                    spanPixels = 0
                    spanMinY = h
                    spanMaxY = -1
                }
                spanPixels += colInk[x]
                if (colMinY[x] < spanMinY) spanMinY = colMinY[x]
                if (colMaxY[x] > spanMaxY) spanMaxY = colMaxY[x]
            } else if (spanStart >= 0) {
                if (spanPixels >= 2 && spanMaxY >= spanMinY) {
                    rawSpans.add(ColumnSpan(spanStart, x - 1, spanPixels, spanMinY, spanMaxY))
                }
                spanStart = -1
            }
        }

        if (rawSpans.size <= 1) return listOf(Rect(0, 0, w, h))

        val wordGapThreshold = max(3, (inkHeight * 0.20f).roundToInt())
        val punctuationGapLimit = max(wordGapThreshold + 2, (wordGapThreshold * 1.5f).roundToInt())
        val smallPunctMaxWidth = max(4, (inkHeight * 0.25f).roundToInt())

        val mergedWords = ArrayList<ColumnSpan>()
        var current = rawSpans[0]
        for (i in 1 until rawSpans.size) {
            val next = rawSpans[i]
            val gap = next.startX - current.endX - 1
            val nextWidth = next.endX - next.startX + 1
            val nextHeight = next.maxY - next.minY + 1
            val isSmallPunctuation = nextWidth <= smallPunctMaxWidth && nextHeight < inkHeight * 0.45f
            val shouldMerge = gap < wordGapThreshold || (isSmallPunctuation && gap < punctuationGapLimit)
            if (shouldMerge) {
                current = ColumnSpan(
                    startX = current.startX,
                    endX = next.endX,
                    inkPixels = current.inkPixels + next.inkPixels,
                    minY = min(current.minY, next.minY),
                    maxY = max(current.maxY, next.maxY)
                )
            } else {
                mergedWords.add(current)
                current = next
            }
        }
        mergedWords.add(current)

        if (mergedWords.size <= 1) return listOf(Rect(0, 0, w, h))

        val padX = 2
        val padY = 2
        val rects = ArrayList<Rect>(mergedWords.size)
        for (i in mergedWords.indices) {
            val word = mergedWords[i]
            var left = (word.startX - padX).coerceAtLeast(0)
            var right = (word.endX + 1 + padX).coerceAtMost(w)
            if (i > 0) {
                val prevEnd = mergedWords[i - 1].endX
                val mid = (prevEnd + word.startX + 1) / 2
                left = max(left, mid)
            }
            if (i + 1 < mergedWords.size) {
                val nextStart = mergedWords[i + 1].startX
                val mid = (word.endX + nextStart + 1) / 2
                right = min(right, mid)
            }
            val top = (word.minY - padY).coerceAtLeast(0)
            val bottom = (word.maxY + 1 + padY).coerceAtMost(h)
            if (right > left && bottom > top) {
                rects.add(Rect(left, top, right, bottom))
            }
        }
        return if (rects.isNotEmpty()) rects else listOf(Rect(0, 0, w, h))
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

    private fun pixelLuma(color: Int): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299f + g * 587f + b * 114f) / 1000f
    }

    internal fun prepareInputBitmap(bitmap: Bitmap): Bitmap? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null

        val normalized = normalizePolarityIfDark(bitmap)
        var paddedCrop: Bitmap? = null
        var lineScaled: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            // Add a small white paper margin around tight word crops so thin edge glyphs
            // (e.g. 'I', 'T', '!') are never clipped or merged into zero-padding.
            val margin = (normalized.height * 0.10f).roundToInt().coerceIn(2, 6)
            val paddedW = normalized.width + margin * 2
            val paddedH = normalized.height + margin * 2
            paddedCrop = Bitmap.createBitmap(paddedW, paddedH, Bitmap.Config.ARGB_8888)
            Canvas(paddedCrop).apply {
                drawColor(Color.WHITE)
                drawBitmap(normalized, margin.toFloat(), margin.toFloat(), null)
            }

            val ratio = paddedW.toFloat() / paddedH.toFloat()
            val maxRatio = INPUT_WIDTH.toFloat() / INPUT_HEIGHT.toFloat()
            val targetWidth: Int
            val targetHeight: Int
            if (ratio > maxRatio) {
                targetWidth = INPUT_WIDTH
                targetHeight = ceil(INPUT_WIDTH / ratio).toInt().coerceIn(1, INPUT_HEIGHT)
            } else {
                targetWidth = ceil(INPUT_HEIGHT * ratio).toInt().coerceIn(1, INPUT_WIDTH)
                targetHeight = INPUT_HEIGHT
            }

            lineScaled = Bitmap.createScaledBitmap(paddedCrop, targetWidth, targetHeight, true)
            scaled = Bitmap.createBitmap(INPUT_WIDTH, INPUT_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(scaled)
            // docTR Resize(preserve_aspect_ratio=True) pads unused canvas with 0 (Color.BLACK)
            // before mean/std normalization.
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(lineScaled, 0f, 0f, null)

            val result = scaled
            scaled = null
            return result
        } catch (_: Exception) {
            return null
        } finally {
            if (lineScaled != null && lineScaled !== paddedCrop && !lineScaled.isRecycled) lineScaled.recycle()
            if (paddedCrop != null && paddedCrop !== normalized && !paddedCrop.isRecycled) paddedCrop.recycle()
            if (normalized !== bitmap && !normalized.isRecycled) normalized.recycle()
            if (scaled != null && !scaled.isRecycled) scaled.recycle()
        }
    }

    private fun runRecognition(bitmap: Bitmap): LineResult? {
        val activeSession = session ?: return null
        val scaled = prepareInputBitmap(bitmap) ?: return null

        try {
            val pixels = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
            scaled.getPixels(pixels, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)
            val planeSize = INPUT_WIDTH * INPUT_HEIGHT
            val buffer = ByteBuffer.allocateDirect(planeSize * 3 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()

            // docTR / PARSeq normalization: RGB order, mean=[0.694, 0.695, 0.693], std=[0.299, 0.296, 0.301]
            for (y in 0 until INPUT_HEIGHT) {
                val rowOffset = y * INPUT_WIDTH
                for (x in 0 until INPUT_WIDTH) {
                    val idx = rowOffset + x
                    val color = pixels[idx]
                    val r = (color shr 16 and 0xFF) / 255.0f
                    val g = (color shr 8 and 0xFF) / 255.0f
                    val b = (color and 0xFF) / 255.0f
                    buffer.put(idx, (r - 0.694f) / 0.299f)
                    buffer.put(planeSize + idx, (g - 0.695f) / 0.296f)
                    buffer.put(planeSize * 2 + idx, (b - 0.693f) / 0.301f)
                }
            }
            buffer.position(0)

            synchronized(sessionLock) {
                if (isClosed) return null
                val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
                val shape = longArrayOf(1, 3, INPUT_HEIGHT.toLong(), INPUT_WIDTH.toLong())
                val inputName = activeSession.inputNames.first()
                OnnxTensor.createTensor(env, buffer, shape).use { inputTensor ->
                    activeSession.run(mapOf(inputName to inputTensor)).use { result ->
                        val output = result.get(0) as? OnnxTensor ?: return null
                        val outputShape = output.info.shape
                        if (outputShape.size != 3) return null
                        val timesteps = outputShape[1].toInt()
                        val classCount = outputShape[2].toInt()
                        val values = output.floatBuffer
                        return decodeParSeq(values, timesteps, classCount, vocab)
                    }
                }
            }
        } finally {
            if (!scaled.isRecycled) scaled.recycle()
        }
    }

    internal fun decodeParSeq(
        values: FloatBuffer,
        timesteps: Int,
        classCount: Int,
        vocab: String
    ): LineResult {
        val builder = StringBuilder()
        var confidenceSum = 0.0
        var charCount = 0
        // In docTR / OnnxTR PARSeq ONNX, output shape is [1, 33, classCount].
        // Valid character indices are 0 until vocab.length.
        // Index vocab.length (or classCount - 1) is <eos>.
        val eosIndex = minOf(vocab.length, classCount - 1)

        for (t in 0 until timesteps) {
            val base = t * classCount
            var bestIndex = 0
            var bestLogit = values.get(base)
            for (c in 1 until classCount) {
                val v = values.get(base + c)
                if (v > bestLogit) {
                    bestLogit = v
                    bestIndex = c
                }
            }

            // Stop decoding immediately once EOS or any special/out-of-bounds token is reached
            if (bestIndex >= eosIndex) {
                break
            }

            if (bestIndex < vocab.length) {
                builder.append(vocab[bestIndex])
                // Softmax probability approximation for confidence
                var sumExp = 0.0
                for (c in 0 until classCount) {
                    sumExp += exp((values.get(base + c) - bestLogit).toDouble().coerceIn(-40.0, 40.0))
                }
                val prob = if (sumExp > 0.0) (1.0 / sumExp).toFloat().coerceIn(0f, 1f) else 1f
                confidenceSum += prob
                charCount++
            }
        }

        val text = builder.toString().trim()
        val confidence = if (charCount > 0) (confidenceSum / charCount).toFloat() else 0f
        return LineResult(text, confidence)
    }

    fun close() {
        synchronized(sessionLock) {
            isClosed = true
            try {
                session?.close()
                session = null
            } catch (_: Exception) {}
            try {
                detector.close()
            } catch (_: Exception) {}
        }
    }
}
