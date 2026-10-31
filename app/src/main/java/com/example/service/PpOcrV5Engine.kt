package com.example.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
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

/**
 * PP-OCRv5 mobile recognizer (recognition-only).
 *
 * The detector still comes from RT-DETR. This class only turns a text block
 * into line crops, runs the PP-OCRv5 CTC recognizer, and maps text back to the
 * original page coordinates.
 */
class PpOcrV5Engine(context: Context) {
    companion object {
        private const val TAG = "BabelRead_PpOcr"
        private const val MODEL_ASSET = "models/ppocrv5_rec.onnx"
        private const val DICT_ASSET = "models/ppocrv5_dict.txt"
        private const val MODEL_FILE = "ppocrv5_rec_v1.onnx"
        private const val MODEL_BYTES = 16_534_782L
        private const val REC_HEIGHT = 48
        private const val MIN_REC_WIDTH = 320
        private const val MAX_REC_WIDTH = 3200
        private const val MIN_LINE_CONFIDENCE = 0.65f
        private const val MIN_CJK_LINE_CONFIDENCE = 0.45f
    }

    private data class LineResult(
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
    private val sessionMutex = Mutex()

    @Volatile
    private var ortEnv: OrtEnvironment? = null

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var dictionary: List<String>? = null

    @Volatile
    private var initFailed = false

    fun isReady(): Boolean = (session != null && dictionary != null) || ensureInitialized()

    /**
     * Loads the recognizer ahead of the first page so model extraction and ORT
     * session creation do not block the translation pipeline.
     */
    suspend fun prewarm(): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()
    }

    suspend fun recognizeBlock(
        bitmap: Bitmap,
        region: Rect,
        originLanguage: String
    ): List<TranslationService.RectAndText> = withContext(Dispatchers.Default) {
        val primaryLanguage = normalizeLanguage(originLanguage) ?: return@withContext emptyList()
        if (!ensureInitialized()) return@withContext emptyList()

        suspend fun recognize(
            language: String,
            forceVertical: Boolean = false,
            forceHorizontal: Boolean = false
        ): BlockAttempt {
            val lines = lineSegmenter.segment(
                bitmap,
                region,
                language,
                forceVertical = forceVertical,
                forceHorizontal = forceHorizontal
            )
            val vertical = if (lines.isEmpty()) {
                isJapanese(primaryLanguage) && region.height() > region.width() * 1.8f
            } else {
                lines.count { it.vertical } * 2 >= lines.size
            }
            if (lines.isEmpty()) return BlockAttempt(emptyList(), 0, vertical)

            val results = ArrayList<TranslationService.RectAndText>(lines.size)
            for (line in lines) {
                currentCoroutineContext().ensureActive()
                val recognized = recognizeLine(bitmap, line.rect, line.vertical) ?: continue
                if (!acceptsLine(recognized, language)) {
                    Log.d(TAG, "PP-OCRv5 rejected low-confidence line: ${recognized.confidence} ($language)")
                    continue
                }
                if (recognized.text.isNotBlank()) {
                    results.add(TranslationService.RectAndText(line.rect, recognized.text))
                }
            }
            return BlockAttempt(results, lines.size, vertical)
        }

        val primary = recognize(primaryLanguage)
        if (primary.lineCount > 0 && primary.results.size >= primary.lineCount) {
            return@withContext primary.results
        }

        // Some lines may be rejected in the detected direction. Retry the same
        // language in the opposite direction and keep the successful lines.
        val retryVertical = !primary.vertical
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

    private fun acceptsLine(line: LineResult, language: String): Boolean {
        if (line.text.isBlank()) return false
        if (line.confidence >= MIN_LINE_CONFIDENCE) return true
        if (!isJapanese(language)) return false
        val hasCjk = line.text.any { ch ->
            ch.code in 0x3040..0x9FAF || ch.code in 0xAC00..0xD7AF
        }
        return hasCjk && line.confidence >= MIN_CJK_LINE_CONFIDENCE
    }

    private fun isJapanese(language: String): Boolean =
        language.equals("Japonca", ignoreCase = true) ||
            language.equals("Japanese", ignoreCase = true)

    private fun normalizeLanguage(originLanguage: String): String? = when {
        originLanguage.equals("İngilizce", ignoreCase = true) ||
            originLanguage.equals("English", ignoreCase = true) -> "İngilizce"
        originLanguage.equals("Japonca", ignoreCase = true) ||
            originLanguage.equals("Japanese", ignoreCase = true) -> "Japonca"
        originLanguage.equals("Yerel", ignoreCase = true) ||
            originLanguage.equals("Bilinmiyor", ignoreCase = true) ||
            originLanguage.equals("Otomatik", ignoreCase = true) ||
            originLanguage.equals("Auto", ignoreCase = true) -> "Japonca"
        else -> null
    }

    private suspend fun recognizeLine(
        bitmap: Bitmap,
        rect: Rect,
        vertical: Boolean
    ): LineResult? = sessionMutex.withLock {
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

        try {
            runRecognition(oriented)
        } catch (t: Throwable) {
            Log.w(TAG, "PP-OCRv5 line recognition failed: ${t.message}")
            null
        } finally {
            if (oriented !== crop && !crop.isRecycled) crop.recycle()
            if (!oriented.isRecycled) oriented.recycle()
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

            val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
            val shape = longArrayOf(1, 3, REC_HEIGHT.toLong(), targetWidth.toLong())
            OnnxTensor.createTensor(env, buffer, shape).use { input ->
                session.run(mapOf(session.inputNames.first() to input)).use { result ->
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
        } finally {
            if (!scaled.isRecycled) scaled.recycle()
        }
    }

    private fun decodeCtc(
        values: FloatBuffer,
        timesteps: Int,
        classCount: Int,
        dictionary: List<String>
    ): LineResult {
        val builder = StringBuilder()
        var lastIndex = -1
        var confidenceSum = 0.0
        var confidenceCount = 0
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
                if (token != null) {
                    builder.append(token)
                    confidenceSum += bestValue
                    confidenceCount++
                }
            }
            lastIndex = bestIndex
        }
        val text = builder.toString().trim()
        val confidence = if (confidenceCount > 0) {
            (confidenceSum / confidenceCount).toFloat()
        } else {
            0f
        }
        return LineResult(text, confidence)
    }

    private fun ensureInitialized(): Boolean {
        if (session != null && dictionary != null) return true
        if (initFailed) return false
        synchronized(this) {
            if (session != null && dictionary != null) return true
            if (initFailed) return false
            try {
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

    private fun readDictionary(): List<String> {
        return appContext.assets.open(DICT_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines().map { it.trimEnd('\r', '\n') }
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
            Log.e(TAG, "PP-OCRv5 model copy failed: ${t.message}", t)
            null
        }
    }
}
