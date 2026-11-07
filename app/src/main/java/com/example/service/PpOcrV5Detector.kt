package com.example.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
        private const val LIMIT_SIDE = 960
        private const val DET_THRESHOLD = 0.3f
        private const val BOX_THRESHOLD = 0.6f
        private const val UNCLIP_RATIO = 1.5f
        private const val MIN_BOX_SIZE = 3f
        private const val MAX_CANDIDATES = 1000
        private const val NMS_THRESHOLD = 0.5f
        private const val MAX_REGION_PIXELS = 4_000_000L
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

    @Volatile
    private var ortEnv: OrtEnvironment? = null

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var initFailed = false

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

                val target = PpOcrV5DetPreprocess.scaledSize(
                    cropRect.width(),
                    cropRect.height(),
                    LIMIT_SIDE
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
                if (detection.boxes.isEmpty()) return@withContext emptyList()

                val scaleX = cropRect.width().toFloat() / detection.width
                val scaleY = cropRect.height().toFloat() / detection.height
                detection.boxes.mapNotNull { box ->
                    val left = (cropRect.left + box.left * scaleX).roundToInt()
                        .coerceIn(0, bitmap.width - 1)
                    val top = (cropRect.top + box.top * scaleY).roundToInt()
                        .coerceIn(0, bitmap.height - 1)
                    val right = (cropRect.left + (box.right + 1f) * scaleX).roundToInt()
                        .coerceIn(left + 1, bitmap.width)
                    val bottom = (cropRect.top + (box.bottom + 1f) * scaleY).roundToInt()
                        .coerceIn(top + 1, bitmap.height)
                    if (right - left < 4 || bottom - top < 4) return@mapNotNull null
                    DetectedLine(Rect(left, top, right, bottom), box.score)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "PP-OCRv5 detector inference failed: ${t.message}")
                emptyList()
            }
        }

    private suspend fun runDetection(
        tensor: FloatBuffer,
        width: Int,
        height: Int
    ): DetectionMap = sessionMutex.withLock {
        val session = session ?: return@withLock DetectionMap(emptyList(), width, height)
        val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }

        OnnxTensor.createTensor(
            env,
            tensor,
            longArrayOf(1, 3, height.toLong(), width.toLong())
        ).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { result ->
                val output = result.get(0) as? OnnxTensor
                    ?: return@withLock DetectionMap(emptyList(), width, height)
                val shape = output.info.shape
                if (shape.size != 4 || shape[1] != 1L) {
                    return@withLock DetectionMap(emptyList(), width, height)
                }
                val mapHeight = shape[2].toInt()
                val mapWidth = shape[3].toInt()
                if (mapHeight <= 0 || mapWidth <= 0) {
                    return@withLock DetectionMap(emptyList(), width, height)
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

    private fun ensureInitialized(): Boolean {
        if (session != null) return true
        if (initFailed) return false
        synchronized(this) {
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

    fun scaledSize(width: Int, height: Int, limitSide: Int): IntArray {
        if (width <= 0 || height <= 0 || limitSide <= 0) {
            return intArrayOf(STRIDE, STRIDE)
        }
        val ratio = limitSide.toFloat() / max(width, height)
        val resizedWidth = ((width * ratio) / STRIDE).roundToInt() * STRIDE
        val resizedHeight = ((height * ratio) / STRIDE).roundToInt() * STRIDE
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
