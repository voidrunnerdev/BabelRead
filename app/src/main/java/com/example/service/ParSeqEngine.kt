package com.example.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import kotlin.math.exp

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
        if (!ensureInitialized()) return@withContext emptyList()
        val detected = detector.detect(bitmap, region)
        val lines = if (detected.isNotEmpty()) {
            detected.map {
                SegmentedTextLine(
                    Rect(it.rect),
                    vertical = it.rect.height() > it.rect.width() * 1.6f
                )
            }
        } else {
            lineSegmenter.segment(
                bitmap,
                region,
                originLanguage,
                forceHorizontal = true
            )
        }
        if (lines.isEmpty()) return@withContext emptyList()

        val results = ArrayList<TranslationService.RectAndText>(lines.size)
        for (line in lines) {
            currentCoroutineContext().ensureActive()
            val recognized = recognizeLine(bitmap, line.rect, line.vertical) ?: continue
            if (recognized.confidence < MIN_LINE_CONFIDENCE || recognized.text.isBlank()) {
                Log.d(TAG, "PARSeq rejected low-confidence line: '${recognized.text}' (${recognized.confidence})")
                continue
            }
            results.add(TranslationService.RectAndText(line.rect, recognized.text))
        }
        results
    }

    private suspend fun recognizeLine(
        bitmap: Bitmap,
        rect: Rect,
        vertical: Boolean
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
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "PARSeq line recognition failed: ${t.message}")
            null
        } finally {
            if (oriented !== crop && crop !== bitmap && !crop.isRecycled) crop.recycle()
            if (oriented !== bitmap && !oriented.isRecycled) oriented.recycle()
        }
    }

    private fun runRecognition(bitmap: Bitmap): LineResult? {
        val activeSession = session ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) return null

        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetWidth = ceil(INPUT_HEIGHT * ratio).toInt().coerceIn(1, INPUT_WIDTH)

        val scaled = Bitmap.createBitmap(INPUT_WIDTH, INPUT_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        canvas.drawColor(Color.WHITE)
        val lineScaled = try {
            Bitmap.createScaledBitmap(bitmap, targetWidth, INPUT_HEIGHT, true)
        } catch (_: Exception) {
            scaled.recycle()
            return null
        }
        canvas.drawBitmap(lineScaled, 0f, 0f, null)
        if (lineScaled !== bitmap && !lineScaled.isRecycled) lineScaled.recycle()

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
