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
import org.json.JSONObject
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
 * Uses OnnxTR v0.9.0 8-bit dynamic INT8 PARSeq recognition ONNX model with
 * a permutation-aware autoregressive decoder that excels at stylized comic lettering
 * (CC Wild Words, Anime Ace, Blambot, all-caps diyaloglar) and irregular bubble layouts.
 */
class ParSeqEngine(private val context: Context) {

    data class LineResult(
        val text: String,
        val confidence: Float,
        val minCharConfidence: Float = confidence
    )

    companion object {
        private const val TAG = "BabelRead_ParSeq"
        private const val MODEL_ASSET = "models/parseq_rec.onnx"
        private const val CACHE_FILE_NAME = "parseq_rec_v2_090.onnx"
        private const val LEGACY_CACHE_FILE_NAME = "parseq_rec_v1.onnx"
        private const val CONFIG_ASSET = "models/parseq_config.json"
        private const val INPUT_HEIGHT = 32
        private const val INPUT_WIDTH = 128
        private const val MIN_LINE_CONFIDENCE = 0.60f
        private const val MIN_WORD_CONFIDENCE = 0.55f
        private const val MIN_CHAR_PROB = 0.25f
        private const val UPSCALE_HEIGHT_THRESHOLD = 36
        private const val SPLIT_CROP_RATIO_THRESHOLD = 4.5f
        private const val SPLIT_CROP_OVERLAP = 0.35f
        private const val UNSHARP_AMOUNT = 0.45f
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
        val legacyCacheFile = File(context.filesDir, LEGACY_CACHE_FILE_NAME)
        if (legacyCacheFile.exists()) {
            legacyCacheFile.delete()
        }
        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        if (!cacheFile.exists() || cacheFile.length() < 100_000L) {
            val copied = copyAssetToFile(MODEL_ASSET, cacheFile)
            if (!copied || !cacheFile.exists() || cacheFile.length() < 100_000L) {
                Log.e(TAG, "PARSeq asset $MODEL_ASSET could not be copied")
                return false
            }
        }

        // Load config / vocab properly via JSONObject so escaped quotes (e.g. \") do not truncate vocab.
        vocab = try {
            context.assets.open(CONFIG_ASSET).bufferedReader().use { reader ->
                parseVocabFromConfigJson(reader.readText()) ?: DEFAULT_VOCAB
            }
        } catch (_: Exception) {
            DEFAULT_VOCAB
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

    internal fun parseVocabFromConfigJson(jsonStr: String): String? {
        return try {
            val parsed = JSONObject(jsonStr).optString("vocab", "")
            if (parsed.length >= 100) parsed else null
        } catch (_: Exception) {
            null
        }
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
            } else if (hasValidTextRegionComponents(workBitmap, workRegion)) {
                lineSegmenter.segment(
                    workBitmap,
                    workRegion,
                    originLanguage,
                    forceHorizontal = true
                )
            } else {
                emptyList()
            }
            if (lines.isEmpty()) return@withContext emptyList()

            val results = ArrayList<TranslationService.RectAndText>(lines.size)
            for (line in lines) {
                currentCoroutineContext().ensureActive()
                val recognized = recognizeLine(workBitmap, line.rect) ?: continue
                if (recognized.confidence < MIN_LINE_CONFIDENCE ||
                    recognized.minCharConfidence < MIN_CHAR_PROB ||
                    recognized.text.isBlank()
                ) {
                    Log.d(
                        TAG,
                        "PARSeq rejected low-confidence line: '${recognized.text}' " +
                            "(avg=${recognized.confidence}, minChar=${recognized.minCharConfidence})"
                    )
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
            if (!hasValidTextStrokes(normalized)) {
                return@withLock null
            }
            val wordRects = splitLineIntoWords(normalized)
            if (wordRects.size > 1) {
                val words = ArrayList<String>(wordRects.size)
                var weightedConfidenceSum = 0.0
                var totalWeight = 0
                var minCharProb = 1.0f
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
                        if (!hasValidTextStrokes(wordCrop)) continue
                        val wordResult = recognizeCropWithSplit(wordCrop)
                        if (wordResult != null &&
                            wordResult.text.isNotBlank() &&
                            wordResult.confidence >= MIN_WORD_CONFIDENCE &&
                            wordResult.minCharConfidence >= MIN_CHAR_PROB
                        ) {
                            words.add(wordResult.text)
                            val weight = wordResult.text.length.coerceAtLeast(1)
                            weightedConfidenceSum += wordResult.confidence * weight
                            totalWeight += weight
                            if (wordResult.minCharConfidence < minCharProb) {
                                minCharProb = wordResult.minCharConfidence
                            }
                        }
                    } finally {
                        if (wordCrop !== normalized && !wordCrop.isRecycled) wordCrop.recycle()
                    }
                }
                if (words.isNotEmpty()) {
                    val joined = words.joinToString(" ")
                    val avgConf = if (totalWeight > 0) (weightedConfidenceSum / totalWeight).toFloat() else 0f
                    return@withLock LineResult(joined, avgConf, minCharProb)
                }
            }

            recognizeCropWithSplit(normalized)
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
        val origW = crop.width
        val origH = crop.height
        val scale = if (origH < UPSCALE_HEIGHT_THRESHOLD) 2 else 1
        val work = if (scale > 1) {
            try {
                Bitmap.createScaledBitmap(crop, origW * scale, origH * scale, true)
            } catch (_: Exception) {
                crop
            }
        } else {
            crop
        }
        val activeScale = if (work === crop) 1 else scale

        try {
            val w = work.width
            val h = work.height
            val pixels = IntArray(w * h)
            work.getPixels(pixels, 0, w, 0, 0, w, h)

            val gray = IntArray(pixels.size) { idx ->
                val c = pixels[idx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                (r * 299 + g * 587 + b * 114) / 1000
            }
            val minGray = gray.minOrNull() ?: 255
            val maxGray = gray.maxOrNull() ?: 0
            if (maxGray - minGray < 20) return listOf(Rect(0, 0, origW, origH))

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
            val borderBand = max(2, activeScale * 2)
            val isBorderRow = BooleanArray(h) { y ->
                (y < borderBand || y >= h - borderBand) && rowInk[y] > (w * 0.75f).toInt()
            }
            for (y in 0 until h) {
                if (!isBorderRow[y] && rowInk[y] > maxRowInk) {
                    maxRowInk = rowInk[y]
                }
            }
            if (maxRowInk <= 0) return listOf(Rect(0, 0, origW, origH))

            val rowActiveThreshold = max(1, (maxRowInk * 0.04f).toInt())
            var inkTop = -1
            var inkBottom = -1
            for (y in 0 until h) {
                if (!isBorderRow[y] && rowInk[y] >= rowActiveThreshold) {
                    if (inkTop < 0) inkTop = y
                    inkBottom = y
                }
            }
            if (inkTop < 0 || inkBottom < inkTop) return listOf(Rect(0, 0, origW, origH))
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

            val minSpanPixels = max(2, activeScale * activeScale)
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
                    if (spanPixels >= minSpanPixels && spanMaxY >= spanMinY) {
                        rawSpans.add(ColumnSpan(spanStart, x - 1, spanPixels, spanMinY, spanMaxY))
                    }
                    spanStart = -1
                }
            }

            if (rawSpans.size <= 1) return listOf(Rect(0, 0, origW, origH))

            // Bimodal gap analysis: compare inter-letter gap median against candidate word gaps.
            val rawGaps = ArrayList<Int>(rawSpans.size - 1)
            for (idx in 1 until rawSpans.size) {
                val g = rawSpans[idx].startX - rawSpans[idx - 1].endX - 1
                if (g > 0) rawGaps.add(g)
            }
            val baseThreshold = max(3 * activeScale, (inkHeight * 0.19f).roundToInt())
            val maxLetterGap = (inkHeight * 0.32f).roundToInt()
            val letterGaps = rawGaps.filter { it in 1..maxLetterGap }.sorted()
            val wordGapThreshold = if (letterGaps.isNotEmpty()) {
                val medianLetterGap = letterGaps[letterGaps.size / 2]
                max(baseThreshold, (medianLetterGap * 2.2f).roundToInt())
                    .coerceAtMost(max(baseThreshold, (inkHeight * 0.42f).roundToInt()))
            } else {
                baseThreshold
            }

            val maxJoinerGap = max(wordGapThreshold * 2, (inkHeight * 0.45f).roundToInt())
            val smallPunctMaxWidth = max(4 * activeScale, (inkHeight * 0.25f).roundToInt())
            val punctuationGapLimit = max(wordGapThreshold + 2 * activeScale, (wordGapThreshold * 1.5f).roundToInt())

            val mergedWords = ArrayList<ColumnSpan>()
            var current = rawSpans[0]
            var i = 1
            while (i < rawSpans.size) {
                val next = rawSpans[i]
                val gap = next.startX - current.endX - 1
                val nextWidth = next.endX - next.startX + 1
                val nextHeight = next.maxY - next.minY + 1

                // Protect hyphen ('-') and apostrophe (''') joiners between adjacent letters
                // (e.g. "B-BUT", "H-HOW", "OHASHI-KUN", "COULDN'T") so they never split mid-word.
                if (i + 1 < rawSpans.size && isJoinerPunctuation(next, inkTop, inkHeight)) {
                    val right = rawSpans[i + 1]
                    val rightGap = right.startX - next.endX - 1
                    if (gap <= maxJoinerGap && rightGap <= maxJoinerGap) {
                        current = mergeColumnSpans(mergeColumnSpans(current, next), right)
                        i += 2
                        continue
                    }
                }

                val isSmallTrailingPunct = nextWidth <= smallPunctMaxWidth && nextHeight < inkHeight * 0.45f
                val shouldMerge = gap < wordGapThreshold || (isSmallTrailingPunct && gap < punctuationGapLimit)
                if (shouldMerge) {
                    current = mergeColumnSpans(current, next)
                } else {
                    mergedWords.add(current)
                    current = next
                }
                i++
            }
            mergedWords.add(current)

            if (mergedWords.size <= 1) return listOf(Rect(0, 0, origW, origH))

            val padX = 2
            val padY = 2
            val rects = ArrayList<Rect>(mergedWords.size)
            for (idx in mergedWords.indices) {
                val word = mergedWords[idx]
                val origStartX = word.startX / activeScale
                val origEndX = ceil((word.endX + 1) / activeScale.toFloat()).toInt() - 1
                val origMinY = word.minY / activeScale
                val origMaxY = ceil((word.maxY + 1) / activeScale.toFloat()).toInt() - 1

                var left = (origStartX - padX).coerceAtLeast(0)
                var right = (origEndX + 1 + padX).coerceAtMost(origW)
                if (idx > 0) {
                    val prevOrigEndX = ceil((mergedWords[idx - 1].endX + 1) / activeScale.toFloat()).toInt() - 1
                    val mid = (prevOrigEndX + origStartX + 1) / 2
                    left = max(left, mid)
                }
                if (idx + 1 < mergedWords.size) {
                    val nextOrigStartX = mergedWords[idx + 1].startX / activeScale
                    val mid = (origEndX + nextOrigStartX + 1) / 2
                    right = min(right, mid)
                }
                val top = (origMinY - padY).coerceAtLeast(0)
                val bottom = (origMaxY + 1 + padY).coerceAtMost(origH)
                if (right > left && bottom > top) {
                    rects.add(Rect(left, top, right, bottom))
                }
            }
            return if (rects.isNotEmpty()) rects else listOf(Rect(0, 0, origW, origH))
        } finally {
            if (work !== crop && !work.isRecycled) work.recycle()
        }
    }

    private fun mergeColumnSpans(first: ColumnSpan, second: ColumnSpan): ColumnSpan = ColumnSpan(
        startX = min(first.startX, second.startX),
        endX = max(first.endX, second.endX),
        inkPixels = first.inkPixels + second.inkPixels,
        minY = min(first.minY, second.minY),
        maxY = max(first.maxY, second.maxY)
    )

    private fun isJoinerPunctuation(span: ColumnSpan, inkTop: Int, inkHeight: Int): Boolean {
        val spanWidth = span.endX - span.startX + 1
        val spanHeight = span.maxY - span.minY + 1
        val isHyphen = spanHeight <= inkHeight * 0.35f &&
            spanWidth <= inkHeight * 0.65f &&
            span.minY >= inkTop + inkHeight * 0.22f &&
            span.maxY <= inkTop + inkHeight * 0.78f
        val isApostrophe = spanHeight <= inkHeight * 0.42f &&
            spanWidth <= inkHeight * 0.45f &&
            span.maxY <= inkTop + inkHeight * 0.55f
        return isHyphen || isApostrophe
    }

    internal fun hasValidTextStrokes(crop: Bitmap): Boolean {
        if (crop.isRecycled || crop.width < 3 || crop.height < 6) return false
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
        if (maxGray - minGray < 35) return false

        val threshold = otsuThreshold(gray)
        var inkCount = 0
        var inkSum = 0L
        var bgCount = 0
        var bgSum = 0L
        val ink = BooleanArray(gray.size)
        for (i in gray.indices) {
            if (gray[i] < threshold) {
                ink[i] = true
                inkCount++
                inkSum += gray[i]
            } else {
                bgCount++
                bgSum += gray[i]
            }
        }
        if (inkCount < 6 || bgCount == 0) return false
        val inkRatio = inkCount.toFloat() / gray.size
        if (inkRatio < 0.02f || inkRatio > 0.78f) return false

        val meanInk = inkSum.toDouble() / inkCount
        val meanBg = bgSum.toDouble() / bgCount
        if (meanBg - meanInk < 35.0) return false

        var minInkX = w
        var maxInkX = -1
        var minInkY = h
        var maxInkY = -1
        var rowsWithTransitions = 0
        for (y in 0 until h) {
            val offset = y * w
            var prev = false
            var transitions = 0
            for (x in 0 until w) {
                val cur = ink[offset + x]
                if (cur) {
                    if (x < minInkX) minInkX = x
                    if (x > maxInkX) maxInkX = x
                    if (y < minInkY) minInkY = y
                    if (y > maxInkY) maxInkY = y
                }
                if (cur != prev) {
                    transitions++
                    prev = cur
                }
            }
            if (prev) transitions++
            if (transitions >= 2) rowsWithTransitions++
        }
        if (maxInkY < minInkY || maxInkX < minInkX) return false
        val inkWidth = maxInkX - minInkX + 1
        val inkHeight = maxInkY - minInkY + 1
        if (inkHeight < max(4, (h * 0.20f).roundToInt())) return false
        if (inkHeight <= 2 && w > h * 3) return false
        if (inkWidth <= 2 && h > w * 3 && inkRatio < 0.08f) return false

        return rowsWithTransitions >= max(3, inkHeight / 4)
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
            val gray = IntArray(pixels.size) { idx ->
                val c = pixels[idx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                (r * 299 + g * 587 + b * 114) / 1000
            }
            val minGray = gray.minOrNull() ?: 255
            val maxGray = gray.maxOrNull() ?: 0
            if (maxGray - minGray < 35) return false

            val threshold = otsuThreshold(gray)
            var inkCount = 0
            var inkSum = 0L
            var bgCount = 0
            var bgSum = 0L
            val ink = BooleanArray(gray.size)
            for (i in gray.indices) {
                if (gray[i] < threshold) {
                    ink[i] = true
                    inkCount++
                    inkSum += gray[i]
                } else {
                    bgCount++
                    bgSum += gray[i]
                }
            }
            if (inkCount < 10 || bgCount == 0) return false
            if ((bgSum.toDouble() / bgCount) - (inkSum.toDouble() / inkCount) < 35.0) return false

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

    private fun pixelLuma(color: Int): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299f + g * 587f + b * 114f) / 1000f
    }

    internal fun enhanceCropForOcr(crop: Bitmap): Bitmap {
        val lightCrop = normalizePolarityIfDark(crop)
        val upscaled = if (lightCrop.height < UPSCALE_HEIGHT_THRESHOLD) {
            try {
                Bitmap.createScaledBitmap(lightCrop, lightCrop.width * 2, lightCrop.height * 2, true)
            } catch (_: Exception) {
                lightCrop
            }
        } else {
            lightCrop
        }
        if (upscaled !== lightCrop && lightCrop !== crop && !lightCrop.isRecycled) {
            lightCrop.recycle()
        }

        val w = upscaled.width
        val h = upscaled.height
        val pixels = IntArray(w * h)
        upscaled.getPixels(pixels, 0, w, 0, 0, w, h)

        val hist = IntArray(256)
        for (c in pixels) {
            val luma = (((c shr 16 and 0xFF) * 299) + ((c shr 8 and 0xFF) * 587) + ((c and 0xFF) * 114)) / 1000
            hist[luma.coerceIn(0, 255)]++
        }
        val total = pixels.size
        val lowTarget = (total * 0.02f).roundToInt()
        val highTarget = (total * 0.98f).roundToInt()
        var acc = 0
        var p2 = 0
        var p98 = 255
        var foundLow = false
        for (i in 0 until 256) {
            acc += hist[i]
            if (!foundLow && acc >= lowTarget) {
                p2 = i
                foundLow = true
            }
            if (acc >= highTarget) {
                p98 = i
                break
            }
        }

        // Smooth 2%-98% percentile contrast stretch preserving anti-aliasing (no binary thresholding).
        if (p98 - p2 >= 20 && (p2 > 4 || p98 < 251)) {
            val scale = 255f / (p98 - p2)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = (((c shr 16 and 0xFF) - p2) * scale).roundToInt().coerceIn(0, 255)
                val g = (((c shr 8 and 0xFF) - p2) * scale).roundToInt().coerceIn(0, 255)
                val b = (((c and 0xFF) - p2) * scale).roundToInt().coerceIn(0, 255)
                pixels[i] = Color.rgb(r, g, b)
            }
        }

        // Mild 3x3 Unsharp Mask when crop is large enough to filter cleanly.
        if (w >= 3 && h >= 3) {
            val sharpened = IntArray(pixels.size)
            for (y in 0 until h) {
                val yPrev = max(0, y - 1)
                val yNext = min(h - 1, y + 1)
                for (x in 0 until w) {
                    val xPrev = max(0, x - 1)
                    val xNext = min(w - 1, x + 1)

                    val c00 = pixels[yPrev * w + xPrev]
                    val c10 = pixels[yPrev * w + x]
                    val c20 = pixels[yPrev * w + xNext]
                    val c01 = pixels[y * w + xPrev]
                    val c11 = pixels[y * w + x]
                    val c21 = pixels[y * w + xNext]
                    val c02 = pixels[yNext * w + xPrev]
                    val c12 = pixels[yNext * w + x]
                    val c22 = pixels[yNext * w + xNext]

                    val blurR = (
                        (c00 shr 16 and 0xFF) + 2 * (c10 shr 16 and 0xFF) + (c20 shr 16 and 0xFF) +
                            2 * (c01 shr 16 and 0xFF) + 4 * (c11 shr 16 and 0xFF) + 2 * (c21 shr 16 and 0xFF) +
                            (c02 shr 16 and 0xFF) + 2 * (c12 shr 16 and 0xFF) + (c22 shr 16 and 0xFF)
                        ) / 16f
                    val blurG = (
                        (c00 shr 8 and 0xFF) + 2 * (c10 shr 8 and 0xFF) + (c20 shr 8 and 0xFF) +
                            2 * (c01 shr 8 and 0xFF) + 4 * (c11 shr 8 and 0xFF) + 2 * (c21 shr 8 and 0xFF) +
                            (c02 shr 8 and 0xFF) + 2 * (c12 shr 8 and 0xFF) + (c22 shr 8 and 0xFF)
                        ) / 16f
                    val blurB = (
                        (c00 and 0xFF) + 2 * (c10 and 0xFF) + (c20 and 0xFF) +
                            2 * (c01 and 0xFF) + 4 * (c11 and 0xFF) + 2 * (c21 and 0xFF) +
                            (c02 and 0xFF) + 2 * (c12 and 0xFF) + (c22 and 0xFF)
                        ) / 16f

                    val origR = c11 shr 16 and 0xFF
                    val origG = c11 shr 8 and 0xFF
                    val origB = c11 and 0xFF

                    val outR = (origR + UNSHARP_AMOUNT * (origR - blurR)).roundToInt().coerceIn(0, 255)
                    val outG = (origG + UNSHARP_AMOUNT * (origG - blurG)).roundToInt().coerceIn(0, 255)
                    val outB = (origB + UNSHARP_AMOUNT * (origB - blurB)).roundToInt().coerceIn(0, 255)
                    sharpened[y * w + x] = Color.rgb(outR, outG, outB)
                }
            }
            System.arraycopy(sharpened, 0, pixels, 0, pixels.size)
        }

        val outBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
        if (upscaled !== crop && !upscaled.isRecycled) upscaled.recycle()
        return outBitmap
    }

    internal fun prepareInputBitmap(bitmap: Bitmap): Bitmap? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null

        val enhanced = enhanceCropForOcr(bitmap)
        var paddedCrop: Bitmap? = null
        var lineScaled: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            // Add a clean white paper margin around tight word crops so edge glyphs
            // (e.g. 'I', 'T', '!') are never clipped.
            val margin = (enhanced.height * 0.10f).roundToInt().coerceIn(2, 6)
            val paddedW = enhanced.width + margin * 2
            val paddedH = enhanced.height + margin * 2
            paddedCrop = Bitmap.createBitmap(paddedW, paddedH, Bitmap.Config.ARGB_8888)
            Canvas(paddedCrop).apply {
                drawColor(Color.WHITE)
                drawBitmap(enhanced, margin.toFloat(), margin.toFloat(), null)
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
            // Use clean white paper background + symmetric_pad so short words sit centered
            // with equal white margin and never hit an artificial Color.BLACK right-edge wall.
            canvas.drawColor(Color.WHITE)
            val offsetX = (INPUT_WIDTH - targetWidth) / 2f
            val offsetY = (INPUT_HEIGHT - targetHeight) / 2f
            canvas.drawBitmap(lineScaled, offsetX, offsetY, null)

            val result = scaled
            scaled = null
            return result
        } catch (_: Exception) {
            return null
        } finally {
            if (lineScaled != null && lineScaled !== paddedCrop && !lineScaled.isRecycled) lineScaled.recycle()
            if (paddedCrop != null && paddedCrop !== enhanced && !paddedCrop.isRecycled) paddedCrop.recycle()
            if (enhanced !== bitmap && !enhanced.isRecycled) enhanced.recycle()
            if (scaled != null && !scaled.isRecycled) scaled.recycle()
        }
    }

    internal fun mergeMultiStrings(parts: List<String>): String {
        val nonBlank = parts.map { it.trim() }.filter { it.isNotEmpty() }
        if (nonBlank.isEmpty()) return ""
        if (nonBlank.size == 1) return nonBlank[0]

        var merged = nonBlank[0]
        for (idx in 1 until nonBlank.size) {
            val next = nonBlank[idx]
            val maxOverlap = min(merged.length, next.length)
            var matchedK = 0

            for (k in maxOverlap downTo 1) {
                if (merged.regionMatches(merged.length - k, next, 0, k, ignoreCase = false)) {
                    matchedK = k
                    break
                }
            }
            if (matchedK == 0 && maxOverlap >= 2) {
                for (k in maxOverlap downTo 2) {
                    if (merged.regionMatches(merged.length - k, next, 0, k, ignoreCase = true)) {
                        matchedK = k
                        break
                    }
                }
            }
            if (matchedK == 0 && maxOverlap >= 4) {
                for (k in maxOverlap downTo 4) {
                    var diffs = 0
                    for (j in 0 until k) {
                        if (!merged[merged.length - k + j].equals(next[j], ignoreCase = true)) {
                            diffs++
                            if (diffs > 1) break
                        }
                    }
                    if (diffs <= 1) {
                        matchedK = k
                        break
                    }
                }
            }

            merged = if (matchedK > 0) {
                merged + next.substring(matchedK)
            } else {
                merged + next
            }
        }
        return merged
    }

    private fun recognizeCropWithSplit(crop: Bitmap): LineResult? {
        if (crop.isRecycled || crop.width <= 0 || crop.height <= 0) return null
        val ratio = crop.width.toFloat() / crop.height.toFloat()
        if (ratio <= SPLIT_CROP_RATIO_THRESHOLD) {
            return runRecognition(crop)
        }

        val chunkWidth = (crop.height * 3.8f).roundToInt().coerceIn(crop.height * 2, crop.width)
        if (chunkWidth >= crop.width) {
            return runRecognition(crop)
        }

        val stride = max(1, (chunkWidth * (1f - SPLIT_CROP_OVERLAP)).roundToInt())
        val ranges = ArrayList<IntRange>()
        var startX = 0
        while (startX + chunkWidth < crop.width) {
            ranges.add(startX until (startX + chunkWidth))
            startX += stride
        }
        val tailStart = (crop.width - chunkWidth).coerceAtLeast(0)
        if (ranges.isEmpty() || ranges.last().first != tailStart) {
            ranges.add(tailStart until crop.width)
        }

        val texts = ArrayList<String>(ranges.size)
        var weightedConfSum = 0.0
        var totalWeight = 0
        var minCharProb = 1.0f

        for (range in ranges) {
            val chunkW = (range.last - range.first + 1).coerceAtLeast(1)
            val chunk = try {
                Bitmap.createBitmap(crop, range.first, 0, chunkW, crop.height)
            } catch (_: Exception) {
                continue
            }
            try {
                val res = runRecognition(chunk)
                if (res != null &&
                    res.text.isNotBlank() &&
                    res.confidence >= MIN_WORD_CONFIDENCE &&
                    res.minCharConfidence >= MIN_CHAR_PROB
                ) {
                    texts.add(res.text)
                    val weight = res.text.length.coerceAtLeast(1)
                    weightedConfSum += res.confidence * weight
                    totalWeight += weight
                    if (res.minCharConfidence < minCharProb) {
                        minCharProb = res.minCharConfidence
                    }
                }
            } finally {
                if (chunk !== crop && !chunk.isRecycled) chunk.recycle()
            }
        }

        val mergedText = mergeMultiStrings(texts)
        if (mergedText.isNotBlank() && totalWeight > 0) {
            return LineResult(mergedText, (weightedConfSum / totalWeight).toFloat(), minCharProb)
        }
        return runRecognition(crop)
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
        var minCharProb = 1.0f
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
                if (prob < minCharProb) minCharProb = prob
                confidenceSum += prob
                charCount++
            }
        }

        val text = builder.toString().trim()
        val effectiveMinCharProb = if (charCount > 0) minCharProb else 0f
        val rawConfidence = if (charCount > 0) (confidenceSum / charCount).toFloat() else 0f
        val confidence = if (effectiveMinCharProb < MIN_CHAR_PROB) 0f else rawConfidence
        return LineResult(text, confidence, effectiveMinCharProb)
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
