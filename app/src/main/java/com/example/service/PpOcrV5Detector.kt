package com.example.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PpOcrV5Detector(context: Context) {
    companion object {
        private const val TAG = "BabelRead_PpOcrDet"
        private const val MODEL_ASSET = "models/ppocrv5_det.onnx"
        private const val MODEL_FILE = "ppocrv5_det_v1.onnx"
        private const val MODEL_BYTES = 4_826_518L
        private const val MIN_DETECT_SIDE = 480
        private const val LIMIT_SIDE = 960
        private const val DET_THRESHOLD = 0.3f
        private const val BOX_THRESHOLD = 0.6f
        private const val UNCLIP_RATIO = 1.5f
        private const val MIN_BOX_SIZE = 3f
        private const val MAX_CANDIDATES = 1000
        private const val NMS_THRESHOLD = 0.5f
        private const val MAX_REGION_PIXELS = 4_000_000L
        private const val PAGE_SCAN_TILE_MAX_WIDTH = 1280
        private const val PAGE_SCAN_TILE_MAX_HEIGHT = 1152
        private const val PAGE_SCAN_DARK_LUMA = 140
        private const val PAGE_SCAN_NON_WHITE_LUMA = 185
        private const val PAGE_SCAN_BRIGHT_LUMA = 195
        private const val PAGE_SCAN_INVERT_MIN_DARK_RATIO = 0.22f
        private const val PAGE_SCAN_INVERT_MIN_COLORED_RATIO = 0.40f
        private const val PAGE_SCAN_INVERT_MIN_BRIGHT_SAMPLES = 8
    }

    data class DetectedLine(
        val rect: Rect,
        val confidence: Float
    )

    private data class DetectionMap(
        val boxes: List<PpOcrV5DetPostProcess.Box>,
        val width: Int,
        val height: Int
    )

    private val appContext = context.applicationContext
    private val sessionMutex = Mutex()
    private val sessionLock = Any()

    @Volatile
    private var ortEnv: OrtEnvironment? = null

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var initFailed = false

    @Volatile
    private var isClosed = false

    fun isReady(): Boolean = session != null || ensureInitialized()

    suspend fun prewarm(): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()
    }

    suspend fun detect(bitmap: Bitmap, region: Rect): List<DetectedLine> =
        withContext(Dispatchers.Default) {
            if (bitmap.isRecycled || region.isEmpty) return@withContext emptyList()
            if (!ensureInitialized()) return@withContext emptyList()

            val pageRect = Rect(region)
            if (!pageRect.intersect(0, 0, bitmap.width, bitmap.height)) {
                return@withContext emptyList()
            }
            if (pageRect.width() < 8 || pageRect.height() < 8) return@withContext emptyList()

            val pad = (min(pageRect.width(), pageRect.height()) / 16).coerceIn(2, 8)
            val cropRect = Rect(
                (pageRect.left - pad).coerceAtLeast(0),
                (pageRect.top - pad).coerceAtLeast(0),
                (pageRect.right + pad).coerceAtMost(bitmap.width),
                (pageRect.bottom + pad).coerceAtMost(bitmap.height)
            )
            if (cropRect.width() < 8 || cropRect.height() < 8) {
                return@withContext emptyList()
            }
            if (cropRect.width().toLong() * cropRect.height() > MAX_REGION_PIXELS) {
                return@withContext emptyList()
            }

            try {
                val crop = Bitmap.createBitmap(
                    bitmap,
                    cropRect.left,
                    cropRect.top,
                    cropRect.width(),
                    cropRect.height()
                )
                val pixels = try {
                    IntArray(crop.width * crop.height).also {
                        crop.getPixels(it, 0, crop.width, 0, 0, crop.width, crop.height)
                    }
                } finally {
                    if (crop !== bitmap && !crop.isRecycled) crop.recycle()
                }
                detectFromCropPixels(pixels, cropRect, bitmap.width, bitmap.height)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "PP-OCRv5 detector inference failed: ${t.message}")
                emptyList()
            }
        }

    /**
     * Scans the full page using overlapping tiles (to stay within [MAX_REGION_PIXELS]
     * and preserve text resolution on tall webtoon strips) for lines missed by bubble
     * detection, including an inverted-contrast pass on dark/colored strips.
     */
    suspend fun detectMissingPageLines(
        bitmap: Bitmap,
        excludeRegions: List<Rect>,
        includeInvertedPass: Boolean = true
    ): List<DetectedLine> = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled || bitmap.width < 8 || bitmap.height < 8) {
            return@withContext emptyList()
        }
        if (!ensureInitialized()) return@withContext emptyList()

        val pageW = bitmap.width
        val pageH = bitmap.height
        val tileW = if (pageW > 1440) {
            min(PAGE_SCAN_TILE_MAX_WIDTH, (pageW + 1) / 2 + 120)
        } else {
            pageW
        }
        val maxHeightByPixels = (MAX_REGION_PIXELS / max(1, tileW).toLong()).toInt().coerceAtLeast(LIMIT_SIDE)
        val tileH = min(
            pageH,
            min(
                maxHeightByPixels,
                max(LIMIT_SIDE, min(PAGE_SCAN_TILE_MAX_HEIGHT, (tileW * 1.25f).roundToInt()))
            )
        )
        val overlapX = if (tileW < pageW) (tileW * 0.15f).roundToInt().coerceIn(48, 160) else 0
        val overlapY = if (tileH < pageH) (tileH * 0.20f).roundToInt().coerceIn(80, 220) else 0
        val stepX = max(1, tileW - overlapX)
        val stepY = max(1, tileH - overlapY)

        val xStarts = computeTileStarts(pageW, tileW, stepX)
        val yStarts = computeTileStarts(pageH, tileH, stepY)
        val collected = ArrayList<DetectedLine>()

        for (yStart in yStarts) {
            for (xStart in xStarts) {
                currentCoroutineContext().ensureActive()
                val tileRect = Rect(
                    xStart,
                    yStart,
                    (xStart + tileW).coerceAtMost(pageW),
                    (yStart + tileH).coerceAtMost(pageH)
                )
                if (tileRect.width() < 8 || tileRect.height() < 8) continue
                if (tileRect.width().toLong() * tileRect.height() > MAX_REGION_PIXELS) continue
                if (excludeRegions.any { !it.isEmpty && it.contains(tileRect) }) continue

                val pixels = try {
                    IntArray(tileRect.width() * tileRect.height()).also {
                        bitmap.getPixels(
                            it,
                            0,
                            tileRect.width(),
                            tileRect.left,
                            tileRect.top,
                            tileRect.width(),
                            tileRect.height()
                        )
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.w(TAG, "PP-OCRv5 page tile pixel read failed: ${t.message}")
                    continue
                }

                try {
                    val normalDetections = detectFromCropPixels(pixels, tileRect, pageW, pageH)
                    for (line in normalDetections) {
                        if (!isExcludedLine(line.rect, excludeRegions)) {
                            collected.add(line)
                        }
                    }

                    if (includeInvertedPass && shouldRunInvertedPass(pixels, tileRect.width(), tileRect.height())) {
                        val invertedPixels = buildInvertedContrastPixels(pixels)
                        val invertedDetections = detectFromCropPixels(invertedPixels, tileRect, pageW, pageH)
                        for (line in invertedDetections) {
                            if (!isExcludedLine(line.rect, excludeRegions)) {
                                collected.add(line)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.w(TAG, "PP-OCRv5 page tile detection failed: ${t.message}")
                }
            }
        }

        dedupeDetectedLines(collected)
    }

    private suspend fun detectFromCropPixels(
        pixels: IntArray,
        cropRect: Rect,
        pageWidth: Int,
        pageHeight: Int
    ): List<DetectedLine> {
        val target = PpOcrV5DetPreprocess.scaledSize(
            cropRect.width(),
            cropRect.height(),
            LIMIT_SIDE,
            MIN_DETECT_SIDE
        )
        val buffer = ByteBuffer.allocateDirect(
            target[0] * target[1] * 3 * Float.SIZE_BYTES
        ).order(ByteOrder.nativeOrder()).asFloatBuffer()
        PpOcrV5DetPreprocess.writeTensor(
            pixels,
            cropRect.width(),
            cropRect.height(),
            target[0],
            target[1],
            buffer
        )
        buffer.position(0)
        val detection = runDetection(buffer, target[0], target[1])
        if (detection.boxes.isEmpty()) return emptyList()

        val scaleX = cropRect.width().toFloat() / detection.width
        val scaleY = cropRect.height().toFloat() / detection.height
        return detection.boxes.mapNotNull { box ->
            val left = (cropRect.left + box.left * scaleX).roundToInt()
                .coerceIn(0, pageWidth - 1)
            val top = (cropRect.top + box.top * scaleY).roundToInt()
                .coerceIn(0, pageHeight - 1)
            val right = (cropRect.left + (box.right + 1f) * scaleX).roundToInt()
                .coerceIn(left + 1, pageWidth)
            val bottom = (cropRect.top + (box.bottom + 1f) * scaleY).roundToInt()
                .coerceIn(top + 1, pageHeight)
            if (right - left < 4 || bottom - top < 4) return@mapNotNull null
            DetectedLine(Rect(left, top, right, bottom), box.score)
        }
    }

    internal fun computeTileStarts(totalSize: Int, tileSize: Int, stepSize: Int): List<Int> {
        if (totalSize <= tileSize || tileSize <= 0) return listOf(0)
        val safeStep = max(1, stepSize)
        val starts = ArrayList<Int>()
        var pos = 0
        while (pos + tileSize < totalSize) {
            starts.add(pos)
            pos += safeStep
        }
        val lastStart = (totalSize - tileSize).coerceAtLeast(0)
        if (starts.isEmpty() || starts.last() != lastStart) {
            starts.add(lastStart)
        }
        return starts
    }

    internal fun shouldRunInvertedPass(pixels: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return false
        val stride = max(2, min(8, min(width, height) / 48))
        var total = 0
        var darkCount = 0
        var nonWhiteCount = 0
        var brightCount = 0
        var y = 0
        while (y < height) {
            val rowOffset = y * width
            var x = 0
            while (x < width) {
                val color = pixels[rowOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val luma = (r * 299 + g * 587 + b * 114) / 1000
                if (luma < PAGE_SCAN_DARK_LUMA) darkCount++
                if (luma < PAGE_SCAN_NON_WHITE_LUMA) nonWhiteCount++
                if (luma >= PAGE_SCAN_BRIGHT_LUMA) brightCount++
                total++
                x += stride
            }
            y += stride
        }
        if (total <= 0 || brightCount < PAGE_SCAN_INVERT_MIN_BRIGHT_SAMPLES) return false
        val darkRatio = darkCount.toFloat() / total
        val coloredRatio = nonWhiteCount.toFloat() / total
        return darkRatio >= PAGE_SCAN_INVERT_MIN_DARK_RATIO ||
            coloredRatio >= PAGE_SCAN_INVERT_MIN_COLORED_RATIO
    }

    internal fun buildInvertedContrastPixels(pixels: IntArray): IntArray {
        val inverted = IntArray(pixels.size)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000f
            val out = ((185f - luma) * 2.0f + 145f).roundToInt().coerceIn(0, 255)
            inverted[i] = (0xFF shl 24) or (out shl 16) or (out shl 8) or out
        }
        return inverted
    }

    internal fun isExcludedLine(rect: Rect, excludeRegions: List<Rect>): Boolean {
        if (excludeRegions.isEmpty()) return false
        val lineArea = rect.width().toLong() * rect.height()
        if (lineArea <= 0L) return true
        for (exclude in excludeRegions) {
            if (exclude.isEmpty) continue
            val interLeft = max(rect.left, exclude.left)
            val interTop = max(rect.top, exclude.top)
            val interRight = min(rect.right, exclude.right)
            val interBottom = min(rect.bottom, exclude.bottom)
            if (interRight <= interLeft || interBottom <= interTop) continue
            val interArea = (interRight - interLeft).toLong() * (interBottom - interTop)
            val excludeArea = exclude.width().toLong() * exclude.height()
            val unionArea = lineArea + excludeArea - interArea
            val iou = if (unionArea > 0L) interArea.toFloat() / unionArea else 0f
            val coverage = interArea.toFloat() / lineArea
            if (iou > 0.30f || coverage > 0.45f) return true
        }
        return false
    }

    internal fun dedupeDetectedLines(lines: List<DetectedLine>): List<DetectedLine> {
        if (lines.size <= 1) return lines
        val sorted = lines.sortedByDescending { it.confidence }
        val kept = ArrayList<DetectedLine>(sorted.size)
        for (candidate in sorted) {
            val candRect = candidate.rect
            val candArea = candRect.width().toLong() * candRect.height()
            if (candArea <= 0L) continue
            var mergedOrSuppressed = false
            for (i in kept.indices) {
                val existing = kept[i]
                val existRect = existing.rect
                val interLeft = max(candRect.left, existRect.left)
                val interTop = max(candRect.top, existRect.top)
                val interRight = min(candRect.right, existRect.right)
                val interBottom = min(candRect.bottom, existRect.bottom)
                if (interRight <= interLeft || interBottom <= interTop) continue
                val interH = interBottom - interTop
                val interArea = (interRight - interLeft).toLong() * interH
                val existArea = existRect.width().toLong() * existRect.height()
                val unionArea = candArea + existArea - interArea
                val iou = if (unionArea > 0L) interArea.toFloat() / unionArea else 0f
                val minArea = min(candArea, existArea).coerceAtLeast(1L)
                val overlapMin = interArea.toFloat() / minArea
                if (iou > 0.30f || overlapMin > 0.55f) {
                    val minH = min(candRect.height(), existRect.height()).coerceAtLeast(1)
                    val maxH = max(candRect.height(), existRect.height())
                    if (interH.toFloat() / minH >= 0.60f && maxH <= minH * 1.40f) {
                        val mergedRect = Rect(
                            min(existRect.left, candRect.left),
                            min(existRect.top, candRect.top),
                            max(existRect.right, candRect.right),
                            max(existRect.bottom, candRect.bottom)
                        )
                        kept[i] = DetectedLine(mergedRect, max(existing.confidence, candidate.confidence))
                    }
                    mergedOrSuppressed = true
                    break
                }
            }
            if (!mergedOrSuppressed) {
                kept.add(DetectedLine(Rect(candRect), candidate.confidence))
            }
        }
        return sortDetectedLinesInReadingOrder(kept)
    }

    private fun sortDetectedLinesInReadingOrder(
        lines: List<DetectedLine>
    ): List<DetectedLine> {
        if (lines.size <= 1) return lines
        val byY = lines.sortedWith(compareBy<DetectedLine> { it.rect.centerY() }.thenBy { it.rect.left })
        val rows = ArrayList<MutableList<DetectedLine>>()
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
                val minH = min(avgH, h).coerceAtLeast(1f)
                kotlin.math.abs(cy - avgCy) <= minH * 0.50f
            }
            if (targetRow != null) targetRow.add(line) else rows.add(mutableListOf(line))
        }
        return rows.flatMap { row -> row.sortedBy { it.rect.left } }
    }

    private suspend fun runDetection(
        tensor: FloatBuffer,
        width: Int,
        height: Int
    ): DetectionMap = sessionMutex.withLock {
        synchronized(sessionLock) {
            if (isClosed) return@synchronized DetectionMap(emptyList(), width, height)
            val activeSession = session ?: return@synchronized DetectionMap(emptyList(), width, height)
            val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }

            OnnxTensor.createTensor(
                env,
                tensor,
                longArrayOf(1, 3, height.toLong(), width.toLong())
            ).use { input ->
                activeSession.run(mapOf(activeSession.inputNames.first() to input)).use { result ->
                    val output = result.get(0) as? OnnxTensor
                        ?: return@synchronized DetectionMap(emptyList(), width, height)
                    val shape = output.info.shape
                    if (shape.size != 4 || shape[1] != 1L) {
                        return@synchronized DetectionMap(emptyList(), width, height)
                    }
                    val mapHeight = shape[2].toInt()
                    val mapWidth = shape[3].toInt()
                    if (mapHeight <= 0 || mapWidth <= 0) {
                        return@synchronized DetectionMap(emptyList(), width, height)
                    }

                    val raw = output.floatBuffer
                    raw.rewind()
                    val values = FloatArray(mapWidth * mapHeight)
                    val available = min(raw.remaining(), values.size)
                    raw.get(values, 0, available)
                    DetectionMap(
                        PpOcrV5DetPostProcess.detectBoxes(
                            values,
                            mapWidth,
                            mapHeight,
                            threshold = DET_THRESHOLD,
                            boxThreshold = BOX_THRESHOLD,
                            unclipRatio = UNCLIP_RATIO,
                            minSize = MIN_BOX_SIZE,
                            maxCandidates = MAX_CANDIDATES,
                            nmsThreshold = NMS_THRESHOLD
                        ),
                        mapWidth,
                        mapHeight
                    )
                }
            }
        }
    }

    fun close() {
        synchronized(sessionLock) {
            isClosed = true
            val old = session
            session = null
            runCatching { old?.close() }
        }
    }

    private fun ensureInitialized(): Boolean {
        if (isClosed) return false
        if (session != null) return true
        if (initFailed) return false
        synchronized(sessionLock) {
            if (isClosed) return false
            if (session != null) return true
            if (initFailed) return false
            try {
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
                session = loaded
                Log.d(TAG, "PP-OCRv5 detector loaded (${modelFile.length()} bytes)")
                return true
            } catch (t: Throwable) {
                initFailed = true
                Log.w(TAG, "PP-OCRv5 detector could not be loaded: ${t.message}")
                return false
            }
        }
    }

    private fun ensureModelFile(): File? {
        val target = File(appContext.filesDir, MODEL_FILE)
        if (target.exists() && target.length() == MODEL_BYTES) return target

        val temporary = File(appContext.filesDir, "$MODEL_FILE.tmp")
        return try {
            appContext.assets.open(MODEL_ASSET).use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            if (temporary.length() != MODEL_BYTES) {
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
            if (target.length() == MODEL_BYTES) target else null
        } catch (t: Throwable) {
            temporary.delete()
            Log.w(TAG, "PP-OCRv5 detector asset unavailable: ${t.message}")
            null
        }
    }
}

internal object PpOcrV5DetPreprocess {
    private const val CHANNELS = 3
    private const val STRIDE = 32
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun scaledSize(width: Int, height: Int, limitSide: Int, minSide: Int = 0): IntArray {
        if (width <= 0 || height <= 0 || limitSide <= 0) {
            return intArrayOf(STRIDE, STRIDE)
        }
        val maxSide = max(width, height)
        val ratio = when {
            maxSide > limitSide -> limitSide.toFloat() / maxSide
            minSide > 0 && maxSide < minSide -> min(limitSide.toFloat() / maxSide, minSide.toFloat() / maxSide)
            else -> 1f
        }
        val resizedWidth = (((width * ratio) / STRIDE).roundToInt() * STRIDE).coerceAtMost(limitSide)
        val resizedHeight = (((height * ratio) / STRIDE).roundToInt() * STRIDE).coerceAtMost(limitSide)
        return intArrayOf(
            max(STRIDE, resizedWidth),
            max(STRIDE, resizedHeight)
        )
    }

    fun toTensor(
        pixels: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): FloatArray {
        val tensor = FloatArray(targetWidth * targetHeight * CHANNELS)
        writeTensor(
            pixels,
            sourceWidth,
            sourceHeight,
            targetWidth,
            targetHeight,
            FloatBuffer.wrap(tensor)
        )
        return tensor
    }

    fun writeTensor(
        pixels: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        output: FloatBuffer
    ) {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(targetWidth > 0 && targetHeight > 0)
        require(pixels.size >= sourceWidth * sourceHeight)

        val planeSize = targetWidth * targetHeight
        require(output.capacity() >= planeSize * CHANNELS)
        val scaleX = sourceWidth.toFloat() / targetWidth
        val scaleY = sourceHeight.toFloat() / targetHeight
        val maxSourceX = (sourceWidth - 1).toFloat()
        val maxSourceY = (sourceHeight - 1).toFloat()

        for (y in 0 until targetHeight) {
            val sourceY = ((y + 0.5f) * scaleY - 0.5f).coerceIn(0f, maxSourceY)
            val y0 = sourceY.toInt()
            val y1 = min(y0 + 1, sourceHeight - 1)
            val weightY = sourceY - y0
            for (x in 0 until targetWidth) {
                val sourceX = ((x + 0.5f) * scaleX - 0.5f).coerceIn(0f, maxSourceX)
                val x0 = sourceX.toInt()
                val x1 = min(x0 + 1, sourceWidth - 1)
                val weightX = sourceX - x0
                val topLeft = pixels[y0 * sourceWidth + x0]
                val topRight = pixels[y0 * sourceWidth + x1]
                val bottomLeft = pixels[y1 * sourceWidth + x0]
                val bottomRight = pixels[y1 * sourceWidth + x1]

                val blue = interpolate(
                    topLeft and 0xFF,
                    topRight and 0xFF,
                    bottomLeft and 0xFF,
                    bottomRight and 0xFF,
                    weightX,
                    weightY
                )
                val green = interpolate(
                    (topLeft shr 8) and 0xFF,
                    (topRight shr 8) and 0xFF,
                    (bottomLeft shr 8) and 0xFF,
                    (bottomRight shr 8) and 0xFF,
                    weightX,
                    weightY
                )
                val red = interpolate(
                    (topLeft shr 16) and 0xFF,
                    (topRight shr 16) and 0xFF,
                    (bottomLeft shr 16) and 0xFF,
                    (bottomRight shr 16) and 0xFF,
                    weightX,
                    weightY
                )

                val index = y * targetWidth + x
                output.put(index, ((blue / 255f) - MEAN[0]) / STD[0])
                output.put(planeSize + index, ((green / 255f) - MEAN[1]) / STD[1])
                output.put(planeSize * 2 + index, ((red / 255f) - MEAN[2]) / STD[2])
            }
        }
    }

    private fun interpolate(
        topLeft: Int,
        topRight: Int,
        bottomLeft: Int,
        bottomRight: Int,
        weightX: Float,
        weightY: Float
    ): Float {
        val top = topLeft + (topRight - topLeft) * weightX
        val bottom = bottomLeft + (bottomRight - bottomLeft) * weightX
        return top + (bottom - top) * weightY
    }
}

internal object PpOcrV5DetPostProcess {
    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val score: Float
    )

    fun sigmoid(value: Float): Float = 1f / (1f + exp(-value))

    fun ensureProbabilities(values: FloatArray) {
        var minimum = Float.MAX_VALUE
        var maximum = -Float.MAX_VALUE
        for (value in values) {
            if (value < minimum) minimum = value
            if (value > maximum) maximum = value
        }
        if (minimum >= -0.01f && maximum <= 1.01f) return
        for (index in values.indices) values[index] = sigmoid(values[index])
    }

    fun detectBoxes(
        values: FloatArray,
        width: Int,
        height: Int,
        threshold: Float = 0.3f,
        boxThreshold: Float = 0.6f,
        unclipRatio: Float = 1.5f,
        minSize: Float = 3f,
        maxCandidates: Int = 1000,
        nmsThreshold: Float = 0.5f
    ): List<Box> {
        if (width <= 0 || height <= 0) return emptyList()
        if (values.size < width * height) return emptyList()

        ensureProbabilities(values)
        val mask = BooleanArray(width * height) { values[it] > threshold }
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val boxes = ArrayList<Box>()

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            if (boxes.size >= maxCandidates) break

            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1

            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                val yStart = max(0, y - 1)
                val yEnd = min(height - 1, y + 1)
                val xStart = max(0, x - 1)
                val xEnd = min(width - 1, x + 1)
                for (ny in yStart..yEnd) {
                    val row = ny * width
                    for (nx in xStart..xEnd) {
                        val next = row + nx
                        if (mask[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1
            if (min(boxWidth.toFloat(), boxHeight.toFloat()) < minSize) continue

            var scoreSum = 0.0
            for (y in minY..maxY) {
                val row = y * width
                for (x in minX..maxX) scoreSum += values[row + x]
            }
            val score = (scoreSum / (boxWidth * boxHeight)).toFloat()
            if (score < boxThreshold) continue

            val perimeter = 2f * (boxWidth + boxHeight)
            val distance = (boxWidth * boxHeight * unclipRatio) / perimeter
            val box = Box(
                (minX - distance).coerceAtLeast(0f),
                (minY - distance).coerceAtLeast(0f),
                (maxX + distance).coerceAtMost((width - 1).toFloat()),
                (maxY + distance).coerceAtMost((height - 1).toFloat()),
                score
            )
            val unclippedWidth = box.right - box.left + 1f
            val unclippedHeight = box.bottom - box.top + 1f
            if (min(unclippedWidth, unclippedHeight) < minSize + 2f) continue
            boxes.add(box)
        }

        return nonMaximumSuppression(boxes, nmsThreshold)
    }

    private fun nonMaximumSuppression(boxes: List<Box>, threshold: Float): List<Box> {
        if (boxes.size <= 1) return boxes
        val sorted = boxes.sortedByDescending { it.score }
        val kept = ArrayList<Box>(sorted.size)
        for (box in sorted) {
            if (kept.none { intersectionOverUnion(box, it) > threshold }) {
                kept.add(box)
            }
        }
        return kept
    }

    private fun intersectionOverUnion(first: Box, second: Box): Float {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val firstArea = (first.right - first.left) * (first.bottom - first.top)
        val secondArea = (second.right - second.left) * (second.bottom - second.top)
        val union = firstArea + secondArea - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
