package com.example.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * RT-DETR-v2 & YOLO Çizgi Roman / Manga Konuşma Balonu ve Metin Bölgesi Dedektörü.
 * Upstream model: ogkalu/comic-text-and-bubble-detector/detector-v4-s_int8.onnx.
 * The bundled asset is renamed to models/detector.onnx; koharu-yolo26s.onnx is
 * retained only as a legacy asset lookup fallback.
 * Çok sınıflı destek:
 *   0: bubble (konuşma balonu)
 *   1: text_bubble (balon içi metin)
 *   2: text_free (balon dışı metin)
 */
class YoloBubbleDetector(context: Context) {
    // The detector can outlive the screen that created it; never retain an Activity context.
    private val context: Context = context.applicationContext

    data class YoloDetection(
        val classId: Int,
        val label: String,
        val confidence: Float,
        val box: RectF // Normalize edilmiş koordinatlar (0.0f .. 1.0f)
    )

    data class DetectionResult(
        val detections: List<BubbleBox>,
        val error: String?
    )

    private data class TileDetection(
        val detection: YoloDetection,
        val tileRow: Int,
        val tileColumn: Int,
        val touchesTop: Boolean,
        val touchesBottom: Boolean,
        val touchesLeft: Boolean,
        val touchesRight: Boolean
    )

    companion object {
        private const val TAG = "BabelRead_Detector"
        const val MODEL_FILENAME = "detector.onnx"
        const val EMBEDDED_ASSET_PATH = "models/detector.onnx"
        const val LEGACY_MODEL_FILENAME = "koharu-yolo26s.onnx"
        const val DEFAULT_INPUT_SIZE = 640
        const val DEFAULT_CONF_THRESHOLD = 0.20f
        private const val MIN_MODEL_FILE_BYTES = 10_000L
        private const val VERIFIED_BUNDLED_MODEL_SHA256 = "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79"

        val DEFAULT_CLASS_NAMES = listOf(
            "bubble",
            "text_bubble",
            "text_free"
        )
    }

    private val sessionLock = Any()
    private val modelFileLock = Any()
    private val hashCacheLock = Any()
    private val inferenceMutex = Mutex()
    private val detectionMutex = Mutex()

    @Volatile
    private var ortEnv: OrtEnvironment? = null

    @Volatile
    private var ortSession: OrtSession? = null

    @Volatile
    private var isSessionInitialized = false

    @Volatile
    var lastInitializationError: String? = null

    @Volatile
    var lastInferenceError: String? = null
        private set

    @Volatile
    var modelInputWidth = DEFAULT_INPUT_SIZE
        private set

    @Volatile
    var modelInputHeight = DEFAULT_INPUT_SIZE
        private set

    @Volatile
    var dynamicClassNames: Map<Int, String> = emptyMap()
        internal set

    @Volatile
    private var verifiedBundledClassMap = false

    @Volatile
    var hasOrigTargetSizesInput = false
        private set

    @Volatile
    var isRtDetrMultiOutput = false
        private set

    private var cachedFloatArray: FloatArray? = null
    private var cachedPixelArray: IntArray? = null
    @Volatile private var cachedModelHashKey: String? = null
    @Volatile private var cachedModelHash: String? = null
    @Volatile private var warnedMissingRtDetrLabels = false
    @Volatile private var warnedUnsupportedInputType = false

    init {
        // Start only after every field used by initialization has been initialized.
        Thread { initOnnxEngine() }.start()
    }

    private fun getOrCreateFloatArray(inputW: Int, inputH: Int): FloatArray {
        val numFloats = 3 * inputW * inputH
        var arr = cachedFloatArray
        if (arr == null || arr.size < numFloats) {
            arr = FloatArray(numFloats)
            cachedFloatArray = arr
        }
        return arr
    }

    private fun getOrCreatePixelArray(size: Int): IntArray {
        var arr = cachedPixelArray
        if (arr == null || arr.size < size) {
            arr = IntArray(size)
            cachedPixelArray = arr
        }
        return arr
    }

    private fun findOutputName(names: Collection<String>, aliases: Set<String>): String? =
        names.firstOrNull { name ->
            val rawName = name.substringAfterLast('/').lowercase()
            val normalized = rawName.replace(Regex("[^a-z0-9]"), "")
            val tokens = rawName.split(Regex("[^a-z0-9]+"))
            aliases.any { normalized == it || it in tokens }
        }

    private fun isOriginalTargetSizesInput(name: String): Boolean =
        name.substringAfterLast('/').lowercase() in setOf("orig_target_sizes", "target_sizes", "orig_sizes", "sizes")

    private fun parseNamesFromMetadata(raw: String): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        // Metadata names may contain spaces and any Unicode letters, including Turkish.
        val regex = Regex("""[\"']?(\d+)[\"']?\s*:\s*[\"'](.+?)[\"'](?=\s*[,}])""")
        regex.findAll(raw).forEach { match ->
            val id = match.groupValues[1].toIntOrNull()
            val name = match.groupValues[2].trim()
            if (id != null && name.isNotEmpty()) {
                result[id] = name
            }
        }
        if (result.isEmpty()) {
            val listRegex = Regex("""[\"'](.+?)[\"'](?=\s*[,\]])""")
            val items = listRegex.findAll(raw).map { it.groupValues[1].trim() }.toList()
            items.forEachIndexed { idx, name ->
                if (name.isNotEmpty()) {
                    result[idx] = name
                }
            }
        }
        return result
    }

    private fun nmsDetections(dets: List<YoloDetection>, iouThreshold: Float = 0.5f): List<YoloDetection> {
        if (dets.size <= 1) return dets
        val result = mutableListOf<YoloDetection>()
        for (classId in dets.map { it.classId }.distinct()) {
            val group = dets.filter { it.classId == classId }.sortedByDescending { it.confidence }.toMutableList()
            while (group.isNotEmpty()) {
                val best = group.removeAt(0)
                result.add(best)
                val it = group.iterator()
                while (it.hasNext()) {
                    val candidate = it.next()
                    // Do not suppress a low-IoU box solely because most of its
                    // area is covered: a high-confidence tile fragment can be
                    // smaller than, and hide, the complete lower-confidence box.
                    if (iou(best.box, candidate.box) > iouThreshold) it.remove()
                }
            }
        }
        return result
    }

    private fun intersectionOverSmallerArea(a: RectF, b: RectF): Float {
        val intersectionWidth = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val intersectionHeight = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val intersection = intersectionWidth * intersectionHeight
        val smallerArea = min(a.width() * a.height(), b.width() * b.height())
        return if (smallerArea > 0f) intersection / smallerArea else 0f
    }

    /**
     * Join detections clipped by opposite edges of neighboring vertical tiles.
     * Regular NMS cannot reliably join these because the visible fragments may
     * have low IoU even though they are parts of the same object.
     */
    private fun mergeTileSeamDetections(detections: List<TileDetection>): List<YoloDetection> {
        if (detections.size <= 1) return detections.map { it.detection }

        val consumed = BooleanArray(detections.size)
        val merged = mutableListOf<YoloDetection>()
        for (i in detections.indices) {
            if (consumed[i]) continue
            val first = detections[i]
            var bestMatch = -1
            var bestOverlap = 0f
            for (j in detections.indices) {
                if (i == j || consumed[j]) continue
                val second = detections[j]
                val verticalNeighbors = first.tileColumn == second.tileColumn &&
                    ((first.tileRow + 1 == second.tileRow && first.touchesBottom && second.touchesTop) ||
                        (second.tileRow + 1 == first.tileRow && second.touchesBottom && first.touchesTop))
                val horizontalNeighbors = first.tileRow == second.tileRow &&
                    ((first.tileColumn + 1 == second.tileColumn && first.touchesRight && second.touchesLeft) ||
                        (second.tileColumn + 1 == first.tileColumn && second.touchesRight && first.touchesLeft))
                if ((!verticalNeighbors && !horizontalNeighbors) ||
                    first.detection.classId != second.detection.classId
                ) continue

                val a = first.detection.box
                val b = second.detection.box
                val overlapW = max(0f, min(a.right, b.right) - max(a.left, b.left))
                val overlapH = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
                val minW = min(a.width(), b.width())
                val minH = min(a.height(), b.height())
                if (minW <= 0f || minH <= 0f) continue
                val orthogonalOverlap = if (verticalNeighbors) overlapW / minW else overlapH / minH
                val seamOverlap = if (verticalNeighbors) overlapH / minH else overlapW / minW
                val areaOverlap = intersectionOverSmallerArea(a, b)

                // Require substantial horizontal alignment and actual shared
                // area in the tile overlap, reducing accidental joins of nearby bubbles.
                if (orthogonalOverlap >= 0.60f && seamOverlap >= 0.20f && areaOverlap >= 0.20f) {
                    val matchScore = orthogonalOverlap * seamOverlap
                    if (matchScore > bestOverlap) {
                        bestOverlap = matchScore
                        bestMatch = j
                    }
                }
            }

            if (bestMatch >= 0) {
                val second = detections[bestMatch]
                consumed[bestMatch] = true
                val a = first.detection
                val b = second.detection
                merged += a.copy(
                    confidence = max(a.confidence, b.confidence),
                    box = RectF(
                        min(a.box.left, b.box.left),
                        min(a.box.top, b.box.top),
                        max(a.box.right, b.box.right),
                        max(a.box.bottom, b.box.bottom)
                    )
                )
            } else {
                merged += first.detection
            }
            consumed[i] = true
        }
        return merged
    }

    private fun tileStarts(length: Int, tileSize: Int, overlap: Int): List<Int> {
        if (length <= tileSize) return listOf(0)
        val step = (tileSize - overlap).coerceAtLeast(1)
        val starts = mutableListOf(0)
        val last = length - tileSize
        var next = step
        while (next < last) {
            starts += next
            next += step
        }
        if (starts.last() != last) starts += last
        if (starts.size >= 3 && last - starts[starts.size - 2] < step / 2) {
            starts.removeAt(starts.size - 2)
        }
        return starts
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interL = max(a.left, b.left)
        val interT = max(a.top, b.top)
        val interR = min(a.right, b.right)
        val interB = min(a.bottom, b.bottom)
        val interW = max(0f, interR - interL)
        val interH = max(0f, interB - interT)
        val inter = interW * interH
        if (inter <= 0f) return 0f
        val union = (a.width() * a.height() + b.width() * b.height()) - inter
        return if (union > 0f) inter / union else 0f
    }

    fun getLabelForClass(classId: Int, totalClassesInHead: Int): String {
        if (classId < 0 || classId >= totalClassesInHead) return "unknown"
        dynamicClassNames[classId]?.let { return it.lowercase() }
        if (!verifiedBundledClassMap) return "unknown"
        return DEFAULT_CLASS_NAMES.getOrNull(classId) ?: "unknown"
    }

    private fun effectiveClassCount(headClassCount: Int? = null): Int {
        val metadataClassCount = dynamicClassNames.keys.maxOrNull()?.plus(1)
        return headClassCount?.takeIf { it > 0 }
            ?: metadataClassCount?.takeIf { it > 0 }
            ?: DEFAULT_CLASS_NAMES.size
    }

    private fun sha256(file: File): String = synchronized(hashCacheLock) {
        val cacheKey = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        if (cachedModelHashKey == cacheKey) {
            cachedModelHash?.let { return@synchronized it }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        cachedModelHashKey = cacheKey
        cachedModelHash = hash
        hash
    }

    private fun invalidateModelHashCache() = synchronized(hashCacheLock) {
        cachedModelHashKey = null
        cachedModelHash = null
    }

    fun initOnnxEngine(): Boolean = synchronized(sessionLock) {
        // The constructor's background initializer and the first inference can race.
        // Reuse an already initialized session instead of hashing/loading it twice.
        if (isSessionInitialized && ortSession != null) return@synchronized true
        // Never let a previous model's trusted class map survive a failed or replacement init.
        verifiedBundledClassMap = false
        dynamicClassNames = emptyMap()
        try {
            val modelFile = getModelFile()
            if (modelFile != null && modelFile.exists() && modelFile.length() > MIN_MODEL_FILE_BYTES) {
                if (ortEnv == null) {
                    ortEnv = OrtEnvironment.getEnvironment()
                }
                val previousSession = ortSession
                ortSession = null
                isSessionInitialized = false
                previousSession?.close()
                val session = OrtSession.SessionOptions().use { sessionOptions ->
                    sessionOptions.setIntraOpNumThreads(2)
                    ortEnv!!.createSession(modelFile.absolutePath, sessionOptions)
                }
                ortSession = session
                verifiedBundledClassMap = runCatching { sha256(modelFile) == VERIFIED_BUNDLED_MODEL_SHA256 }.getOrDefault(false)

                // 1. Giriş boyutunu ve isimlerini session.inputInfo üzerinden oku
                var inputW = DEFAULT_INPUT_SIZE
                var inputH = DEFAULT_INPUT_SIZE
                var hasOrigSizes = false
                try {
                    val inputInfo = session.inputInfo
                    for ((name, nodeInfo) in inputInfo) {
                        if (isOriginalTargetSizesInput(name)) {
                            hasOrigSizes = true
                        }
                        val tensorInfo = nodeInfo?.info as? TensorInfo
                        val tensorShape = tensorInfo?.shape
                        if (tensorShape != null && tensorShape.size == 4) {
                            val h = tensorShape[2].toInt()
                            val w = tensorShape[3].toInt()
                            if (h > 0 && w > 0) {
                                inputH = h
                                inputW = w
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Giriş boyutu okunamadı, varsayılan $DEFAULT_INPUT_SIZE: ${e.message}")
                }
                modelInputWidth = inputW
                modelInputHeight = inputH
                hasOrigTargetSizesInput = hasOrigSizes

                // 2. Çıkış isimlerini kontrol et (RT-DETR çoklu çıkış: boxes, scores, labels)
                val outNames = session.outputNames
                val boxOutputName = findOutputName(
                    outNames,
                    setOf("boxes", "box", "bboxes", "predboxes", "boundingboxes")
                )
                val scoreOutputName = findOutputName(
                    outNames,
                    setOf("scores", "score", "predscores", "confidence", "confidences")
                )
                val logitsOutputName = findOutputName(outNames, setOf("logits", "predlogits", "classlogits"))
                isRtDetrMultiOutput = boxOutputName != null && (scoreOutputName != null || logitsOutputName != null)

                // 3. Sınıf adlarını ONNX metadata'sından oku
                val parsedClasses = mutableMapOf<Int, String>()
                try {
                    val customMeta = session.metadata.customMetadata
                    val namesRaw = customMeta["names"]
                    if (!namesRaw.isNullOrBlank()) {
                        parsedClasses.putAll(parseNamesFromMetadata(namesRaw))
                    }
                } catch (_: Exception) {}
                dynamicClassNames = parsedClasses

                isSessionInitialized = true
                lastInitializationError = null
                Log.i(TAG, "ONNX Dedektör başarıyla yüklendi: ${modelFile.name} (${modelFile.length() / 1024} KB), Boyut: ${inputW}x${inputH}, RT-DETR: $isRtDetrMultiOutput, OrigSizes: $hasOrigSizes")
                true
            } else {
                Log.d(TAG, "Dedektör ONNX modeli henüz hazır değil.")
                isSessionInitialized = false
                false
            }
        } catch (t: Throwable) {
            val errMsg = t.message ?: t.toString()
            Log.w(TAG, "ONNX başlatma hatası: $errMsg")
            isSessionInitialized = false
            lastInitializationError = errMsg
            false
        }
    }

    private fun ensureSessionReady(): Boolean {
        synchronized(sessionLock) {
            if (isSessionInitialized && ortSession != null) return true
        }
        return initOnnxEngine()
    }

    fun getModelFile(): File? = synchronized(modelFileLock) {
        val internalDir = File(context.filesDir, "models")
        if (!internalDir.exists() && !internalDir.mkdirs()) return@synchronized null
        val internalFile = File(internalDir, MODEL_FILENAME)
        // Imported models are accepted above MIN_MODEL_FILE_BYTES; do not replace a
        // valid small model with the bundled asset on a later initialization.
        if (internalFile.exists() && internalFile.length() > MIN_MODEL_FILE_BYTES) return@synchronized internalFile

        val tempFile = File(internalDir, "${MODEL_FILENAME}.tmp")
        try {
            val stream = try {
                context.assets.open(EMBEDDED_ASSET_PATH)
            } catch (_: Throwable) {
                try {
                    context.assets.open(MODEL_FILENAME)
                } catch (_: Throwable) {
                    try {
                        context.assets.open("models/$LEGACY_MODEL_FILENAME")
                    } catch (_: Throwable) {
                        null
                    }
                }
            }

            if (stream != null) {
                stream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
                if (tempFile.exists() && tempFile.length() > MIN_MODEL_FILE_BYTES) {
                    if (internalFile.exists()) internalFile.delete()
                    if (tempFile.renameTo(internalFile)) {
                        invalidateModelHashCache()
                        return@synchronized internalFile
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gömülü model kopyalama hatası: ${e.message}")
        }
        if (tempFile.exists()) tempFile.delete()
        if (internalFile.exists() && internalFile.length() > MIN_MODEL_FILE_BYTES) internalFile else null
    }

    // Both fields are volatile; avoid waiting for inference/session initialization
    // when Settings queries model status on the main thread.
    fun isModelLoaded(): Boolean = isSessionInitialized && ortSession != null

    suspend fun importModelFile(inputStream: InputStream): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val internalDir = File(context.filesDir, "models")
        if (!internalDir.exists()) internalDir.mkdirs()
        val tempFile = File(internalDir, "${MODEL_FILENAME}.import.tmp")
        val targetFile = File(internalDir, MODEL_FILENAME)
        val backupFile = File(internalDir, "${MODEL_FILENAME}.backup")

        try {
            synchronized(modelFileLock) {
                FileOutputStream(tempFile).use { output ->
                    inputStream.copyTo(output)
                    output.flush()
                }
                if (!tempFile.exists() || tempFile.length() <= MIN_MODEL_FILE_BYTES) {
                    tempFile.delete()
                    return@withContext Pair(false, "Dosya çok küçük veya bozuk.")
                }

                // Reject malformed/non-ONNX files before touching the currently working model.
                val validationSession = OrtSession.SessionOptions().use { options ->
                    OrtEnvironment.getEnvironment().createSession(tempFile.absolutePath, options)
                }
                val validSession = try {
                    validationSession.inputNames.isNotEmpty() && validationSession.outputNames.isNotEmpty()
                } finally {
                    validationSession.close()
                }
                if (!validSession) {
                    tempFile.delete()
                    return@withContext Pair(false, "ONNX modeli giriş veya çıkış tensörü içermiyor.")
                }

                if (backupFile.exists() && !backupFile.delete()) {
                    tempFile.delete()
                    return@withContext Pair(false, "Eski model yedeği temizlenemedi.")
                }
                if (targetFile.exists() && !targetFile.renameTo(backupFile)) {
                    tempFile.delete()
                    return@withContext Pair(false, "Mevcut model yedeklenemedi.")
                }
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.delete()
                    if (backupFile.exists()) backupFile.renameTo(targetFile)
                    return@withContext Pair(false, "Yeni model dosyası kaydedilemedi.")
                }
            }

            invalidateModelHashCache()
            closeSession()
            val loaded = initOnnxEngine()
            if (loaded) {
                synchronized(modelFileLock) { backupFile.delete() }
                Pair(true, null)
            } else {
                val importError = lastInitializationError ?: "Model yüklenemedi."
                closeSession()
                synchronized(modelFileLock) {
                    targetFile.delete()
                    if (backupFile.exists()) backupFile.renameTo(targetFile)
                }
                invalidateModelHashCache()
                initOnnxEngine()
                Pair(false, importError)
            }
        } catch (e: Throwable) {
            val msg = e.localizedMessage ?: e.message ?: e.toString()
            Log.e(TAG, "Model yükleme hatası: $msg", e)
            lastInitializationError = msg
            if (tempFile.exists()) tempFile.delete()
            Pair(false, msg)
        }
    }

    suspend fun importModelFromUri(uri: Uri): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Pair(false, "Seçilen dosya açılamadı.")
            inputStream.use { importModelFile(it) }
        } catch (e: Throwable) {
            val msg = e.localizedMessage ?: e.message ?: e.toString()
            lastInitializationError = msg
            Pair(false, msg)
        }
    }

    fun closeSession() {
        synchronized(sessionLock) {
            verifiedBundledClassMap = false
            dynamicClassNames = emptyMap()
            try {
                val oldSession = ortSession
                ortSession = null
                isSessionInitialized = false
                oldSession?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Oturum kapatma hatası: ${e.message}")
            }
        }
    }

    fun getModelInfo(): Map<String, String> {
        // Settings can call this on the main thread. Inspect the existing file only;
        // model extraction is left to the background ONNX initialization path.
        val modelFile = File(context.filesDir, "models/$MODEL_FILENAME").takeIf { it.isFile }
        val loaded = isModelLoaded()
        val classLabels = if (dynamicClassNames.isNotEmpty()) {
            dynamicClassNames.values.joinToString(", ")
        } else {
            if (verifiedBundledClassMap) "bubble (0), text_bubble (1), text_free (2) [SHA-256 verified]" else "Unknown (model metadata has no class names)"
        }
        return mapOf(
            "engine" to "Microsoft ONNX Runtime Android (Embedded Asset)",
            "model_name" to (modelFile?.name ?: MODEL_FILENAME),
            "status" to if (loaded) "Aktif & Yüklendi (RT-DETR-v2 Comic AI, ${(modelFile?.length() ?: 0) / 1024} KB)" else if (lastInitializationError != null) "Yükleme Hatası" else "Model Bekleniyor",
            "target_path" to "${context.filesDir.absolutePath}/models/$MODEL_FILENAME",
            "file_size" to if (modelFile?.exists() == true) "${modelFile.length()} bayt" else "Mevcut Değil",
            "input_size" to "${modelInputWidth}x${modelInputHeight}",
            "architecture" to if (isRtDetrMultiOutput) "RT-DETR-v2 End-to-End Transformer" else "Ultralytics YOLO / End2End",
            "last_error" to (lastInitializationError ?: "Hata Yok"),
            "classes" to classLabels
        )
    }

    suspend fun detectSpeechBubbles(
        bitmap: Bitmap?,
        confidenceThreshold: Float = DEFAULT_CONF_THRESHOLD
    ): List<BubbleBox> = detectSpeechBubblesWithResult(bitmap, confidenceThreshold).detections

    /** Returns a per-call error value, so concurrent callers don't need the shared status field. */
    suspend fun detectSpeechBubblesWithResult(
        bitmap: Bitmap?,
        confidenceThreshold: Float = DEFAULT_CONF_THRESHOLD
    ): DetectionResult = withContext(Dispatchers.Default) {
        detectionMutex.lock()
        try {
            lastInferenceError = null
            val detections = detectSpeechBubblesLocked(bitmap, confidenceThreshold)
            DetectionResult(detections, lastInferenceError)
        } finally {
            detectionMutex.unlock()
        }
    }

    private suspend fun detectSpeechBubblesLocked(
        bitmap: Bitmap?,
        confidenceThreshold: Float
    ): List<BubbleBox> {
        lastInferenceError = null
        if (bitmap == null || bitmap.isRecycled || bitmap.width < 30 || bitmap.height < 30) {
            lastInferenceError = "Geçersiz veya çok küçük görüntü."
            return emptyList()
        }
        if (!ensureSessionReady()) {
            lastInferenceError = lastInitializationError ?: "ONNX modeli başlatılamadı."
            return emptyList()
        }

        val origW = bitmap.width
        val origH = bitmap.height

        // Avoid shrinking high-resolution spreads and near-square pages to one
        // 640px input. Overlapped square crops preserve small text and bubbles.
        val inputLongSide = max(modelInputWidth, modelInputHeight)
        if (origH <= origW * 2.2f && max(origW, origH) >= inputLongSide * 2.5f) {
            val sourceTileSide = min(min(origW, origH), inputLongSide * 2)
            val overlap = (sourceTileSide * 0.25f).toInt().coerceAtLeast(1)
            val xStarts = tileStarts(origW, sourceTileSide, overlap)
            val yStarts = tileStarts(origH, sourceTileSide, overlap)
            val tiledDetections = mutableListOf<TileDetection>()
            for ((row, tileY) in yStarts.withIndex()) {
                for ((column, tileX) in xStarts.withIndex()) {
                    val tileW = min(sourceTileSide, origW - tileX)
                    val tileH = min(sourceTileSide, origH - tileY)
                    val tile = try {
                        Bitmap.createBitmap(bitmap, tileX, tileY, tileW, tileH)
                    } catch (_: Exception) {
                        null
                    }
                    if (tile == null) {
                        lastInferenceError = "Görüntü tile'ı oluşturulamadı (x=$tileX, y=$tileY)."
                        continue
                    }
                    try {
                        for (det in runOnnxInference(tile, confidenceThreshold)) {
                            tiledDetections += TileDetection(
                                detection = det.copy(
                                    box = RectF(
                                        (tileX + det.box.left * tileW) / origW.toFloat(),
                                        (tileY + det.box.top * tileH) / origH.toFloat(),
                                        (tileX + det.box.right * tileW) / origW.toFloat(),
                                        (tileY + det.box.bottom * tileH) / origH.toFloat()
                                    )
                                ),
                                tileRow = row,
                                tileColumn = column,
                                touchesTop = det.box.top <= 0.015f,
                                touchesBottom = det.box.bottom >= 0.985f,
                                touchesLeft = det.box.left <= 0.015f,
                                touchesRight = det.box.right >= 0.985f
                            )
                        }
                    } finally {
                        if (tile !== bitmap && !tile.isRecycled) tile.recycle()
                    }
                }
            }
            return nmsDetections(mergeTileSeamDetections(tiledDetections))
                .mapIndexed { index, det ->
                    BubbleBox(index + 1, det.box, det.confidence, det.label)
                }
        }

        // Çok uzun Webtoon sayfalarında dikey parçalama (tiling)
        if (origH > origW * 2.2f && origH > 2400) {
            val tileH = (origW * 1.8f).toInt().coerceIn(1200, 2400)
            val overlap = (origW * 0.25f).toInt().coerceIn(150, 400)
            val allDetections = mutableListOf<TileDetection>()
            var tileRow = 0
            var y = 0
            while (y < origH) {
                val remaining = origH - y
                // Avoid stretching a very short final strip into the model's square input.
                // It is already covered by the previous tile when it fits inside the overlap.
                if (y > 0 && remaining <= overlap) break
                if (y > 0 && remaining < tileH) y = (origH - tileH).coerceAtLeast(0)
                val h = min(tileH, origH - y)
                val tile = try {
                    Bitmap.createBitmap(bitmap, 0, y, origW, h)
                } catch (_: Exception) {
                    null
                }
                if (tile == null) {
                    lastInferenceError = "Dikey görüntü tile'ı oluşturulamadı (y=$y)."
                } else {
                    val tileDets = runOnnxInference(tile, confidenceThreshold)
                    for (det in tileDets) {
                        val normL = det.box.left
                        val normR = det.box.right
                        val normT = (det.box.top * h + y) / origH.toFloat()
                        val normB = (det.box.bottom * h + y) / origH.toFloat()
                        allDetections.add(
                            TileDetection(
                                detection = det.copy(box = RectF(normL, normT, normR, normB)),
                                tileRow = tileRow,
                                tileColumn = 0,
                                touchesTop = det.box.top <= 0.015f,
                                touchesBottom = det.box.bottom >= 0.985f,
                                touchesLeft = false,
                                touchesRight = false
                            )
                        )
                    }
                    if (tile !== bitmap && !tile.isRecycled) tile.recycle()
                }
                tileRow++
                if (y + h >= origH) break
                y += (tileH - overlap)
            }
            val seamMerged = mergeTileSeamDetections(allDetections)
            return nmsDetections(seamMerged).mapIndexed { index, det ->
                BubbleBox(
                    id = index + 1,
                    bounds = det.box,
                    confidence = det.confidence,
                    label = det.label
                )
            }
        }

        val detections = runOnnxInference(bitmap, confidenceThreshold)
        return detections.mapIndexed { index, det ->
            BubbleBox(
                id = index + 1,
                bounds = det.box,
                confidence = det.confidence,
                label = det.label
            )
        }
    }

    private suspend fun runOnnxInference(source: Bitmap, confThreshold: Float): List<YoloDetection> =
        inferenceMutex.withLock {
            synchronized(sessionLock) {
                runOnnxInferenceLocked(source, confThreshold)
            }
        }

    private fun runOnnxInferenceLocked(source: Bitmap, confThreshold: Float): List<YoloDetection> {
        val session = ortSession ?: run {
            lastInferenceError = "ONNX oturumu hazır değil."
            return emptyList()
        }
        val env = ortEnv ?: run {
            lastInferenceError = "ONNX çalışma ortamı hazır değil."
            return emptyList()
        }

        val inputW = modelInputWidth
        val inputH = modelInputHeight
        val origW = source.width.toFloat()
        val origH = source.height.toFloat()

        // The bundled RT-DETR model is trained on a direct 640x640 resize (no letterbox).
        // Letterboxing here shifts boxes and changes the geometry the model learned.
        val scaleX = inputW / origW
        val scaleY = inputH / origH

        var scaledBmp: Bitmap? = null
        var inputTensor: OnnxTensor? = null
        var sizeTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null

        try {
            scaledBmp = Bitmap.createScaledBitmap(source, inputW, inputH, true)
            val numPixels = inputW * inputH
            val floatArray = getOrCreateFloatArray(inputW, inputH)

            val gOffset = numPixels
            val bOffset = 2 * numPixels
            val pixels = getOrCreatePixelArray(numPixels)
            scaledBmp.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)

            for (y in 0 until inputH) {
                val srcRowOffset = y * inputW
                val dstRowOffset = y * inputW
                for (x in 0 until inputW) {
                    val pixel = pixels[srcRowOffset + x]
                    val targetIdx = dstRowOffset + x
                    val alpha = ((pixel ushr 24) and 0xFF) / 255.0f
                    // Composite transparent pixels over white paper instead of treating
                    // their hidden RGB values as visible black ink.
                    val r = (((pixel shr 16) and 0xFF) * alpha + 255f * (1f - alpha)) / 255f
                    val g = (((pixel shr 8) and 0xFF) * alpha + 255f * (1f - alpha)) / 255f
                    val b = ((pixel and 0xFF) * alpha + 255f * (1f - alpha)) / 255f
                    floatArray[targetIdx] = r
                    floatArray[gOffset + targetIdx] = g
                    floatArray[bOffset + targetIdx] = b
                }
            }

            val detections = mutableListOf<YoloDetection>()
            val inputShape = longArrayOf(1, 3, inputH.toLong(), inputW.toLong())
            val inputNames = session.inputNames.toList()
            val imageInputName = inputNames.firstOrNull {
                !isOriginalTargetSizesInput(it) && it.contains("image", ignoreCase = true)
            } ?: inputNames.firstOrNull { !isOriginalTargetSizesInput(it) }
                ?: run {
                    lastInferenceError = "ONNX modelinde görüntü girişi bulunamadı."
                    return emptyList()
                }
            val imageTensorInfo = (session.inputInfo[imageInputName]?.info as? TensorInfo)
            if (imageTensorInfo == null || imageTensorInfo.type != OnnxJavaType.FLOAT) {
                lastInferenceError = "Desteklenmeyen görüntü girişi tipi: ${imageTensorInfo?.type ?: "unknown"}."
                if (!warnedUnsupportedInputType) {
                    warnedUnsupportedInputType = true
                    Log.e(
                        TAG,
                        "Desteklenmeyen ONNX görüntü girişi tipi: " +
                            "name=$imageInputName, type=${imageTensorInfo?.type ?: "unknown"}. " +
                            "Bu dedektör Float32 giriş bekliyor."
                    )
                }
                return emptyList()
            }
            inputTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(floatArray, 0, 3 * numPixels),
                inputShape
            )

            val inputMap = mutableMapOf<String, OnnxTensor>(imageInputName to inputTensor)

            // RT-DETR orig_target_sizes girdisi (varsa)
            val origSizesInputName = session.inputNames.firstOrNull {
                isOriginalTargetSizesInput(it)
            }
            if (origSizesInputName != null) {
                // RT-DETR expects target sizes as (width, height), matching the model author's
                // reference implementation (comic-translate/modules/detection/rtdetr_v2_onnx.py).
                val sizes = longArrayOf(origW.toLong(), origH.toLong())
                sizeTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(sizes), longArrayOf(1, 2))
                inputMap[origSizesInputName] = sizeTensor
            }

            results = session.run(inputMap)

            // --- 1. Biçim: RT-DETR / çoklu çıkış modeli ---
            val outputNames = session.outputNames
            val boxesName = findOutputName(
                outputNames,
                setOf("boxes", "box", "bboxes", "predboxes", "boundingboxes")
            )
            val scoresName = findOutputName(
                outputNames,
                setOf("scores", "score", "predscores", "confidence", "confidences")
            )
            val logitsName = findOutputName(outputNames, setOf("logits", "predlogits", "classlogits"))
            val labelsName = findOutputName(
                outputNames,
                setOf("labels", "label", "classes", "classids", "classid", "predlabels", "classnames")
            )
            if (isRtDetrMultiOutput) {
                if (boxesName == null || (scoresName == null && logitsName == null)) {
                    lastInferenceError = "RT-DETR çıkış adları beklenen kutu/skor bilgilerini içermiyor."
                    Log.w(TAG, "Çoklu çıkış modeli tanındı ancak kutu/skor çıkış adları çözümlenemedi: $outputNames")
                    return emptyList()
                }
                val boxesOpt = results.get(boxesName)
                val scoresOpt = scoresName?.let { results.get(it) }
                val logitsOpt = logitsName?.let { results.get(it) }
                val labelsOpt = labelsName?.let { results.get(it) }

                val boxesTensor = boxesOpt.get() as? OnnxTensor
                val scoresTensor = scoresOpt?.takeIf { it.isPresent }?.get() as? OnnxTensor
                val logitsTensor = logitsOpt?.takeIf { it.isPresent }?.get() as? OnnxTensor
                val labelsTensor = labelsOpt?.takeIf { it.isPresent }?.get() as? OnnxTensor

                if (boxesTensor != null && (scoresTensor != null || logitsTensor != null)) {
                    val bBuf = boxesTensor.floatBuffer
                    val bData = FloatArray(bBuf.remaining()).also { bBuf.get(it) }
                    val normalizedBoxesName = boxesName.substringAfterLast('/').lowercase()
                        .replace(Regex("[^a-z0-9]"), "")
                    val normalizedLogitsName = logitsName?.substringAfterLast('/')?.lowercase()
                        ?.replace(Regex("[^a-z0-9]"), "")
                    // Hugging Face RT-DETR exposes normalized cxcywh as pred_boxes
                    // and one class logit per label; its focal-loss postprocessor uses sigmoid.
                    val isHuggingFaceRtDetrOutput = normalizedBoxesName == "predboxes" &&
                        normalizedLogitsName != null &&
                        normalizedLogitsName in setOf("logits", "predlogits", "classlogits")
                    val logitsData: FloatArray?
                    val logitsPerDetection: Int
                    val numDets: Int
                    val sData: FloatArray
                    val boxesCount = boxesTensor.info.shape
                        .getOrNull(boxesTensor.info.shape.size - 2)?.toInt() ?: 0
                    val logitsLabels = IntArray(boxesCount)
                    if (scoresTensor != null) {
                        val sBuf = scoresTensor.floatBuffer
                        sData = FloatArray(sBuf.remaining()).also { sBuf.get(it) }
                        numDets = if (boxesCount > 0) min(boxesCount, sData.size)
                        else scoresTensor.info.shape.last().toInt()
                        logitsData = null
                        logitsPerDetection = 0
                    } else {
                        val tensor = logitsTensor!!
                        val logitsBuffer = tensor.floatBuffer
                        logitsData = FloatArray(logitsBuffer.remaining()).also { logitsBuffer.get(it) }
                        logitsPerDetection = tensor.info.shape.last().toInt()
                        if (logitsPerDetection <= 0) {
                            lastInferenceError = "RT-DETR logits çıkışının sınıf boyutu geçersiz."
                            return emptyList()
                        }
                        numDets = min(boxesCount, logitsData.size / logitsPerDetection)
                        sData = FloatArray(numDets)
                        val configuredClasses = dynamicClassNames.keys.maxOrNull()?.plus(1)
                            ?: if (verifiedBundledClassMap) DEFAULT_CLASS_NAMES.size
                            else logitsPerDetection
                        val usableClassCount = when {
                            isHuggingFaceRtDetrOutput -> logitsPerDetection
                            logitsPerDetection == configuredClasses + 1 -> configuredClasses
                            dynamicClassNames.isEmpty() && logitsPerDetection == 81 -> 80
                            else -> min(logitsPerDetection, configuredClasses)
                        }.coerceAtLeast(1)
                        for (detection in 0 until min(numDets, logitsLabels.size)) {
                            var maxForegroundLogit = Float.NEGATIVE_INFINITY
                            var maxAllLogit = Float.NEGATIVE_INFINITY
                            var bestClass = 0
                            for (classIndex in 0 until usableClassCount) {
                                val logit = logitsData[detection * logitsPerDetection + classIndex]
                                if (logit > maxForegroundLogit) {
                                    maxForegroundLogit = logit
                                    bestClass = classIndex
                                }
                            }
                            if (isHuggingFaceRtDetrOutput) {
                                sData[detection] = if (maxForegroundLogit.isFinite()) {
                                    (1.0 / (1.0 + kotlin.math.exp(-maxForegroundLogit.toDouble()))).toFloat()
                                } else 0f
                            } else {
                                for (classIndex in 0 until usableClassCount) {
                                    maxAllLogit = max(
                                        maxAllLogit,
                                        logitsData[detection * logitsPerDetection + classIndex]
                                    )
                                }
                                var denominator = 0.0
                                for (classIndex in 0 until usableClassCount) {
                                    denominator += kotlin.math.exp(
                                        (logitsData[detection * logitsPerDetection + classIndex] - maxAllLogit).toDouble()
                                    )
                                }
                                sData[detection] = if (denominator > 0.0 && maxForegroundLogit.isFinite()) {
                                    (kotlin.math.exp((maxForegroundLogit - maxAllLogit).toDouble()) / denominator).toFloat()
                                } else 0f
                            }
                            logitsLabels[detection] = bestClass
                        }
                    }
                    val boxesAreNormalized = isHuggingFaceRtDetrOutput ||
                        (origSizesInputName == null &&
                            bData.maxOfOrNull { abs(it) }?.let { it <= 2f } == true)

                    // -1 means the exporter did not provide a usable class ID. Do not
                    // silently reinterpret an unlabelled box as class 0 (bubble).
                    val labelsData = IntArray(numDets) { -1 }
                    if (labelsTensor != null) {
                        runCatching {
                            val labelBuffer = labelsTensor.longBuffer
                            for (i in 0 until min(numDets, labelBuffer.remaining())) labelsData[i] = labelBuffer.get().toInt()
                        }.recoverCatching {
                            val labelBuffer = labelsTensor.intBuffer
                            for (i in 0 until min(numDets, labelBuffer.remaining())) labelsData[i] = labelBuffer.get()
                        }.recoverCatching {
                            val labelBuffer = labelsTensor.floatBuffer
                            for (i in 0 until min(numDets, labelBuffer.remaining())) {
                                labelsData[i] = labelBuffer.get().toInt()
                            }
                        }
                    } else if (logitsTensor != null) {
                        for (i in 0 until min(numDets, logitsLabels.size)) labelsData[i] = logitsLabels[i]
                    }

                    val missingLabelCount = labelsData.count { it < 0 }
                    if (missingLabelCount > 0 && !warnedMissingRtDetrLabels) {
                        warnedMissingRtDetrLabels = true
                        Log.w(
                            TAG,
                            "RT-DETR çıktısında $missingLabelCount/$numDets tespit için sınıf etiketi yok; " +
                                "bu kutular 'unknown' olarak korunacak, sınıf 0 varsayılmayacak. " +
                                "Çıkışlar: $outputNames"
                        )
                    }

                    for (i in 0 until numDets) {
                        val score = sData[i]
                        if (score >= confThreshold) {
                            val rawX1 = bData[i * 4 + 0]
                            val rawY1 = bData[i * 4 + 1]
                            val rawX2 = bData[i * 4 + 2]
                            val rawY2 = bData[i * 4 + 3]
                            val x1: Float
                            val y1: Float
                            val x2: Float
                            val y2: Float
                            if (isHuggingFaceRtDetrOutput) {
                                // HF pred_boxes use normalized center-x, center-y, width, height.
                                x1 = rawX1 - rawX2 / 2f
                                y1 = rawY1 - rawY2 / 2f
                                x2 = rawX1 + rawX2 / 2f
                                y2 = rawY1 + rawY2 / 2f
                            } else {
                                x1 = rawX1
                                y1 = rawY1
                                x2 = rawX2
                                y2 = rawY2
                            }
                            // Preserve boxes with missing labels as unknown; assigning class 0
                            // here would falsely turn every unlabelled detection into a bubble.
                            val rawClassId = labelsData.getOrElse(i) { -1 }

                            val label = getLabelForClass(rawClassId, effectiveClassCount())

                            // Koordinat normalizasyonu
                            val normL: Float
                            val normT: Float
                            val normR: Float
                            val normB: Float

                            if (boxesAreNormalized) {
                                normL = min(x1, x2).coerceIn(0f, 1f)
                                normT = min(y1, y2).coerceIn(0f, 1f)
                                normR = max(x1, x2).coerceIn(normL, 1f)
                                normB = max(y1, y2).coerceIn(normT, 1f)
                            } else if (origSizesInputName != null) {
                                // RT-DETR post-processing scales boxes back to the supplied original size.
                                // Decide the coordinate space from the model input, never from this box's location.
                                normL = (min(x1, x2) / origW).coerceIn(0f, 1f)
                                normT = (min(y1, y2) / origH).coerceIn(0f, 1f)
                                normR = (max(x1, x2) / origW).coerceIn(normL, 1f)
                                normB = (max(y1, y2) / origH).coerceIn(normT, 1f)
                            } else {
                                val px1 = min(x1, x2)
                                val py1 = min(y1, y2)
                                val px2 = max(x1, x2)
                                val py2 = max(y1, y2)

                                val origLeft = (px1 / scaleX).coerceIn(0f, origW)
                                val origTop = (py1 / scaleY).coerceIn(0f, origH)
                                val origRight = (px2 / scaleX).coerceIn(0f, origW)
                                val origBottom = (py2 / scaleY).coerceIn(0f, origH)

                                normL = origLeft / origW
                                normT = origTop / origH
                                normR = origRight / origW
                                normB = origBottom / origH
                            }

                            val normW = normR - normL
                            val normH = normB - normT
                            if (normW in 0.008f..0.98f && normH in 0.006f..0.98f) {
                                detections.add(
                                    YoloDetection(
                                        classId = rawClassId,
                                        label = label,
                                        confidence = score,
                                        box = RectF(normL, normT, normR, normB)
                                    )
                                )
                            }
                        }
                    }
                    return detections
                }
            }

            // An unrecognized multi-output graph must not be parsed as a single YOLO tensor.
            if (outputNames.size > 1) {
                lastInferenceError = "ONNX modelinin çoklu çıkış biçimi desteklenmiyor: $outputNames"
                Log.w(TAG, "Bilinmeyen çoklu çıkış biçimi, hatalı ayrıştırma önlendi: $outputNames")
                return emptyList()
            }

            // --- 2. Biçim: Tek Çıkışlı Model (YOLO End-to-End veya YOLOv8) ---
            val outputTensor = (
                try {
                    results.get("output0").orElse(null)
                } catch (_: Exception) { null }
                    ?: try { results.get(0) } catch (_: Exception) { null }
            ) as? OnnxTensor ?: run {
                lastInferenceError = "Tek çıkışlı ONNX modelinde output0 tensörü bulunamadı."
                Log.w(TAG, "Tek çıkışlı modelde output0/0 tensörü bulunamadı: $outputNames")
                return emptyList()
            }

            val shape = outputTensor.info.shape
            if (shape.size != 3) {
                lastInferenceError = "Desteklenmeyen model çıkış boyutu: ${shape.contentToString()}"
                Log.w(TAG, "Desteklenmeyen tek çıkış tensör boyutu: ${shape.contentToString()}")
                return emptyList()
            }
            val rawBuffer = outputTensor.floatBuffer
            val totalElements = rawBuffer.remaining()
            val data = FloatArray(totalElements)
            rawBuffer.get(data)
            val dim1 = shape[1].toInt()
            val dim2 = shape[2].toInt()
            if (data.size.toLong() < dim1.toLong() * dim2.toLong()) {
                lastInferenceError = "Model çıkışı bildirilen tensör boyutundan kısa."
                Log.w(TAG, "Model çıktısı şeklin gerektirdiğinden kısa: shape=${shape.contentToString()}, elements=${data.size}")
                return emptyList()
            }
            val rowMajor = dim1 > dim2 && dim2 in 5..512
            val featureMajor = dim2 > dim1 && dim1 in 5..512
            if (!rowMajor && !featureMajor) {
                lastInferenceError = "Model çıkış eksenleri tanınmıyor: ${shape.contentToString()}"
                Log.w(TAG, "Tanınmayan YOLO çıktı eksenleri: ${shape.contentToString()}")
                return emptyList()
            }

            val numFeatures = if (rowMajor) dim2 else dim1
            val numCandidates = if (rowMajor) dim1 else dim2
            fun value(candidate: Int, feature: Int): Float =
                if (rowMajor) data[candidate * numFeatures + feature]
                else data[feature * numCandidates + candidate]

            // Infer coordinate units once for the whole tensor. Per-box thresholds
            // misclassify pixel boxes near the top-left corner as normalized values.
            var maxCoordinateMagnitude = 0f
            for (candidate in 0 until numCandidates) {
                for (coordinate in 0..3) {
                    maxCoordinateMagnitude = max(maxCoordinateMagnitude, abs(value(candidate, coordinate)))
                }
            }
            val coordinatesAreNormalized = maxCoordinateMagnitude <= 2f

            // Six-column end-to-end rows use [box(4), score, classId]. Ultralytics
            // RT-DETR uses normalized cxcywh, while end-to-end YOLO commonly uses
            // pixel xyxy. Coordinate ordering is only meaningful for the latter;
            // applying x2 >= x1 to cxcywh rejects valid boxes whose width is less
            // than their center coordinate.
            val endToEnd = numFeatures == 6 && (0 until min(numCandidates, 64)).all { candidate ->
                val score = value(candidate, 4)
                val classId = value(candidate, 5)
                val x1 = value(candidate, 0)
                val y1 = value(candidate, 1)
                val x2 = value(candidate, 2)
                val y2 = value(candidate, 3)
                score in 0f..1.01f && classId >= 0f &&
                    abs(classId - classId.toInt()) < 0.001f &&
                    (coordinatesAreNormalized || (x2 >= x1 && y2 >= y1))
            }
            // Ultralytics RT-DETR's single-output export uses normalized
            // [cx, cy, w, h, score, classId], unlike its pixel-space xyxy end-to-end YOLO output.
            // With no explicit box-format metadata, this is the supported convention for normalized
            // six-feature detections; raw pixel-space six-feature detections remain xyxy.
            val endToEndBoxesAreCenterFormat = endToEnd && coordinatesAreNormalized

            val metadataClassCount = effectiveClassCount()
            val knownClassMetadata = dynamicClassNames.isNotEmpty() || verifiedBundledClassMap
            val hasObjectness = !endToEnd && (
                numFeatures == metadataClassCount + 5 ||
                    (dynamicClassNames.isEmpty() && numFeatures == 85)
                )
            if (!endToEnd && numFeatures == 6 && !knownClassMetadata) {
                lastInferenceError = "6 kanallı model çıkışı için sınıf metadata bilgisi bulunamadı."
                Log.w(TAG, "6 kanallı tek çıkış biçimi metadata olmadan belirsiz; yanlış YOLO ayrıştırması önlendi")
                return emptyList()
            }
            val headClassCount = when {
                endToEnd -> metadataClassCount
                hasObjectness -> numFeatures - 5
                else -> numFeatures - 4
            }
            if (headClassCount <= 0) {
                lastInferenceError = "Model çıkışında kullanılabilir sınıf kanalı bulunamadı."
                Log.w(TAG, "Çıktı sınıf kanalı bulunamadı: features=$numFeatures")
                return emptyList()
            }

            for (candidate in 0 until numCandidates) {
                val raw0 = value(candidate, 0)
                val raw1 = value(candidate, 1)
                val raw2 = value(candidate, 2)
                val raw3 = value(candidate, 3)
                var bestClass = 0
                var score: Float
                var left: Float
                var top: Float
                var right: Float
                var bottom: Float

                if (endToEnd) {
                    score = value(candidate, 4)
                    bestClass = value(candidate, 5).toInt()
                    if (endToEndBoxesAreCenterFormat) {
                        left = raw0 - raw2 / 2f
                        top = raw1 - raw3 / 2f
                        right = raw0 + raw2 / 2f
                        bottom = raw1 + raw3 / 2f
                    } else {
                        left = min(raw0, raw2)
                        top = min(raw1, raw3)
                        right = max(raw0, raw2)
                        bottom = max(raw1, raw3)
                    }
                } else {
                    val classStart = if (hasObjectness) 5 else 4
                    var classScore = Float.NEGATIVE_INFINITY
                    for (classIndex in 0 until headClassCount) {
                        val classValue = value(candidate, classStart + classIndex)
                        if (classValue > classScore) {
                            classScore = classValue
                            bestClass = classIndex
                        }
                    }
                    score = if (hasObjectness) value(candidate, 4) * classScore else classScore
                    left = raw0 - raw2 / 2f
                    top = raw1 - raw3 / 2f
                    right = raw0 + raw2 / 2f
                    bottom = raw1 + raw3 / 2f
                }

                if (!score.isFinite() || score < confThreshold || bestClass < 0) continue
                val inputScaleX = if (coordinatesAreNormalized) inputW.toFloat() else 1f
                val inputScaleY = if (coordinatesAreNormalized) inputH.toFloat() else 1f
                val normL = ((left * inputScaleX / scaleX) / origW).coerceIn(0f, 1f)
                val normT = ((top * inputScaleY / scaleY) / origH).coerceIn(0f, 1f)
                val normR = ((right * inputScaleX / scaleX) / origW).coerceIn(normL, 1f)
                val normB = ((bottom * inputScaleY / scaleY) / origH).coerceIn(normT, 1f)
                val normW = normR - normL
                val normH = normB - normT
                if (normW in 0.008f..0.98f && normH in 0.006f..0.98f) {
                    detections.add(
                        YoloDetection(
                            classId = bestClass,
                            label = getLabelForClass(bestClass, effectiveClassCount(headClassCount)),
                            confidence = score,
                            box = RectF(normL, normT, normR, normB)
                        )
                    )
                }
            }
            return nmsDetections(detections)
        } catch (e: Exception) {
            lastInferenceError = e.localizedMessage ?: e.message ?: e.toString()
            Log.e(TAG, "ONNX çıkarım hatası: ${e.message}", e)
            return emptyList()
        } finally {
            if (scaledBmp != null && scaledBmp !== source && !scaledBmp.isRecycled) {
                scaledBmp.recycle()
            }
            try { inputTensor?.close() } catch (_: Exception) {}
            try { sizeTensor?.close() } catch (_: Exception) {}
            try { results?.close() } catch (_: Exception) {}
        }
    }
}
