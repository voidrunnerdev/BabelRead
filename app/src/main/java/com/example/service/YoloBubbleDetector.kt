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
import com.example.util.ContourScanlineProfile
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
import kotlin.math.roundToInt

/**
 * RT-DETR-v2 & YOLO Çizgi Roman / Manga Konuşma Balonu ve Metin Bölgesi Dedektörü.
 * Varsayılan segmentasyon modeli: chiqui7/yolo11-manga-seg (yolo11s_seg.onnx, 4 sınıf:
 *   0: bubble (konuşma balonu)
 *   1: panel (manga panel çerçevesi - filtrelenir)
 *   2: sfx (ses efekti)
 *   3: text (balon dışı/serbest metin veya anlatım kutusu)
 * ).
 */
enum class DetectorModel(
    val id: String,
    val title: String,
    val subtitle: String,
    val assetPath: String,
    val targetFileName: String,
    val expectedBytes: Long,
    val sha256: String,
    val defaultConfidence: Float,
    val description: String
) {
    RTDETR_V2(
        id = "rtdetr_v2",
        title = "RT-DETR-v2 Comic AI",
        subtitle = "Klasik Kutu (11.1 MB)",
        assetPath = "models/rtdetr_v2.onnx",
        targetFileName = "rtdetr_v2.onnx",
        expectedBytes = 11120765L,
        sha256 = "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79",
        defaultConfidence = 0.35f,
        description = "Transformer tabanlı nesne tespiti. Balon, metin ve ses efektlerini (SFX) dikdörtgen sınır kutularıyla ayrıştırır."
    ),
    YOLO11S_SEG(
        id = "yolo11s_seg",
        title = "YOLO11s-seg Manga",
        subtitle = "Piksel Segmentasyon (39.3 MB)",
        assetPath = "models/yolo11s_seg.onnx",
        targetFileName = "yolo11s_seg.onnx",
        expectedBytes = 41227478L,
        sha256 = "d87389af4350954143a216bcfe40fd2ad93312cb136534e6073c9c740d98b646",
        defaultConfidence = 0.25f,
        description = "Manga ve çizgi roman üzerinde eğitilmiş 4 sınıflı (bubble, panel, sfx, text) piksel segmentasyon yapay zekası. 1024px dinamik dikdörtgen çözünürlükle düzensiz, kardan adam veya eğri balon sınırlarını kontur profiliyle çıkarır."
    );

    companion object {
        fun fromId(id: String?): DetectorModel {
            if (id.equals("yolo11n_seg", ignoreCase = true)) return YOLO11S_SEG
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: YOLO11S_SEG
        }
    }
}

class YoloBubbleDetector(context: Context) {
    // The detector can outlive the screen that created it; never retain an Activity context.
    private val context: Context = context.applicationContext

    data class YoloDetection(
        val classId: Int,
        val label: String,
        val confidence: Float,
        val box: RectF, // Normalize edilmiş koordinatlar (0.0f .. 1.0f)
        val contourProfile: ContourScanlineProfile? = null
    )

    data class DetectionResult(
        val detections: List<BubbleBox>,
        val error: String?
    )

    data class DetectionWithPanelsResult(
        val detections: List<BubbleBox>,
        val panels: List<RectF>,
        val error: String?
    )

    internal data class SegmentedDetectionsWithPanels(
        val detections: List<YoloDetection>,
        val panels: List<YoloDetection> = emptyList()
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

    internal data class LetterboxTransform(
        val origW: Int,
        val origH: Int,
        val inputW: Int,
        val inputH: Int,
        val gain: Float,
        val contentW: Int,
        val contentH: Int,
        val padLeft: Int,
        val padTop: Int
    )

    companion object {
        private const val TAG = "BabelRead_Detector"
        const val MODEL_FILENAME = "detector.onnx"
        const val EMBEDDED_ASSET_PATH = "models/detector.onnx"
        const val LEGACY_MODEL_FILENAME = "koharu-yolo26s.onnx"
        const val DEFAULT_INPUT_SIZE = 640
        const val DEFAULT_YOLO11S_INPUT_SIZE = 1024
        const val DEFAULT_MODEL_STRIDE = 32
        const val DEFAULT_CONF_THRESHOLD = 0.25f
        private const val LETTERBOX_FILL_VALUE = 114f / 255f
        private const val MIN_MODEL_FILE_BYTES = 10_000L
        private const val VERIFIED_BUNDLED_MODEL_SHA256 = "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79"

        val DEFAULT_CLASS_NAMES = listOf(
            "bubble",
            "text_bubble",
            "text_free"
        )

        val YOLO11S_SEG_CLASS_NAMES = listOf(
            "bubble",
            "panel",
            "sfx",
            "text"
        )

        private val PANEL_LABELS = setOf("panel", "panels", "frame")
        private val BUBBLE_LABELS = setOf("bubble", "balloon")
        private val TEXT_LABELS = setOf("text", "text_bubble", "dialogue_text")
        private val INVERTED_RESCUE_LABELS = setOf("bubble", "balloon", "text")
        private val PRIMARY_TEXT_SUPPRESS_LABELS = setOf("bubble", "balloon", "text", "text_bubble")
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
    var modelInputWidth = DEFAULT_YOLO11S_INPUT_SIZE
        private set

    @Volatile
    var modelInputHeight = DEFAULT_YOLO11S_INPUT_SIZE
        private set

    @Volatile
    var hasDynamicInputShape = false
        private set

    @Volatile
    var modelStride = DEFAULT_MODEL_STRIDE
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

    @Volatile
    var isYoloSegmentation = false
        private set

    @Volatile
    var activeModelType: DetectorModel = DetectorModel.YOLO11S_SEG
        private set

    fun switchModel(modelType: DetectorModel): Boolean = synchronized(sessionLock) {
        if (activeModelType == modelType && isSessionInitialized && ortSession != null) return true
        activeModelType = modelType
        closeSession()
        return initOnnxEngine()
    }

    private var cachedFloatArray: FloatArray? = null
    private var cachedPixelArray: IntArray? = null
    @Volatile private var auxRtDetrSession: OrtSession? = null
    @Volatile private var auxRtDetrAttempted = false
    @Volatile private var auxRtDetrClassNames: Map<Int, String> = emptyMap()
    @Volatile private var auxRtDetrOrigSizes = false
    @Volatile private var cachedModelHashKey: String? = null
    @Volatile private var cachedModelHash: String? = null
    @Volatile private var warnedMissingRtDetrLabels = false
    @Volatile private var warnedUnsupportedInputType = false

    private fun createOptimizedSessionOptions(): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            // Avoid XNNPACK EP and memory-pattern caching on dynamic-shape
            // YOLO/PP-OCRv5/RT-DETR graphs (XNNPACK pthreadpool crashes on
            // dynamic Resize ops in onnxruntime-android 1.20.0 on Android 16).
            setIntraOpNumThreads(2)
        }
    }

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

    internal fun parseImgszFromMetadata(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        val nums = Regex("""\d+""").findAll(raw)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it > 0 }
            .toList()
        return when {
            nums.size >= 2 -> nums[0] to nums[1]
            nums.size == 1 -> nums[0] to nums[0]
            else -> null
        }
    }

    internal fun parseStrideFromMetadata(raw: String?): Int {
        if (raw.isNullOrBlank()) return DEFAULT_MODEL_STRIDE
        return Regex("""\d+""").find(raw)?.value?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_MODEL_STRIDE
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
                    val boxIou = iou(best.box, candidate.box)
                    val shouldSuppress = when {
                        best.label in BUBBLE_LABELS || best.label == "text" || best.label == "text_bubble" -> {
                            if (boxIou > 0.65f) {
                                true
                            } else if (boxIou >= 0.35f) {
                                val dx = abs(best.box.centerX() - candidate.box.centerX())
                                val dy = abs(best.box.centerY() - candidate.box.centerY())
                                val maxW = max(best.box.width(), candidate.box.width())
                                val maxH = max(best.box.height(), candidate.box.height())
                                dx < 0.28f * maxW && dy < 0.28f * maxH
                            } else {
                                false
                            }
                        }
                        best.label in PANEL_LABELS -> boxIou > 0.65f
                        else -> boxIou > iouThreshold
                    }
                    if (shouldSuppress) it.remove()
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
                    ),
                    contourProfile = null
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
        val minOverlap = (overlap / 2).coerceAtLeast(1)
        if (starts.size >= 3 && last - starts[starts.size - 3] <= tileSize - minOverlap) {
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
        if (activeModelType == DetectorModel.YOLO11S_SEG && (verifiedBundledClassMap || dynamicClassNames.isEmpty())) {
            return YOLO11S_SEG_CLASS_NAMES.getOrNull(classId) ?: "unknown"
        }
        if (!verifiedBundledClassMap) return "unknown"
        return DEFAULT_CLASS_NAMES.getOrNull(classId) ?: "unknown"
    }

    private fun effectiveClassCount(headClassCount: Int? = null): Int {
        val metadataClassCount = dynamicClassNames.keys.maxOrNull()?.plus(1)
        val defaultClassCount = if (activeModelType == DetectorModel.YOLO11S_SEG) {
            YOLO11S_SEG_CLASS_NAMES.size
        } else {
            DEFAULT_CLASS_NAMES.size
        }
        return headClassCount?.takeIf { it > 0 }
            ?: metadataClassCount?.takeIf { it > 0 }
            ?: defaultClassCount
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
        hasDynamicInputShape = false
        modelStride = DEFAULT_MODEL_STRIDE
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
                val session = createOptimizedSessionOptions().use { sessionOptions ->
                    ortEnv!!.createSession(modelFile.absolutePath, sessionOptions)
                }
                ortSession = session
                verifiedBundledClassMap = runCatching { sha256(modelFile) == activeModelType.sha256 }.getOrDefault(false)

                // 1. Giriş boyutunu ve isimlerini session.inputInfo üzerinden oku
                var inputW = DEFAULT_INPUT_SIZE
                var inputH = DEFAULT_INPUT_SIZE
                var hasOrigSizes = false
                var dynamicInput = false
                try {
                    val inputInfo = session.inputInfo
                    for ((name, nodeInfo) in inputInfo) {
                        if (isOriginalTargetSizesInput(name)) {
                            hasOrigSizes = true
                            continue
                        }
                        val tensorInfo = nodeInfo?.info as? TensorInfo
                        val tensorShape = tensorInfo?.shape
                        if (tensorShape != null && tensorShape.size == 4) {
                            val h = tensorShape[2].toInt()
                            val w = tensorShape[3].toInt()
                            if (h > 0 && w > 0) {
                                inputH = h
                                inputW = w
                            } else {
                                dynamicInput = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Giriş boyutu okunamadı, varsayılan $DEFAULT_INPUT_SIZE: ${e.message}")
                }

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

                val protoOutputName = findOutputName(outNames, setOf("output1", "proto", "prototypes", "1"))
                val detOutputName = findOutputName(outNames, setOf("output0", "detections", "pred", "0"))
                isYoloSegmentation = detOutputName != null && protoOutputName != null

                // 3. Sınıf adlarını ve dinamik imgsz / stride metadata'sını ONNX'ten oku
                val parsedClasses = mutableMapOf<Int, String>()
                var parsedStride = DEFAULT_MODEL_STRIDE
                var parsedImgsz: Pair<Int, Int>? = null
                try {
                    val customMeta = session.metadata.customMetadata
                    val namesRaw = customMeta["names"]
                    if (!namesRaw.isNullOrBlank()) {
                        parsedClasses.putAll(parseNamesFromMetadata(namesRaw))
                    }
                    parsedStride = parseStrideFromMetadata(customMeta["stride"])
                    parsedImgsz = parseImgszFromMetadata(customMeta["imgsz"])
                } catch (_: Exception) {}
                dynamicClassNames = parsedClasses

                if (dynamicInput) {
                    if (parsedImgsz != null) {
                        inputH = parsedImgsz.first
                        inputW = parsedImgsz.second
                    } else if (activeModelType == DetectorModel.YOLO11S_SEG || isYoloSegmentation) {
                        inputH = DEFAULT_YOLO11S_INPUT_SIZE
                        inputW = DEFAULT_YOLO11S_INPUT_SIZE
                    }
                }
                hasDynamicInputShape = dynamicInput
                modelStride = parsedStride
                modelInputWidth = inputW
                modelInputHeight = inputH
                hasOrigTargetSizesInput = hasOrigSizes

                isSessionInitialized = true
                lastInitializationError = null
                Log.i(TAG, "ONNX Dedektör başarıyla yüklendi: ${modelFile.name} (${modelFile.length() / 1024} KB), Boyut: ${inputW}x${inputH}, Dinamik: $dynamicInput, Stride: $parsedStride, RT-DETR: $isRtDetrMultiOutput, OrigSizes: $hasOrigSizes")
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

    fun getModelFile(modelType: DetectorModel = activeModelType): File? = synchronized(modelFileLock) {
        val internalDir = File(context.filesDir, "models")
        if (!internalDir.exists() && !internalDir.mkdirs()) return@synchronized null
        val internalFile = File(internalDir, modelType.targetFileName)
        val isCustom = File(internalDir, "${modelType.targetFileName}.imported").exists()
        // Eşleşen boyuttaki gömülü model veya kullanıcı tarafından yüklenmiş model varsa doğrudan kullan
        if (internalFile.exists() && (internalFile.length() == modelType.expectedBytes || (isCustom && internalFile.length() > MIN_MODEL_FILE_BYTES))) {
            return@synchronized internalFile
        }

        val tempFile = File(internalDir, "${modelType.targetFileName}.tmp")
        try {
            val stream = try {
                context.assets.open(modelType.assetPath)
            } catch (_: Throwable) {
                try {
                    context.assets.open(modelType.targetFileName)
                } catch (_: Throwable) {
                    try {
                        context.assets.open(EMBEDDED_ASSET_PATH)
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
                if (tempFile.exists() && tempFile.length() == modelType.expectedBytes) {
                    if (internalFile.exists()) internalFile.delete()
                    if (tempFile.renameTo(internalFile)) {
                        invalidateModelHashCache()
                        return@synchronized internalFile
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gömülü model (${modelType.id}) kopyalama hatası: ${e.message}")
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
        val tempFile = File(internalDir, "${activeModelType.targetFileName}.import.tmp")
        val targetFile = File(internalDir, activeModelType.targetFileName)
        val backupFile = File(internalDir, "${activeModelType.targetFileName}.backup")
        val markerFile = File(internalDir, "${activeModelType.targetFileName}.imported")

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
                markerFile.createNewFile()
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
                    markerFile.delete()
                    if (backupFile.exists()) backupFile.renameTo(targetFile)
                }
                invalidateModelHashCache()
                initOnnxEngine()
                Pair(false, importError)
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) {
                if (tempFile.exists()) tempFile.delete()
                throw e
            }
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            val msg = e.localizedMessage ?: e.message ?: e.toString()
            lastInitializationError = msg
            Pair(false, msg)
        }
    }

    fun closeSession() {
        synchronized(sessionLock) {
            verifiedBundledClassMap = false
            dynamicClassNames = emptyMap()
            hasDynamicInputShape = false
            modelStride = DEFAULT_MODEL_STRIDE
            cachedFloatArray = null
            cachedPixelArray = null
            auxRtDetrAttempted = false
            auxRtDetrClassNames = emptyMap()
            auxRtDetrOrigSizes = false
            try {
                auxRtDetrSession?.close()
            } catch (_: Exception) {}
            auxRtDetrSession = null
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
        val modelFile = getModelFile(activeModelType)
        val loaded = isModelLoaded()
        val classLabels = if (dynamicClassNames.isNotEmpty()) {
            dynamicClassNames.toSortedMap().values.joinToString(", ")
        } else {
            when {
                isRtDetrMultiOutput -> "bubble (0), text_bubble (1), text_free (2)"
                activeModelType == DetectorModel.YOLO11S_SEG -> "bubble (0), panel (1), sfx (2), text (3)"
                else -> "balloon (0)"
            }
        }
        val architectureName = when {
            isYoloSegmentation -> "${activeModelType.title} Instance Segmentation (ProtoNet 32-mask)"
            isRtDetrMultiOutput -> "RT-DETR-v2 End-to-End Transformer"
            else -> "ONNX Dedektör"
        }
        val inputSizeDesc = if (hasDynamicInputShape) {
            "${modelInputWidth}x${modelInputHeight} (Dinamik Rect)"
        } else {
            "${modelInputWidth}x${modelInputHeight}"
        }
        return mapOf(
            "engine" to "Microsoft ONNX Runtime Android",
            "model_type" to activeModelType.id,
            "model_title" to activeModelType.title,
            "model_subtitle" to activeModelType.subtitle,
            "model_name" to (modelFile?.name ?: activeModelType.targetFileName),
            "status" to if (loaded) "Aktif & Yüklendi (${activeModelType.title})" else if (lastInitializationError != null) "Yükleme Hatası" else "Model Bekleniyor",
            "target_path" to (modelFile?.absolutePath ?: "${context.filesDir.absolutePath}/models/${activeModelType.targetFileName}"),
            "file_size" to if (modelFile?.exists() == true) "${modelFile.length()} bayt" else "Mevcut Değil",
            "input_size" to inputSizeDesc,
            "architecture" to architectureName,
            "last_error" to (lastInitializationError ?: "Hata Yok"),
            "classes" to classLabels,
            "description" to activeModelType.description
        )
    }

    suspend fun detectSpeechBubbles(
        bitmap: Bitmap?,
        confidenceThreshold: Float = DEFAULT_CONF_THRESHOLD
    ): List<BubbleBox> = detectSpeechBubblesWithPanelsResult(bitmap, confidenceThreshold).detections

    /** Returns a per-call error value, so concurrent callers don't need the shared status field. */
    suspend fun detectSpeechBubblesWithResult(
        bitmap: Bitmap?,
        confidenceThreshold: Float = DEFAULT_CONF_THRESHOLD
    ): DetectionResult {
        val full = detectSpeechBubblesWithPanelsResult(bitmap, confidenceThreshold)
        return DetectionResult(full.detections, full.error)
    }

    suspend fun detectSpeechBubblesWithPanelsResult(
        bitmap: Bitmap?,
        confidenceThreshold: Float = DEFAULT_CONF_THRESHOLD
    ): DetectionWithPanelsResult = withContext(Dispatchers.Default) {
        detectionMutex.lock()
        try {
            lastInferenceError = null
            detectSpeechBubblesWithPanelsLocked(bitmap, confidenceThreshold)
        } finally {
            detectionMutex.unlock()
        }
    }

    private suspend fun detectSpeechBubblesWithPanelsLocked(
        bitmap: Bitmap?,
        confidenceThreshold: Float
    ): DetectionWithPanelsResult {
        lastInferenceError = null
        if (bitmap == null || bitmap.isRecycled || bitmap.width < 30 || bitmap.height < 30) {
            lastInferenceError = "Geçersiz veya çok küçük görüntü."
            return DetectionWithPanelsResult(emptyList(), emptyList(), lastInferenceError)
        }
        if (!ensureSessionReady()) {
            lastInferenceError = lastInitializationError ?: "ONNX modeli başlatılamadı."
            return DetectionWithPanelsResult(emptyList(), emptyList(), lastInferenceError)
        }

        val origW = bitmap.width
        val origH = bitmap.height

        // Avoid shrinking high-resolution spreads and near-square pages to one
        // input. Overlapped square crops preserve small text and bubbles.
        val inputLongSide = max(modelInputWidth, modelInputHeight)
        val allowInvertedPass = isYoloSegmentation
        val invertedConfThreshold = max(0.18f, confidenceThreshold - 0.07f)
        var ranInvertedPass = false
        val pass1Detections: List<YoloDetection>
        val pass2Detections: List<YoloDetection>
        val panelDetections: List<YoloDetection>

        if (origH <= origW * 2.2f && max(origW, origH) >= inputLongSide * 2.5f) {
            val sourceTileSide = min(min(origW, origH), inputLongSide * 2)
            val overlap = (sourceTileSide * 0.25f).toInt().coerceAtLeast(1)
            val xStarts = tileStarts(origW, sourceTileSide, overlap)
            val yStarts = tileStarts(origH, sourceTileSide, overlap)
            val pass1Tiled = mutableListOf<TileDetection>()
            val pass2Tiled = mutableListOf<TileDetection>()
            val panelTiled = mutableListOf<TileDetection>()
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
                        fun toTileDetection(det: YoloDetection) = TileDetection(
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

                        val pass1 = runOnnxInferenceWithPanels(tile, confidenceThreshold, invertColors = false)
                        for (det in pass1.detections) {
                            pass1Tiled += toTileDetection(det)
                        }
                        for (panel in pass1.panels) {
                            panelTiled += toTileDetection(panel)
                        }
                        if (allowInvertedPass && shouldRunInvertedYoloPass(tile)) {
                            ranInvertedPass = true
                            val pass2 = runOnnxInferenceWithPanels(tile, invertedConfThreshold, invertColors = true)
                            for (det in pass2.detections) {
                                pass2Tiled += toTileDetection(det)
                            }
                        }
                    } finally {
                        if (tile !== bitmap && !tile.isRecycled) tile.recycle()
                    }
                }
            }
            pass1Detections = nmsDetections(mergeTileSeamDetections(pass1Tiled))
            pass2Detections = if (pass2Tiled.isNotEmpty()) nmsDetections(mergeTileSeamDetections(pass2Tiled)) else emptyList()
            panelDetections = if (panelTiled.isNotEmpty()) nmsDetections(mergeTileSeamDetections(panelTiled)) else emptyList()
        } else if (origH > origW * 2.2f && origH > 2400) {
            // Çok uzun Webtoon sayfalarında dikey parçalama (tiling)
            val tileH = (origW * 1.8f).toInt().coerceIn(1200, 2400)
            val overlap = (origW * 0.25f).toInt().coerceIn(150, 400)
            val pass1Tiled = mutableListOf<TileDetection>()
            val pass2Tiled = mutableListOf<TileDetection>()
            val panelTiled = mutableListOf<TileDetection>()
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
                    try {
                        fun toVerticalTileDetection(det: YoloDetection): TileDetection {
                            val normL = det.box.left
                            val normR = det.box.right
                            val normT = (det.box.top * h + y) / origH.toFloat()
                            val normB = (det.box.bottom * h + y) / origH.toFloat()
                            return TileDetection(
                                detection = det.copy(box = RectF(normL, normT, normR, normB)),
                                tileRow = tileRow,
                                tileColumn = 0,
                                touchesTop = det.box.top <= 0.015f,
                                touchesBottom = det.box.bottom >= 0.985f,
                                touchesLeft = false,
                                touchesRight = false
                            )
                        }

                        val pass1 = runOnnxInferenceWithPanels(tile, confidenceThreshold, invertColors = false)
                        for (det in pass1.detections) {
                            pass1Tiled.add(toVerticalTileDetection(det))
                        }
                        for (panel in pass1.panels) {
                            panelTiled.add(toVerticalTileDetection(panel))
                        }
                        if (allowInvertedPass && shouldRunInvertedYoloPass(tile)) {
                            ranInvertedPass = true
                            val pass2 = runOnnxInferenceWithPanels(tile, invertedConfThreshold, invertColors = true)
                            for (det in pass2.detections) {
                                pass2Tiled.add(toVerticalTileDetection(det))
                            }
                        }
                    } finally {
                        if (tile !== bitmap && !tile.isRecycled) tile.recycle()
                    }
                }
                tileRow++
                if (y + h >= origH) break
                y += (tileH - overlap)
            }
            pass1Detections = nmsDetections(mergeTileSeamDetections(pass1Tiled))
            pass2Detections = if (pass2Tiled.isNotEmpty()) nmsDetections(mergeTileSeamDetections(pass2Tiled)) else emptyList()
            panelDetections = if (panelTiled.isNotEmpty()) nmsDetections(mergeTileSeamDetections(panelTiled)) else emptyList()
        } else {
            val pass1 = runOnnxInferenceWithPanels(bitmap, confidenceThreshold, invertColors = false)
            pass1Detections = pass1.detections
            panelDetections = pass1.panels
            pass2Detections = if (allowInvertedPass && shouldRunInvertedYoloPass(bitmap)) {
                ranInvertedPass = true
                runOnnxInferenceWithPanels(bitmap, invertedConfThreshold, invertColors = true).detections
            } else {
                emptyList()
            }
        }

        val darkBubbles = if (pass2Detections.isNotEmpty()) {
            filterInvertedBubbleDetections(bitmap, pass2Detections, pass1Detections)
        } else {
            emptyList()
        }
        val initialCombined = if (darkBubbles.isEmpty()) pass1Detections else pass1Detections + darkBubbles
        val (afterPanelRescue, remainingPanels) = rescueTextBearingPanels(bitmap, initialCombined, panelDetections)

        var finalDetections = afterPanelRescue
        if (activeModelType == DetectorModel.YOLO11S_SEG && isYoloSegmentation) {
            val zeroBubbles = finalDetections.none { it.label in BUBBLE_LABELS }
            val hasOrphanText = hasOrphanTextDetection(finalDetections)
            if (ranInvertedPass || zeroBubbles || hasOrphanText) {
                val rtConf = max(confidenceThreshold, DetectorModel.RTDETR_V2.defaultConfidence)
                val rtDetections = runAuxRtDetrDetection(bitmap, rtConf, inputLongSide)
                if (rtDetections.isNotEmpty()) {
                    finalDetections = fuseDetectionsWbf(finalDetections, rtDetections)
                }
            }
        }

        finalDetections = filterFalsePositiveSfxDetections(bitmap, finalDetections, remainingPanels)

        val bubbleBoxes = finalDetections.mapIndexed { index, det ->
            BubbleBox(
                id = index + 1,
                bounds = det.box,
                confidence = det.confidence,
                label = det.label,
                contourProfile = det.contourProfile
            )
        }
        return DetectionWithPanelsResult(
            detections = bubbleBoxes,
            panels = remainingPanels.map { it.box },
            error = lastInferenceError
        )
    }

    internal fun filterFalsePositiveSfxDetections(
        bitmap: Bitmap,
        detections: List<YoloDetection>,
        panels: List<YoloDetection>
    ): List<YoloDetection> {
        if (detections.isEmpty() || bitmap.isRecycled || bitmap.width < 8 || bitmap.height < 8) {
            return detections
        }
        val bubbles = detections.filter { it.label in BUBBLE_LABELS }
        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()
        return detections.filter { det ->
            if (det.label !in setOf("sfx", "text_free")) return@filter true
            val pxW = det.box.width() * bmpW
            val pxH = det.box.height() * bmpH
            if (pxW < 8f || pxH < 8f) return@filter false
            val aspect = max(pxW / pxH, pxH / pxW)
            if (aspect > 12f) return@filter false

            val sfxArea = det.box.width() * det.box.height()
            if (sfxArea <= 0f) return@filter false

            val outsidePanels = panels.isNotEmpty() && panels.none { p ->
                val interW = max(0f, min(det.box.right, p.box.right) - max(det.box.left, p.box.left))
                val interH = max(0f, min(det.box.bottom, p.box.bottom) - max(det.box.top, p.box.top))
                (interW * interH) / sfxArea >= 0.30f
            }
            if (outsidePanels && aspect >= 4.0f) return@filter false

            val grazesBubbleSlightly = bubbles.any { b ->
                val interW = max(0f, min(det.box.right, b.box.right) - max(det.box.left, b.box.left))
                val interH = max(0f, min(det.box.bottom, b.box.bottom) - max(det.box.top, b.box.top))
                val interArea = interW * interH
                if (interArea <= 0f) {
                    false
                } else {
                    val bArea = b.box.width() * b.box.height()
                    val smaller = min(sfxArea, bArea)
                    smaller > 0f && (interArea / smaller) < 0.25f
                }
            }
            if (grazesBubbleSlightly && (outsidePanels || aspect >= 4.0f)) return@filter false

            hasTextLikeContrastEdges(bitmap, det.box)
        }
    }

    private fun hasTextLikeContrastEdges(bitmap: Bitmap, normBox: RectF): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val pxL = (normBox.left * w).toInt().coerceIn(0, w - 1)
        val pxT = (normBox.top * h).toInt().coerceIn(0, h - 1)
        val pxR = (normBox.right * w).toInt().coerceIn(pxL + 1, w)
        val pxB = (normBox.bottom * h).toInt().coerceIn(pxT + 1, h)
        val boxW = pxR - pxL
        val boxH = pxB - pxT
        if (boxW < 6 || boxH < 6) return false

        val insetX = (boxW * 0.08f).toInt().coerceIn(1, max(1, boxW / 4))
        val insetY = (boxH * 0.08f).toInt().coerceIn(1, max(1, boxH / 4))
        val sL = (pxL + insetX).coerceIn(0, w - 1)
        val sR = (pxR - insetX).coerceIn(sL, w - 1)
        val sT = (pxT + insetY).coerceIn(0, h - 1)
        val sB = (pxB - insetY).coerceIn(sT, h - 1)

        val stepX = max(1, (sR - sL) / 20)
        val stepY = max(1, (sB - sT) / 20)
        val cols = ((sR - sL) / stepX) + 1
        val prevRowLuma = FloatArray(cols) { -1f }

        var minLuma = 255f
        var maxLuma = 0f
        var horizEdges = 0
        var vertEdges = 0

        var y = sT
        while (y <= sB) {
            var prevLuma = -1f
            var colIdx = 0
            var x = sL
            while (x <= sR && colIdx < cols) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = ((pixel ushr 24) and 0xFF) / 255f
                val r = ((pixel shr 16) and 0xFF) * alpha + 255f * (1f - alpha)
                val g = ((pixel shr 8) and 0xFF) * alpha + 255f * (1f - alpha)
                val b = (pixel and 0xFF) * alpha + 255f * (1f - alpha)
                val luma = r * 0.299f + g * 0.587f + b * 0.114f
                if (luma < minLuma) minLuma = luma
                if (luma > maxLuma) maxLuma = luma

                if (prevLuma >= 0f && abs(luma - prevLuma) >= 35f) horizEdges++
                if (prevRowLuma[colIdx] >= 0f && abs(luma - prevRowLuma[colIdx]) >= 35f) vertEdges++
                prevLuma = luma
                prevRowLuma[colIdx] = luma

                colIdx++
                x += stepX
            }
            y += stepY
        }

        return (maxLuma - minLuma) >= 45f && horizEdges >= 2 && vertEdges >= 2
    }

    internal fun rescueTextBearingPanels(
        bitmap: Bitmap,
        detections: List<YoloDetection>,
        panels: List<YoloDetection>
    ): Pair<List<YoloDetection>, List<YoloDetection>> {
        if (panels.isEmpty() || bitmap.isRecycled || bitmap.width < 8 || bitmap.height < 8) {
            return detections to panels
        }
        val existingBubbles = detections.filter { it.label in BUBBLE_LABELS }
        val textDetections = detections.filter { it.label in TEXT_LABELS }
        val promoted = mutableListOf<YoloDetection>()
        val remaining = mutableListOf<YoloDetection>()

        for (panel in panels) {
            val pBox = panel.box
            val panelArea = pBox.width() * pBox.height()
            val smallEnough = panelArea in 0.0004f..0.179999f && pBox.width() < 0.65f && pBox.height() < 0.65f
            if (!smallEnough) {
                remaining.add(panel)
                continue
            }

            val overlapsBubble = (existingBubbles + promoted).any { b ->
                iou(pBox, b.box) >= 0.45f || intersectionOverSmallerArea(pBox, b.box) >= 0.45f
            }
            if (overlapsBubble) {
                remaining.add(panel)
                continue
            }

            val coveredTexts = textDetections.filter { t ->
                val tArea = t.box.width() * t.box.height()
                if (tArea <= 0f) return@filter false
                val interW = max(0f, min(pBox.right, t.box.right) - max(pBox.left, t.box.left))
                val interH = max(0f, min(pBox.bottom, t.box.bottom) - max(pBox.top, t.box.top))
                (interW * interH) / tArea >= 0.75f
            }
            if (coveredTexts.isEmpty()) {
                remaining.add(panel)
                continue
            }

            if (hasUniformBubbleInteriorAroundTexts(bitmap, pBox, coveredTexts.map { it.box })) {
                promoted.add(
                    panel.copy(
                        classId = 0,
                        label = "bubble"
                    )
                )
            } else {
                remaining.add(panel)
            }
        }

        val combined = if (promoted.isEmpty()) detections else detections + promoted
        return combined to remaining
    }

    private fun hasUniformBubbleInteriorAroundTexts(
        bitmap: Bitmap,
        panelNorm: RectF,
        textNormBoxes: List<RectF>
    ): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val pxL = (panelNorm.left * w).toInt().coerceIn(0, w - 1)
        val pxT = (panelNorm.top * h).toInt().coerceIn(0, h - 1)
        val pxR = (panelNorm.right * w).toInt().coerceIn(pxL + 1, w)
        val pxB = (panelNorm.bottom * h).toInt().coerceIn(pxT + 1, h)
        val boxW = pxR - pxL
        val boxH = pxB - pxT
        if (boxW < 8 || boxH < 8) return false

        val insetX = (boxW * 0.06f).toInt().coerceIn(1, max(1, boxW / 4))
        val insetY = (boxH * 0.06f).toInt().coerceIn(1, max(1, boxH / 4))
        val sL = (pxL + insetX).coerceIn(0, w - 1)
        val sR = (pxR - insetX).coerceIn(sL, w - 1)
        val sT = (pxT + insetY).coerceIn(0, h - 1)
        val sB = (pxB - insetY).coerceIn(sT, h - 1)

        val textPixelRects = textNormBoxes.map { tb ->
            intArrayOf(
                (tb.left * w).toInt(),
                (tb.top * h).toInt(),
                (tb.right * w).toInt(),
                (tb.bottom * h).toInt()
            )
        }

        fun sampleNonText(stepX: Int, stepY: Int): Triple<Int, Int, IntArray> {
            val lums = ArrayList<Float>(512)
            val rs = ArrayList<Float>(512)
            val gs = ArrayList<Float>(512)
            val bs = ArrayList<Float>(512)
            var whiteCount = 0
            var darkCount = 0
            var y = sT
            while (y <= sB) {
                var x = sL
                while (x <= sR) {
                    val insideText = textPixelRects.any { r -> x in r[0]..r[2] && y in r[1]..r[3] }
                    if (!insideText) {
                        val pixel = bitmap.getPixel(x, y)
                        val alpha = ((pixel ushr 24) and 0xFF) / 255f
                        val r = ((pixel shr 16) and 0xFF) * alpha + 255f * (1f - alpha)
                        val g = ((pixel shr 8) and 0xFF) * alpha + 255f * (1f - alpha)
                        val b = (pixel and 0xFF) * alpha + 255f * (1f - alpha)
                        val luma = r * 0.299f + g * 0.587f + b * 0.114f
                        lums.add(luma)
                        rs.add(r)
                        gs.add(g)
                        bs.add(b)
                        if (luma >= 210f) whiteCount++
                        if (luma < 80f) darkCount++
                    }
                    x += stepX
                }
                y += stepY
            }
            val n = lums.size
            if (n < 8) return Triple(n, 0, intArrayOf(0))
            if (whiteCount.toFloat() / n >= 0.62f || darkCount.toFloat() / n >= 0.62f) {
                return Triple(n, 1, intArrayOf(1))
            }
            val rsSorted = rs.sorted()
            val gsSorted = gs.sorted()
            val bsSorted = bs.sorted()
            val medR = rsSorted[n / 2]
            val medG = gsSorted[n / 2]
            val medB = bsSorted[n / 2]
            var flatCount = 0
            for (i in 0 until n) {
                if (abs(rs[i] - medR) + abs(gs[i] - medG) + abs(bs[i] - medB) <= 45f) {
                    flatCount++
                }
            }
            val passed = if (flatCount.toFloat() / n >= 0.62f) 1 else 0
            return Triple(n, passed, intArrayOf(passed))
        }

        val coarse = sampleNonText(max(1, (sR - sL) / 24), max(1, (sB - sT) / 24))
        if (coarse.first >= 12) return coarse.second == 1
        val fine = sampleNonText(max(1, (sR - sL) / 64), max(1, (sB - sT) / 64))
        return fine.first >= 8 && fine.second == 1
    }

    internal fun hasOrphanTextDetection(detections: List<YoloDetection>): Boolean {
        val bubbles = detections.filter { it.label in BUBBLE_LABELS }
        val texts = detections.filter { it.label in TEXT_LABELS }
        if (texts.isEmpty()) return false
        if (bubbles.isEmpty()) return true
        val hasUncoveredText = texts.any { t ->
            bubbles.none { b ->
                iou(t.box, b.box) >= 0.30f || intersectionOverSmallerArea(t.box, b.box) >= 0.50f
            }
        }
        if (hasUncoveredText) return true
        // Also trigger RT-DETR v2 when a single YOLO bubble merges 2+ distinct non-overlapping text blocks.
        return bubbles.any { b ->
            val insideTexts = texts.filter { t -> intersectionOverSmallerArea(t.box, b.box) >= 0.55f }
            if (insideTexts.size < 2) {
                false
            } else {
                var distinctPair = false
                for (i in insideTexts.indices) {
                    for (j in i + 1 until insideTexts.size) {
                        if (iou(insideTexts[i].box, insideTexts[j].box) < 0.25f) {
                            distinctPair = true
                            break
                        }
                    }
                    if (distinctPair) break
                }
                distinctPair
            }
        }
    }

    private fun remapContourProfileToSubBox(
        profile: ContourScanlineProfile?,
        parentBox: RectF,
        childBox: RectF
    ): ContourScanlineProfile? {
        if (profile == null || !profile.isUsable) return null
        val pW = parentBox.width()
        val pH = parentBox.height()
        val cW = childBox.width()
        val cH = childBox.height()
        if (pW <= 1e-4f || pH <= 1e-4f || cW <= 1e-4f || cH <= 1e-4f) return null
        val rows = profile.rowCount
        if (rows < 4) return null
        val outLeft = FloatArray(rows) { 1f }
        val outRight = FloatArray(rows) { 0f }
        var validRows = 0
        var sumX = 0f
        var sumY = 0f
        for (r in 0 until rows) {
            val subFrac = (r + 0.5f) / rows.toFloat()
            val cy = childBox.top + subFrac * cH
            val pyNorm = (cy - parentBox.top) / pH
            if (pyNorm < 0f || pyNorm > 1f) continue
            val pr = (pyNorm * rows).toInt().coerceIn(0, rows - 1)
            val pl = profile.rowLeftFractions[pr]
            val pRight = profile.rowRightFractions[pr]
            if (pRight <= pl) continue
            val absL = parentBox.left + pl * pW
            val absR = parentBox.left + pRight * pW
            val cl = ((absL - childBox.left) / cW).coerceIn(0f, 1f)
            val cr = ((absR - childBox.left) / cW).coerceIn(0f, 1f)
            if (cr - cl >= 0.12f) {
                outLeft[r] = cl
                outRight[r] = cr
                validRows++
                sumX += (cl + cr) * 0.5f
                sumY += subFrac
            }
        }
        return if (validRows >= 4) {
            ContourScanlineProfile(
                rowLeftFractions = outLeft,
                rowRightFractions = outRight,
                opticalCenterX = (sumX / validRows).coerceIn(0.15f, 0.85f),
                opticalCenterY = (sumY / validRows).coerceIn(0.15f, 0.85f)
            )
        } else {
            null
        }
    }

    internal fun fuseDetectionsWbf(
        yoloDetections: List<YoloDetection>,
        rtDetrDetections: List<YoloDetection>
    ): List<YoloDetection> {
        if (rtDetrDetections.isEmpty()) return yoloDetections
        val rawYoloBalloons = yoloDetections.filter { it.label in BUBBLE_LABELS }
        val yoloOthers = yoloDetections.filterNot { it.label in BUBBLE_LABELS }.toMutableList()
        val extraBalloons = mutableListOf<YoloDetection>()
        val extraSfx = mutableListOf<YoloDetection>()

        // Pre-pass: if a single YOLO bubble merges 2+ non-overlapping RT-DETR sub-bubbles,
        // replace the merged YOLO box with the clean RT-DETR sub-bubbles instead of averaging
        // one half with the merged parent.
        val rtOuterBalloons = rtDetrDetections
            .filter { it.label in BUBBLE_LABELS }
            .sortedByDescending { it.confidence }
        val consumedRtIndices = HashSet<Int>()
        val yoloBalloons = mutableListOf<YoloDetection>()

        for (yolo in rawYoloBalloons) {
            val yArea = (yolo.box.width() * yolo.box.height()).coerceAtLeast(1e-6f)
            val candidateSubIndices = mutableListOf<Int>()
            for (rIdx in rtOuterBalloons.indices) {
                if (rIdx in consumedRtIndices) continue
                val rt = rtOuterBalloons[rIdx]
                val rArea = (rt.box.width() * rt.box.height()).coerceAtLeast(1e-6f)
                if (rArea >= yArea * 0.85f) continue
                if (intersectionOverSmallerArea(rt.box, yolo.box) >= 0.60f) {
                    val overlapsChosen = candidateSubIndices.any { prevIdx ->
                        iou(rtOuterBalloons[prevIdx].box, rt.box) >= 0.25f
                    }
                    if (!overlapsChosen) {
                        candidateSubIndices.add(rIdx)
                    }
                }
            }
            if (candidateSubIndices.size >= 2) {
                for (rIdx in candidateSubIndices) {
                    consumedRtIndices.add(rIdx)
                    val rt = rtOuterBalloons[rIdx]
                    val slicedContour = remapContourProfileToSubBox(yolo.contourProfile, yolo.box, rt.box)
                    yoloBalloons.add(
                        rt.copy(
                            confidence = max(rt.confidence, yolo.confidence * 0.95f),
                            contourProfile = slicedContour
                        )
                    )
                }
            } else {
                yoloBalloons.add(yolo)
            }
        }

        val consumedRtSet = consumedRtIndices.map { rtOuterBalloons[it] }.toSet()

        // Match outer RT-DETR bubbles before inner text_bubble boxes
        val sortedRt = rtDetrDetections
            .filterNot { it in consumedRtSet }
            .sortedWith(
                compareBy<YoloDetection> {
                    when (it.label) {
                        in BUBBLE_LABELS -> 0
                        "text_bubble" -> 1
                        else -> 2
                    }
                }.thenByDescending { it.confidence }
            )

        for (rtDet in sortedRt) {
            when (rtDet.label) {
                in BUBBLE_LABELS, "text_bubble" -> {
                    var bestIdx = -1
                    var bestIou = 0f
                    for (idx in yoloBalloons.indices) {
                        val currentIou = iou(yoloBalloons[idx].box, rtDet.box)
                        if (currentIou >= 0.48f && currentIou > bestIou) {
                            bestIou = currentIou
                            bestIdx = idx
                        }
                    }
                    if (bestIdx >= 0) {
                        val yolo = yoloBalloons[bestIdx]
                        if (rtDet.label == "text_bubble") {
                            // İç metin kutusu (text_bubble) dış konuşma balonunu daraltmamalı;
                            // yalnızca güven skorunu güçlendir.
                            yoloBalloons[bestIdx] = yolo.copy(
                                confidence = max(yolo.confidence, rtDet.confidence)
                            )
                        } else {
                            val wY = yolo.confidence.coerceAtLeast(1e-4f)
                            val wR = rtDet.confidence.coerceAtLeast(1e-4f)
                            val wSum = wY + wR
                            val fusedBox = RectF(
                                ((yolo.box.left * wY + rtDet.box.left * wR) / wSum).coerceIn(0f, 1f),
                                ((yolo.box.top * wY + rtDet.box.top * wR) / wSum).coerceIn(0f, 1f),
                                ((yolo.box.right * wY + rtDet.box.right * wR) / wSum).coerceIn(0f, 1f),
                                ((yolo.box.bottom * wY + rtDet.box.bottom * wR) / wSum).coerceIn(0f, 1f)
                            )
                            val yW = yolo.box.width().coerceAtLeast(1e-4f)
                            val yH = yolo.box.height().coerceAtLeast(1e-4f)
                            val shifted = abs(fusedBox.left - yolo.box.left) / yW > 0.04f ||
                                abs(fusedBox.right - yolo.box.right) / yW > 0.04f ||
                                abs(fusedBox.top - yolo.box.top) / yH > 0.04f ||
                                abs(fusedBox.bottom - yolo.box.bottom) / yH > 0.04f
                            yoloBalloons[bestIdx] = yolo.copy(
                                confidence = max(yolo.confidence, rtDet.confidence),
                                box = fusedBox,
                                contourProfile = if (shifted) null else yolo.contourProfile
                            )
                        }
                    } else {
                        val coveredByExisting = (yoloBalloons + extraBalloons).any { existing ->
                            iou(existing.box, rtDet.box) >= 0.48f ||
                                (rtDet.label == "text_bubble" && intersectionOverSmallerArea(rtDet.box, existing.box) >= 0.70f)
                        }
                        if (!coveredByExisting) {
                            extraBalloons.add(rtDet.copy(contourProfile = null))
                        }
                    }
                }
                "text_free" -> {
                    val alreadySfx = (yoloOthers + extraSfx).any { existing ->
                        existing.label == "sfx" &&
                            (iou(existing.box, rtDet.box) >= 0.45f || intersectionOverSmallerArea(existing.box, rtDet.box) >= 0.65f)
                    }
                    if (!alreadySfx) {
                        extraSfx.add(rtDet.copy(label = "sfx", contourProfile = null))
                    }
                }
            }
        }

        return yoloBalloons + extraBalloons + yoloOthers + extraSfx
    }

    private suspend fun runOnnxInferenceWithPanels(
        source: Bitmap,
        confThreshold: Float,
        invertColors: Boolean = false
    ): SegmentedDetectionsWithPanels =
        inferenceMutex.withLock {
            synchronized(sessionLock) {
                runOnnxInferenceWithPanelsLocked(source, confThreshold, invertColors)
            }
        }

    private suspend fun runAuxRtDetrDetection(
        bitmap: Bitmap,
        confThreshold: Float,
        inputLongSide: Int
    ): List<YoloDetection> {
        val origW = bitmap.width
        val origH = bitmap.height
        return if (origH <= origW * 2.2f && max(origW, origH) >= inputLongSide * 2.5f) {
            val sourceTileSide = min(min(origW, origH), inputLongSide * 2)
            val overlap = (sourceTileSide * 0.25f).toInt().coerceAtLeast(1)
            val xStarts = tileStarts(origW, sourceTileSide, overlap)
            val yStarts = tileStarts(origH, sourceTileSide, overlap)
            val tiled = mutableListOf<TileDetection>()
            for ((row, tileY) in yStarts.withIndex()) {
                for ((column, tileX) in xStarts.withIndex()) {
                    val tileW = min(sourceTileSide, origW - tileX)
                    val tileH = min(sourceTileSide, origH - tileY)
                    val tile = try {
                        Bitmap.createBitmap(bitmap, tileX, tileY, tileW, tileH)
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    try {
                        val dets = inferenceMutex.withLock {
                            synchronized(sessionLock) {
                                runAuxRtDetrLocked(tile, confThreshold)
                            }
                        }
                        for (det in dets) {
                            tiled += TileDetection(
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
            nmsDetections(mergeTileSeamDetections(tiled))
        } else if (origH > origW * 2.2f && origH > 2400) {
            val tileH = (origW * 1.8f).toInt().coerceIn(1200, 2400)
            val overlap = (origW * 0.25f).toInt().coerceIn(150, 400)
            val tiled = mutableListOf<TileDetection>()
            var tileRow = 0
            var y = 0
            while (y < origH) {
                val remaining = origH - y
                if (y > 0 && remaining <= overlap) break
                if (y > 0 && remaining < tileH) y = (origH - tileH).coerceAtLeast(0)
                val h = min(tileH, origH - y)
                val tile = try {
                    Bitmap.createBitmap(bitmap, 0, y, origW, h)
                } catch (_: Exception) {
                    null
                }
                if (tile != null) {
                    try {
                        val dets = inferenceMutex.withLock {
                            synchronized(sessionLock) {
                                runAuxRtDetrLocked(tile, confThreshold)
                            }
                        }
                        for (det in dets) {
                            tiled.add(
                                TileDetection(
                                    detection = det.copy(
                                        box = RectF(
                                            det.box.left,
                                            (det.box.top * h + y) / origH.toFloat(),
                                            det.box.right,
                                            (det.box.bottom * h + y) / origH.toFloat()
                                        )
                                    ),
                                    tileRow = tileRow,
                                    tileColumn = 0,
                                    touchesTop = det.box.top <= 0.015f,
                                    touchesBottom = det.box.bottom >= 0.985f,
                                    touchesLeft = false,
                                    touchesRight = false
                                )
                            )
                        }
                    } finally {
                        if (tile !== bitmap && !tile.isRecycled) tile.recycle()
                    }
                }
                tileRow++
                if (y + h >= origH) break
                y += (tileH - overlap)
            }
            nmsDetections(mergeTileSeamDetections(tiled))
        } else {
            inferenceMutex.withLock {
                synchronized(sessionLock) {
                    runAuxRtDetrLocked(bitmap, confThreshold)
                }
            }
        }
    }

    private fun ensureAuxRtDetrSessionLocked(): OrtSession? {
        auxRtDetrSession?.let { return it }
        if (auxRtDetrAttempted) return null
        auxRtDetrAttempted = true
        return runCatching {
            val rtFile = getModelFile(DetectorModel.RTDETR_V2) ?: return null
            if (!rtFile.exists() || rtFile.length() <= MIN_MODEL_FILE_BYTES) return null
            if (ortEnv == null) ortEnv = OrtEnvironment.getEnvironment()
            val session = createOptimizedSessionOptions().use { options ->
                ortEnv!!.createSession(rtFile.absolutePath, options)
            }
            var origSizes = false
            for (name in session.inputNames) {
                if (isOriginalTargetSizesInput(name)) origSizes = true
            }
            val parsedClasses = mutableMapOf<Int, String>()
            runCatching {
                val namesRaw = session.metadata.customMetadata["names"]
                if (!namesRaw.isNullOrBlank()) parsedClasses.putAll(parseNamesFromMetadata(namesRaw))
            }
            auxRtDetrOrigSizes = origSizes
            auxRtDetrClassNames = parsedClasses
            auxRtDetrSession = session
            Log.i(TAG, "Yardımcı RT-DETR-v2 füzyon oturumu hazır: ${rtFile.name}")
            session
        }.getOrNull()
    }

    private fun runAuxRtDetrLocked(source: Bitmap, confThreshold: Float): List<YoloDetection> {
        val session = ensureAuxRtDetrSessionLocked() ?: return emptyList()
        val env = ortEnv ?: return emptyList()
        val inputW = DEFAULT_INPUT_SIZE
        val inputH = DEFAULT_INPUT_SIZE
        val origW = source.width.toFloat()
        val origH = source.height.toFloat()
        val scaleX = inputW / origW
        val scaleY = inputH / origH

        var scaledBmp: Bitmap? = null
        var inputTensor: OnnxTensor? = null
        var sizeTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null
        return runCatching {
            scaledBmp = Bitmap.createScaledBitmap(source, inputW, inputH, true)
            val numPixels = inputW * inputH
            val floatArray = getOrCreateFloatArray(inputW, inputH)
            val gOffset = numPixels
            val bOffset = 2 * numPixels
            val pixels = getOrCreatePixelArray(numPixels)
            scaledBmp!!.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)

            for (i in 0 until numPixels) {
                val pixel = pixels[i]
                val alpha = ((pixel ushr 24) and 0xFF) / 255.0f
                val r = (((pixel shr 16) and 0xFF) * alpha + 255f * (1f - alpha)) / 255f
                val g = (((pixel shr 8) and 0xFF) * alpha + 255f * (1f - alpha)) / 255f
                val b = ((pixel and 0xFF) * alpha + 255f * (1f - alpha)) / 255f
                floatArray[i] = r
                floatArray[gOffset + i] = g
                floatArray[bOffset + i] = b
            }

            val inputNames = session.inputNames.toList()
            val imageInputName = inputNames.firstOrNull {
                !isOriginalTargetSizesInput(it) && it.contains("image", ignoreCase = true)
            } ?: inputNames.firstOrNull { !isOriginalTargetSizesInput(it) } ?: return emptyList()

            inputTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(floatArray, 0, 3 * numPixels),
                longArrayOf(1, 3, inputH.toLong(), inputW.toLong())
            )
            val inputMap = mutableMapOf<String, OnnxTensor>(imageInputName to inputTensor!!)
            val origSizesInputName = inputNames.firstOrNull { isOriginalTargetSizesInput(it) }
            if (origSizesInputName != null) {
                val sizes = longArrayOf(origW.toLong(), origH.toLong())
                sizeTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(sizes), longArrayOf(1, 2))
                inputMap[origSizesInputName] = sizeTensor!!
            }

            results = session.run(inputMap)
            val labelResolver: (Int, Int) -> String = { classId, total ->
                if (classId < 0 || classId >= total) "unknown"
                else auxRtDetrClassNames[classId]?.lowercase() ?: DEFAULT_CLASS_NAMES.getOrNull(classId) ?: "unknown"
            }
            val effectiveCount = auxRtDetrClassNames.keys.maxOrNull()?.plus(1) ?: DEFAULT_CLASS_NAMES.size
            parseRtDetrMultiOutput(
                results = results!!,
                outputNames = session.outputNames,
                origSizesInputName = origSizesInputName,
                origW = origW,
                origH = origH,
                scaleX = scaleX,
                scaleY = scaleY,
                confThreshold = confThreshold,
                configuredClassMap = auxRtDetrClassNames,
                hasVerifiedClasses = true,
                effectiveClassCount = effectiveCount,
                resolveLabel = labelResolver,
                reportErrors = false
            ) ?: emptyList()
        }.getOrDefault(emptyList()).also {
            if (scaledBmp != null && scaledBmp !== source && !scaledBmp!!.isRecycled) {
                scaledBmp!!.recycle()
            }
            try { inputTensor?.close() } catch (_: Exception) {}
            try { sizeTensor?.close() } catch (_: Exception) {}
            try { results?.close() } catch (_: Exception) {}
        }
    }

    private fun runOnnxInferenceWithPanelsLocked(
        source: Bitmap,
        confThreshold: Float,
        invertColors: Boolean = false
    ): SegmentedDetectionsWithPanels {
        val session = ortSession ?: run {
            lastInferenceError = "ONNX oturumu hazır değil."
            return SegmentedDetectionsWithPanels(emptyList())
        }
        val env = ortEnv ?: run {
            lastInferenceError = "ONNX çalışma ortamı hazır değil."
            return SegmentedDetectionsWithPanels(emptyList())
        }

        val (inputW, inputH) = if (hasDynamicInputShape) {
            computeDynamicRectInputSize(
                origW = source.width,
                origH = source.height,
                maxLongSide = max(modelInputWidth, modelInputHeight),
                stride = modelStride
            )
        } else {
            modelInputWidth to modelInputHeight
        }
        val origW = source.width.toFloat()
        val origH = source.height.toFloat()

        // RT-DETR is trained on direct 640x640 resize; YOLO11-seg is
        // trained with Ultralytics Letterbox (padding color 114) and dynamic rect (1024, stride 32).
        val useLetterbox = isYoloSegmentation
        val letterbox = if (useLetterbox) {
            computeLetterboxTransform(source.width, source.height, inputW, inputH)
        } else {
            LetterboxTransform(
                origW = source.width,
                origH = source.height,
                inputW = inputW,
                inputH = inputH,
                gain = 1f,
                contentW = inputW,
                contentH = inputH,
                padLeft = 0,
                padTop = 0
            )
        }
        val scaleX = inputW / origW
        val scaleY = inputH / origH

        var scaledBmp: Bitmap? = null
        var inputTensor: OnnxTensor? = null
        var sizeTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null

        try {
            val targetW = letterbox.contentW
            val targetH = letterbox.contentH
            val padLeft = letterbox.padLeft
            val padTop = letterbox.padTop
            scaledBmp = Bitmap.createScaledBitmap(source, targetW, targetH, true)
            val numPixels = inputW * inputH
            val floatArray = getOrCreateFloatArray(inputW, inputH)
            if (useLetterbox && (padLeft > 0 || padTop > 0 || targetW < inputW || targetH < inputH)) {
                java.util.Arrays.fill(floatArray, 0, 3 * numPixels, LETTERBOX_FILL_VALUE)
            }

            val gOffset = numPixels
            val bOffset = 2 * numPixels
            val pixels = getOrCreatePixelArray(targetW * targetH)
            scaledBmp.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

            for (y in 0 until targetH) {
                val srcRowOffset = y * targetW
                val dstRowOffset = (padTop + y) * inputW + padLeft
                for (x in 0 until targetW) {
                    val pixel = pixels[srcRowOffset + x]
                    val targetIdx = dstRowOffset + x
                    val alpha = ((pixel ushr 24) and 0xFF) / 255.0f
                    // Composite transparent pixels over white paper instead of treating
                    // their hidden RGB values as visible black ink.
                    val rawR = ((pixel shr 16) and 0xFF) * alpha + 255f * (1f - alpha)
                    val rawG = ((pixel shr 8) and 0xFF) * alpha + 255f * (1f - alpha)
                    val rawB = (pixel and 0xFF) * alpha + 255f * (1f - alpha)
                    val r = (if (invertColors) 255f - rawR else rawR) / 255f
                    val g = (if (invertColors) 255f - rawG else rawG) / 255f
                    val b = (if (invertColors) 255f - rawB else rawB) / 255f
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
                    return SegmentedDetectionsWithPanels(emptyList())
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
                return SegmentedDetectionsWithPanels(emptyList())
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
            if (isRtDetrMultiOutput) {
                val rtDets = parseRtDetrMultiOutput(
                    results = results,
                    outputNames = outputNames,
                    origSizesInputName = origSizesInputName,
                    origW = origW,
                    origH = origH,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    confThreshold = confThreshold,
                    configuredClassMap = dynamicClassNames,
                    hasVerifiedClasses = verifiedBundledClassMap,
                    effectiveClassCount = effectiveClassCount(),
                    resolveLabel = { classId, total -> getLabelForClass(classId, total) },
                    reportErrors = true
                )
                return SegmentedDetectionsWithPanels(rtDets ?: emptyList())
            }

            // --- 2. Biçim: YOLO Instance Segmentation (output0: detections, output1: proto masks) ---
            val protoOutputName = findOutputName(outputNames, setOf("output1", "proto", "prototypes", "1"))
            val detOutputName = findOutputName(outputNames, setOf("output0", "detections", "pred", "0"))
            if (protoOutputName != null && detOutputName != null) {
                return parseYoloSegmentationOutput(
                    results = results,
                    detOutputName = detOutputName,
                    protoOutputName = protoOutputName,
                    inputW = inputW,
                    inputH = inputH,
                    letterbox = letterbox,
                    confThreshold = confThreshold
                )
            }

            // An unrecognized multi-output graph must not be parsed as a single YOLO tensor.
            if (outputNames.size > 1) {
                lastInferenceError = "ONNX modelinin çoklu çıkış biçimi desteklenmiyor: $outputNames"
                Log.w(TAG, "Bilinmeyen çoklu çıkış biçimi, hatalı ayrıştırma önlendi: $outputNames")
                return SegmentedDetectionsWithPanels(emptyList())
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
                return SegmentedDetectionsWithPanels(emptyList())
            }

            val shape = outputTensor.info.shape
            if (shape.size != 3) {
                lastInferenceError = "Desteklenmeyen model çıkış boyutu: ${shape.contentToString()}"
                Log.w(TAG, "Desteklenmeyen tek çıkış tensör boyutu: ${shape.contentToString()}")
                return SegmentedDetectionsWithPanels(emptyList())
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
                return SegmentedDetectionsWithPanels(emptyList())
            }
            val rowMajor = dim1 > dim2 && dim2 in 5..512
            val featureMajor = dim2 > dim1 && dim1 in 5..512
            if (!rowMajor && !featureMajor) {
                lastInferenceError = "Model çıkış eksenleri tanınmıyor: ${shape.contentToString()}"
                Log.w(TAG, "Tanınmayan YOLO çıktı eksenleri: ${shape.contentToString()}")
                return SegmentedDetectionsWithPanels(emptyList())
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
                return SegmentedDetectionsWithPanels(emptyList())
            }
            val headClassCount = when {
                endToEnd -> metadataClassCount
                hasObjectness -> numFeatures - 5
                else -> numFeatures - 4
            }
            if (headClassCount <= 0) {
                lastInferenceError = "Model çıkışında kullanılabilir sınıf kanalı bulunamadı."
                Log.w(TAG, "Çıktı sınıf kanalı bulunamadı: features=$numFeatures")
                return SegmentedDetectionsWithPanels(emptyList())
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
            return SegmentedDetectionsWithPanels(nmsDetections(detections))
        } catch (e: Exception) {
            lastInferenceError = e.localizedMessage ?: e.message ?: e.toString()
            Log.e(TAG, "ONNX çıkarım hatası: ${e.message}", e)
            return SegmentedDetectionsWithPanels(emptyList())
        } finally {
            if (scaledBmp != null && scaledBmp !== source && !scaledBmp.isRecycled) {
                scaledBmp.recycle()
            }
            try { inputTensor?.close() } catch (_: Exception) {}
            try { sizeTensor?.close() } catch (_: Exception) {}
            try { results?.close() } catch (_: Exception) {}
        }
    }

    private fun parseRtDetrMultiOutput(
        results: OrtSession.Result,
        outputNames: Collection<String>,
        origSizesInputName: String?,
        origW: Float,
        origH: Float,
        scaleX: Float,
        scaleY: Float,
        confThreshold: Float,
        configuredClassMap: Map<Int, String>,
        hasVerifiedClasses: Boolean,
        effectiveClassCount: Int,
        resolveLabel: (Int, Int) -> String,
        reportErrors: Boolean
    ): List<YoloDetection>? {
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
        if (boxesName == null || (scoresName == null && logitsName == null)) {
            if (reportErrors) {
                lastInferenceError = "RT-DETR çıkış adları beklenen kutu/skor bilgilerini içermiyor."
                Log.w(TAG, "Çoklu çıkış modeli tanındı ancak kutu/skor çıkış adları çözümlenemedi: $outputNames")
            }
            return null
        }
        val boxesOpt = results.get(boxesName)
        val scoresOpt = scoresName?.let { results.get(it) }
        val logitsOpt = logitsName?.let { results.get(it) }
        val labelsOpt = labelsName?.let { results.get(it) }

        val boxesTensor = boxesOpt.takeIf { it.isPresent }?.get() as? OnnxTensor
        val scoresTensor = scoresOpt?.takeIf { it.isPresent }?.get() as? OnnxTensor
        val logitsTensor = logitsOpt?.takeIf { it.isPresent }?.get() as? OnnxTensor
        val labelsTensor = labelsOpt?.takeIf { it.isPresent }?.get() as? OnnxTensor
        if (boxesTensor == null || (scoresTensor == null && logitsTensor == null)) return null

        val bBuf = boxesTensor.floatBuffer
        val bData = FloatArray(bBuf.remaining()).also { bBuf.get(it) }
        val normalizedBoxesName = boxesName.substringAfterLast('/').lowercase()
            .replace(Regex("[^a-z0-9]"), "")
        val normalizedLogitsName = logitsName?.substringAfterLast('/')?.lowercase()
            ?.replace(Regex("[^a-z0-9]"), "")
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
                if (reportErrors) lastInferenceError = "RT-DETR logits çıkışının sınıf boyutu geçersiz."
                return null
            }
            numDets = min(boxesCount, logitsData.size / logitsPerDetection)
            sData = FloatArray(numDets)
            val configuredClasses = configuredClassMap.keys.maxOrNull()?.plus(1)
                ?: if (hasVerifiedClasses) DEFAULT_CLASS_NAMES.size
                else logitsPerDetection
            val usableClassCount = when {
                isHuggingFaceRtDetrOutput -> logitsPerDetection
                logitsPerDetection == configuredClasses + 1 -> configuredClasses
                configuredClassMap.isEmpty() && logitsPerDetection == 81 -> 80
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
                    for (classIndex in 0 until logitsPerDetection) {
                        val logit = logitsData[detection * logitsPerDetection + classIndex]
                        if (logit.isFinite()) {
                            maxAllLogit = max(maxAllLogit, logit)
                        }
                    }
                    var denominator = 0.0
                    if (maxAllLogit.isFinite()) {
                        for (classIndex in 0 until logitsPerDetection) {
                            val logit = logitsData[detection * logitsPerDetection + classIndex]
                            if (logit.isFinite()) {
                                denominator += kotlin.math.exp((logit - maxAllLogit).toDouble())
                            }
                        }
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

        if (reportErrors) {
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
        }

        val detections = mutableListOf<YoloDetection>()
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
                val rawClassId = labelsData.getOrElse(i) { -1 }
                val label = resolveLabel(rawClassId, effectiveClassCount)

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

    private fun parseYoloSegmentationOutput(
        results: OrtSession.Result,
        detOutputName: String,
        protoOutputName: String,
        inputW: Int,
        inputH: Int,
        letterbox: LetterboxTransform,
        confThreshold: Float
    ): SegmentedDetectionsWithPanels {
        val detOpt = results.get(detOutputName)
        val protoOpt = results.get(protoOutputName)
        val detTensor = detOpt.takeIf { it.isPresent }?.get() as? OnnxTensor
        val protoTensor = protoOpt.takeIf { it.isPresent }?.get() as? OnnxTensor

        if (detTensor == null || protoTensor == null) {
            lastInferenceError = "YOLO segmentasyon tensörleri okunamadı."
            return SegmentedDetectionsWithPanels(emptyList())
        }

        val detShape = detTensor.info.shape
        val protoShape = protoTensor.info.shape
        val detBuffer = detTensor.floatBuffer
        val detData = FloatArray(detBuffer.remaining()).also { detBuffer.get(it) }
        val protoBuffer = protoTensor.floatBuffer
        val protoData = FloatArray(protoBuffer.remaining()).also { protoBuffer.get(it) }

        return parseYoloSegmentationTensorsWithPanels(
            detData = detData,
            detShape = detShape,
            protoData = protoData,
            protoShape = protoShape,
            inputW = inputW,
            inputH = inputH,
            letterbox = letterbox,
            confThreshold = confThreshold
        )
    }

    internal fun parseYoloSegmentationTensors(
        detData: FloatArray,
        detShape: LongArray,
        protoData: FloatArray,
        protoShape: LongArray,
        inputW: Int,
        inputH: Int,
        letterbox: LetterboxTransform,
        confThreshold: Float
    ): List<YoloDetection> = parseYoloSegmentationTensorsWithPanels(
        detData = detData,
        detShape = detShape,
        protoData = protoData,
        protoShape = protoShape,
        inputW = inputW,
        inputH = inputH,
        letterbox = letterbox,
        confThreshold = confThreshold
    ).detections

    internal fun parseYoloSegmentationTensorsWithPanels(
        detData: FloatArray,
        detShape: LongArray,
        protoData: FloatArray,
        protoShape: LongArray,
        inputW: Int,
        inputH: Int,
        letterbox: LetterboxTransform,
        confThreshold: Float
    ): SegmentedDetectionsWithPanels {
        if (detShape.size != 3 || protoShape.size != 4) {
            lastInferenceError = "Geçersiz YOLO segmentasyon tensör şekli: det=${detShape.contentToString()}, proto=${protoShape.contentToString()}"
            return SegmentedDetectionsWithPanels(emptyList())
        }

        val protoH = protoShape[2].toInt()
        val protoW = protoShape[3].toInt()
        val numProtos = protoShape[1].toInt().coerceAtLeast(1)
        if (protoH <= 0 || protoW <= 0) return SegmentedDetectionsWithPanels(emptyList())

        val dim1 = detShape[1].toInt()
        val dim2 = detShape[2].toInt()
        val rowMajor = dim1 > dim2 && dim2 in 5..512
        val featureMajor = dim2 > dim1 && dim1 in 5..512
        if (!rowMajor && !featureMajor) {
            lastInferenceError = "Tanınmayan YOLO segmentasyon çıktı eksenleri: ${detShape.contentToString()}"
            return SegmentedDetectionsWithPanels(emptyList())
        }

        val numFeatures = if (rowMajor) dim2 else dim1
        val numCandidates = if (rowMajor) dim1 else dim2
        if (detData.size.toLong() < numFeatures.toLong() * numCandidates.toLong() ||
            protoData.size.toLong() < numProtos.toLong() * protoH.toLong() * protoW.toLong()
        ) {
            lastInferenceError = "YOLO segmentasyon tensörü beklenen boyuttan kısa."
            return SegmentedDetectionsWithPanels(emptyList())
        }

        val numMaskCoeffs = min(32, numProtos).coerceAtMost((numFeatures - 5).coerceAtLeast(0))
        if (numMaskCoeffs <= 0) return SegmentedDetectionsWithPanels(emptyList())
        val numClasses = (numFeatures - 4 - numMaskCoeffs).coerceAtLeast(1)
        if (4 + numClasses + numMaskCoeffs > numFeatures) return SegmentedDetectionsWithPanels(emptyList())

        fun getDetValue(candidate: Int, feature: Int): Float =
            if (rowMajor) detData[candidate * numFeatures + feature]
            else detData[feature * numCandidates + candidate]

        // Check if coordinates are normalized
        var maxCoordinateMagnitude = 0f
        val checkLimit = min(numCandidates, 100)
        for (candidate in 0 until checkLimit) {
            for (c in 0..3) {
                maxCoordinateMagnitude = max(maxCoordinateMagnitude, abs(getDetValue(candidate, c)))
            }
        }
        val coordinatesAreNormalized = maxCoordinateMagnitude <= 2f

        class RawSegCandidate(
            val boxModel: RectF,
            val score: Float,
            val classId: Int,
            val label: String,
            val maskCoeffs: FloatArray
        )

        val rawCandidates = ArrayList<RawSegCandidate>(64)

        for (candidate in 0 until numCandidates) {
            var bestClass = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (c in 0 until numClasses) {
                val s = getDetValue(candidate, 4 + c)
                if (s.isFinite() && s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (!bestScore.isFinite() || bestScore < confThreshold) continue

            val label = getLabelForClass(bestClass, numClasses)

            val rawCx = getDetValue(candidate, 0)
            val rawCy = getDetValue(candidate, 1)
            val rawW = getDetValue(candidate, 2)
            val rawH = getDetValue(candidate, 3)

            val cx = if (coordinatesAreNormalized) rawCx * inputW else rawCx
            val cy = if (coordinatesAreNormalized) rawCy * inputH else rawCy
            val w = if (coordinatesAreNormalized) rawW * inputW else rawW
            val h = if (coordinatesAreNormalized) rawH * inputH else rawH

            val x1 = (cx - w * 0.5f).coerceIn(0f, inputW.toFloat())
            val y1 = (cy - h * 0.5f).coerceIn(0f, inputH.toFloat())
            val x2 = (cx + w * 0.5f).coerceIn(x1, inputW.toFloat())
            val y2 = (cy + h * 0.5f).coerceIn(y1, inputH.toFloat())

            val coeffs = FloatArray(numMaskCoeffs) { k ->
                getDetValue(candidate, 4 + numClasses + k)
            }
            rawCandidates.add(RawSegCandidate(RectF(x1, y1, x2, y2), bestScore, bestClass, label, coeffs))
        }

        if (rawCandidates.isEmpty()) return SegmentedDetectionsWithPanels(emptyList())
        rawCandidates.sortByDescending { it.score }

        // Sınıf-duyarlı Greedy NMS + Balon içi dikey kardan adam koruması
        fun boxIoU(a: RectF, b: RectF): Float {
            val interL = max(a.left, b.left)
            val interT = max(a.top, b.top)
            val interR = min(a.right, b.right)
            val interB = min(a.bottom, b.bottom)
            if (interR <= interL || interB <= interT) return 0f
            val interArea = (interR - interL) * (interB - interT)
            val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
            return if (unionArea > 0f) interArea / unionArea else 0f
        }

        val selected = ArrayList<RawSegCandidate>(rawCandidates.size)
        val suppressed = BooleanArray(rawCandidates.size)

        for (i in 0 until rawCandidates.size) {
            if (suppressed[i]) continue
            val candA = rawCandidates[i]
            selected.add(candA)

            for (j in (i + 1) until rawCandidates.size) {
                if (suppressed[j]) continue
                val candB = rawCandidates[j]
                // Farklı sınıflar (ör. "bubble", "panel", "sfx", "text") birbirini bastırmaz
                if (candA.classId != candB.classId) continue

                val iou = boxIoU(candA.boxModel, candB.boxModel)
                if (candA.label in BUBBLE_LABELS || candA.label == "text" || candA.label == "text_bubble") {
                    if (iou > 0.65f) {
                        suppressed[j] = true
                    } else if (iou >= 0.35f) {
                        // Şüpheci kuralı: Kardan adam / dikey bitişik balon koruması
                        val deltaX = abs(candA.boxModel.centerX() - candB.boxModel.centerX())
                        val deltaY = abs(candA.boxModel.centerY() - candB.boxModel.centerY())
                        val maxW = max(candA.boxModel.width(), candB.boxModel.width())
                        val maxH = max(candA.boxModel.height(), candB.boxModel.height())
                        if (deltaX < 0.28f * maxW && deltaY < 0.28f * maxH) {
                            suppressed[j] = true
                        }
                    }
                } else if (candA.label in PANEL_LABELS) {
                    if (iou > 0.65f) suppressed[j] = true
                } else if (iou > 0.50f) {
                    suppressed[j] = true
                }
            }
        }

        // Un-letterbox boxes and prototype masks back to original page normalized coordinates
        val detections = ArrayList<YoloDetection>(selected.size)
        val panels = ArrayList<YoloDetection>(8)

        for (det in selected) {
            val normBox = mapLetterboxBoxToNormalized(det.boxModel, letterbox)
            if (normBox.width() < 0.004f || normBox.height() < 0.004f) continue

            if (det.label in PANEL_LABELS) {
                // Yalnızca metin barındıran kutu adayı olabilecek küçük paneller için kontur çıkar
                val panelArea = normBox.width() * normBox.height()
                val profile = if (panelArea < 0.18f && normBox.width() < 0.65f && normBox.height() < 0.65f) {
                    extractSegmentationContourProfile(
                        normBox = normBox,
                        letterbox = letterbox,
                        protoData = protoData,
                        protoW = protoW,
                        protoH = protoH,
                        maskCoeffs = det.maskCoeffs,
                        numMaskCoeffs = numMaskCoeffs
                    )
                } else {
                    null
                }
                panels.add(
                    YoloDetection(
                        classId = det.classId,
                        label = det.label,
                        confidence = det.score,
                        box = normBox,
                        contourProfile = profile
                    )
                )
                continue
            }

            // "sfx" tespitleri dışlama amacıyla korunur; gereksiz maske konturu çıkarılmaz
            val profile = if (det.label == "sfx") {
                null
            } else {
                extractSegmentationContourProfile(
                    normBox = normBox,
                    letterbox = letterbox,
                    protoData = protoData,
                    protoW = protoW,
                    protoH = protoH,
                    maskCoeffs = det.maskCoeffs,
                    numMaskCoeffs = numMaskCoeffs
                )
            }

            detections.add(
                YoloDetection(
                    classId = det.classId,
                    label = det.label,
                    confidence = det.score,
                    box = normBox,
                    contourProfile = profile
                )
            )
        }

        return SegmentedDetectionsWithPanels(detections = detections, panels = panels)
    }

    internal fun computeDynamicRectInputSize(
        origW: Int,
        origH: Int,
        maxLongSide: Int = DEFAULT_YOLO11S_INPUT_SIZE,
        stride: Int = DEFAULT_MODEL_STRIDE
    ): Pair<Int, Int> {
        val safeW = origW.coerceAtLeast(1)
        val safeH = origH.coerceAtLeast(1)
        val safeStride = stride.coerceAtLeast(1)
        val safeMax = (maxLongSide.coerceAtLeast(safeStride) / safeStride) * safeStride
        return if (safeW >= safeH) {
            val targetW = safeMax
            val rawH = safeMax.toFloat() * (safeH.toFloat() / safeW.toFloat())
            val targetH = (kotlin.math.ceil(rawH / safeStride).toInt() * safeStride).coerceIn(safeStride, safeMax)
            targetW to targetH
        } else {
            val targetH = safeMax
            val rawW = safeMax.toFloat() * (safeW.toFloat() / safeH.toFloat())
            val targetW = (kotlin.math.ceil(rawW / safeStride).toInt() * safeStride).coerceIn(safeStride, safeMax)
            targetW to targetH
        }
    }

    internal fun computeLetterboxTransform(
        origW: Int,
        origH: Int,
        inputW: Int,
        inputH: Int
    ): LetterboxTransform {
        val safeOrigW = origW.coerceAtLeast(1)
        val safeOrigH = origH.coerceAtLeast(1)
        val safeInputW = inputW.coerceAtLeast(1)
        val safeInputH = inputH.coerceAtLeast(1)
        val gain = min(
            safeInputW.toFloat() / safeOrigW.toFloat(),
            safeInputH.toFloat() / safeOrigH.toFloat()
        )
        val contentW = (safeOrigW * gain).roundToInt().coerceIn(1, safeInputW)
        val contentH = (safeOrigH * gain).roundToInt().coerceIn(1, safeInputH)
        val padLeft = (safeInputW - contentW) / 2
        val padTop = (safeInputH - contentH) / 2
        return LetterboxTransform(
            origW = safeOrigW,
            origH = safeOrigH,
            inputW = safeInputW,
            inputH = safeInputH,
            gain = gain,
            contentW = contentW,
            contentH = contentH,
            padLeft = padLeft,
            padTop = padTop
        )
    }

    internal fun mapLetterboxBoxToNormalized(
        boxModel: RectF,
        letterbox: LetterboxTransform
    ): RectF {
        val padX = letterbox.padLeft.toFloat()
        val padY = letterbox.padTop.toFloat()
        val contentW = letterbox.contentW.toFloat().coerceAtLeast(1f)
        val contentH = letterbox.contentH.toFloat().coerceAtLeast(1f)
        val normL = ((boxModel.left - padX) / contentW).coerceIn(0f, 1f)
        val normT = ((boxModel.top - padY) / contentH).coerceIn(0f, 1f)
        val normR = ((boxModel.right - padX) / contentW).coerceIn(normL, 1f)
        val normB = ((boxModel.bottom - padY) / contentH).coerceIn(normT, 1f)
        return RectF(normL, normT, normR, normB)
    }

    internal fun extractSegmentationContourProfile(
        normBox: RectF,
        letterbox: LetterboxTransform,
        protoData: FloatArray,
        protoW: Int,
        protoH: Int,
        maskCoeffs: FloatArray,
        numMaskCoeffs: Int = maskCoeffs.size
    ): ContourScanlineProfile? {
        if (protoW <= 0 || protoH <= 0 || numMaskCoeffs <= 0) return null
        val maskThreshold = 0.45f
        val thresholdLogit = kotlin.math.ln(maskThreshold / (1.0f - maskThreshold))
        val hw = protoH * protoW
        val protoStrideX = letterbox.inputW.toFloat() / protoW.toFloat()
        val protoStrideY = letterbox.inputH.toFloat() / protoH.toFloat()

        // Clamp mask sampling strictly to the un-letterboxed page content region so padding
        // never leaks into scanline rows/columns or shifts contour fractions.
        val clampedModelL = letterbox.padLeft + normBox.left * letterbox.contentW
        val clampedModelT = letterbox.padTop + normBox.top * letterbox.contentH
        val clampedModelR = letterbox.padLeft + normBox.right * letterbox.contentW
        val clampedModelB = letterbox.padTop + normBox.bottom * letterbox.contentH

        val rawProtoW = (clampedModelR - clampedModelL) / protoStrideX
        val rawProtoH = (clampedModelB - clampedModelT) / protoStrideY
        // Mikro 4x4 proto pikselinden küçük kutularda analitik elipse düş; >= 4f için bilinear örnekleme yap
        if (rawProtoW < 4f || rawProtoH < 4f) return null

        val pxMin = kotlin.math.floor(clampedModelL / protoStrideX).toInt().coerceIn(0, protoW - 1)
        val pxMax = (kotlin.math.ceil(clampedModelR / protoStrideX).toInt() - 1).coerceIn(pxMin, protoW - 1)
        val pyMin = kotlin.math.floor(clampedModelT / protoStrideY).toInt().coerceIn(0, protoH - 1)
        val pyMax = (kotlin.math.ceil(clampedModelB / protoStrideY).toInt() - 1).coerceIn(pyMin, protoH - 1)

        val subW = pxMax - pxMin + 1
        val subH = pyMax - pyMin + 1
        val useBilinear = subW < 16 || subH < 16

        val boxProtoL = clampedModelL / protoStrideX
        val boxProtoT = clampedModelT / protoStrideY
        val boxProtoW = rawProtoW.coerceAtLeast(1f)
        val boxProtoH = rawProtoH.coerceAtLeast(1f)

        val outH = if (useBilinear) {
            kotlin.math.ceil((rawProtoH * 2.0)).toInt().coerceAtLeast(8)
        } else {
            subH
        }
        val rowLeftFractions = FloatArray(outH) { -1f }
        val rowRightFractions = FloatArray(outH) { -1f }
        var sumOptX = 0.0
        var sumOptY = 0.0
        var validCount = 0

        if (useBilinear) {
            val sampleCols = kotlin.math.ceil((rawProtoW * 2.0)).toInt().coerceAtLeast(8)
            val stepProtoX = rawProtoW / sampleCols.toFloat()
            val stepProtoY = rawProtoH / outH.toFloat()
            for (r in 0 until outH) {
                val pyFloat = boxProtoT + (r + 0.5f) * stepProtoY
                val fy = (pyFloat - 0.5f).coerceIn(0f, (protoH - 1).toFloat())
                val y0 = kotlin.math.floor(fy).toInt().coerceIn(0, protoH - 1)
                val y1 = (y0 + 1).coerceAtMost(protoH - 1)
                val wy = (fy - y0).coerceIn(0f, 1f)

                val runs = mutableListOf<Pair<Int, Int>>()
                var runStart = -1
                for (c in 0 until sampleCols) {
                    val pxFloat = boxProtoL + (c + 0.5f) * stepProtoX
                    val fx = (pxFloat - 0.5f).coerceIn(0f, (protoW - 1).toFloat())
                    val x0 = kotlin.math.floor(fx).toInt().coerceIn(0, protoW - 1)
                    val x1 = (x0 + 1).coerceAtMost(protoW - 1)
                    val wx = (fx - x0).coerceIn(0f, 1f)

                    val w00 = (1f - wx) * (1f - wy)
                    val w10 = wx * (1f - wy)
                    val w01 = (1f - wx) * wy
                    val w11 = wx * wy
                    val i00 = y0 * protoW + x0
                    val i10 = y0 * protoW + x1
                    val i01 = y1 * protoW + x0
                    val i11 = y1 * protoW + x1

                    var z = 0f
                    for (k in 0 until numMaskCoeffs) {
                        val base = k * hw
                        val pVal = w00 * protoData[base + i00] +
                            w10 * protoData[base + i10] +
                            w01 * protoData[base + i01] +
                            w11 * protoData[base + i11]
                        z += maskCoeffs[k] * pVal
                    }
                    if (z >= thresholdLogit) {
                        if (runStart < 0) runStart = c
                        sumOptX += ((c + 0.5f) / sampleCols.toFloat()).coerceIn(0f, 1f)
                        sumOptY += ((r + 0.5f) / outH.toFloat()).coerceIn(0f, 1f)
                        validCount++
                    } else if (runStart >= 0) {
                        runs.add(runStart to (c - 1))
                        runStart = -1
                    }
                }
                if (runStart >= 0) runs.add(runStart to (sampleCols - 1))

                if (runs.isNotEmpty()) {
                    val centerCol = sampleCols * 0.5f
                    val primary = runs.maxByOrNull { run ->
                        val len = (run.second - run.first + 1).toFloat()
                        val runMid = (run.first + run.second) * 0.5f
                        val centerBonus = 1f - (abs(runMid - centerCol) / sampleCols.toFloat()).coerceIn(0f, 1f) * 0.35f
                        len * centerBonus
                    }
                    if (primary != null) {
                        var firstCol = primary.first
                        var lastCol = primary.second
                        // Only bridge tiny internal glyph gaps (<= 2 cols), never disconnected exterior leaks.
                        var merged = true
                        while (merged) {
                            merged = false
                            for (run in runs) {
                                if (run.second < firstCol && firstCol - run.second <= 2) {
                                    firstCol = run.first
                                    merged = true
                                } else if (run.first > lastCol && run.first - lastCol <= 2) {
                                    lastCol = run.second
                                    merged = true
                                }
                            }
                        }
                        val leftFrac = (firstCol.toFloat() / sampleCols.toFloat()).coerceIn(0f, 1f)
                        val rightFrac = ((lastCol + 1).toFloat() / sampleCols.toFloat()).coerceIn(leftFrac, 1f)
                        rowLeftFractions[r] = leftFrac
                        rowRightFractions[r] = rightFrac
                    }
                }
            }
        } else {
            val stepProtoY = boxProtoH / outH.toFloat()
            for (r in 0 until outH) {
                val py = kotlin.math.floor(boxProtoT + (r + 0.5f) * stepProtoY).toInt().coerceIn(pyMin, pyMax)
                val baseRowIdx = py * protoW

                val runs = mutableListOf<Pair<Int, Int>>()
                var runStart = -1
                for (px in pxMin..pxMax) {
                    var z = 0f
                    val pixelIdx = baseRowIdx + px
                    for (k in 0 until numMaskCoeffs) {
                        z += maskCoeffs[k] * protoData[k * hw + pixelIdx]
                    }
                    if (z >= thresholdLogit) {
                        if (runStart < 0) runStart = px
                        sumOptX += ((px + 0.5f - boxProtoL) / boxProtoW).coerceIn(0f, 1f)
                        sumOptY += ((r + 0.5f) / outH.toFloat()).coerceIn(0f, 1f)
                        validCount++
                    } else {
                        if (runStart >= 0) {
                            runs.add(runStart to (px - 1))
                            runStart = -1
                        }
                    }
                }
                if (runStart >= 0) runs.add(runStart to pxMax)

                if (runs.isNotEmpty()) {
                    // Pick the single dominant central run and bridge only <=2px internal glyph gaps;
                    // never bridge across wide gaps into leaked background blobs.
                    val centerPx = boxProtoL + boxProtoW * 0.5f
                    val primary = runs.maxByOrNull { run ->
                        val len = (run.second - run.first + 1).toFloat()
                        val runMid = (run.first + run.second) * 0.5f
                        val centerBonus = 1f - (abs(runMid - centerPx) / boxProtoW).coerceIn(0f, 1f) * 0.35f
                        len * centerBonus
                    }
                    if (primary != null) {
                        var firstX = primary.first
                        var lastX = primary.second
                        var merged = true
                        while (merged) {
                            merged = false
                            for (run in runs) {
                                if (run.second < firstX && firstX - run.second <= 2) {
                                    firstX = run.first
                                    merged = true
                                } else if (run.first > lastX && run.first - lastX <= 2) {
                                    lastX = run.second
                                    merged = true
                                }
                            }
                        }
                        val leftFrac = ((firstX.toFloat() - boxProtoL) / boxProtoW).coerceIn(0f, 1f)
                        val rightFrac = ((lastX + 1f - boxProtoL) / boxProtoW).coerceIn(leftFrac, 1f)
                        rowLeftFractions[r] = leftFrac
                        rowRightFractions[r] = rightFrac
                    }
                }
            }
        }

        if (validCount == 0) return null

        // 1. Koyu kanji nedeniyle oluşan delikleri komşulardan onar
        for (r in 0 until outH) {
            if (rowLeftFractions[r] < 0f) {
                val prevL = if (r > 0) rowLeftFractions[r - 1] else -1f
                val nextIdx = (r + 1 until outH).firstOrNull { rowLeftFractions[it] >= 0f }
                val nextL = nextIdx?.let { rowLeftFractions[it] } ?: -1f
                if (prevL >= 0f && nextIdx != null && nextL >= 0f) {
                    rowLeftFractions[r] = (prevL + nextL) * 0.5f
                    rowRightFractions[r] = ((rowRightFractions.getOrNull(r - 1) ?: 1f) + rowRightFractions[nextIdx]) * 0.5f
                } else if (prevL >= 0f) {
                    rowLeftFractions[r] = prevL
                    rowRightFractions[r] = rowRightFractions.getOrNull(r - 1) ?: 1f
                } else if (nextIdx != null && nextL >= 0f) {
                    rowLeftFractions[r] = rowLeftFractions[nextIdx]
                    rowRightFractions[r] = rowRightFractions[nextIdx]
                } else {
                    rowLeftFractions[r] = 0.15f
                    rowRightFractions[r] = 0.85f
                }
            }
        }

        // 2. 5-Satırlı Medyan Filtresi: 1-2 satırlık keskin sızıntı dikenlerini (ör. arka plan gagaları)
        // gerçek balon köşelerini bozmadan tamamen temizler.
        if (outH >= 5) {
            val medL = FloatArray(outH)
            val medR = FloatArray(outH)
            val winL = FloatArray(5)
            val winR = FloatArray(5)
            for (r in 0 until outH) {
                for (k in -2..2) {
                    val idx = (r + k).coerceIn(0, outH - 1)
                    winL[k + 2] = rowLeftFractions[idx]
                    winR[k + 2] = rowRightFractions[idx]
                }
                winL.sort()
                winR.sort()
                medL[r] = winL[2]
                medR[r] = winR[2].coerceAtLeast(medL[r])
            }
            for (r in 0 until outH) {
                rowLeftFractions[r] = medL[r]
                rowRightFractions[r] = medR[r]
            }
        }

        // 3. Bölgesel Kubbe ve Taban Daralma Kalkanı (Top %15 & Bottom %15)
        // Orta bant dikdörtgense (düşük sol/sağ kenar sapması) köşeleri yuvarlatma
        val midStart = (outH * 0.15f).toInt().coerceIn(0, outH - 1)
        val midEnd = (outH * 0.85f).toInt().coerceIn(midStart, outH - 1)
        var midMinLeft = Float.POSITIVE_INFINITY
        var midMaxLeft = Float.NEGATIVE_INFINITY
        var midMinRight = Float.POSITIVE_INFINITY
        var midMaxRight = Float.NEGATIVE_INFINITY
        for (r in midStart..midEnd) {
            val lf = rowLeftFractions[r]
            val rf = rowRightFractions[r]
            if (lf < midMinLeft) midMinLeft = lf
            if (lf > midMaxLeft) midMaxLeft = lf
            if (rf < midMinRight) midMinRight = rf
            if (rf > midMaxRight) midMaxRight = rf
        }
        val leftSpread = if (midMaxLeft >= midMinLeft) midMaxLeft - midMinLeft else 1f
        val rightSpread = if (midMaxRight >= midMinRight) midMaxRight - midMinRight else 1f
        val isMidBandRectangular = leftSpread <= 0.08f && rightSpread <= 0.08f

        if (!isMidBandRectangular) {
            val topCut = (outH * 0.15f).toInt().coerceIn(1, outH - 1)
            for (r in (topCut - 1) downTo 0) {
                val cl = maxOf(rowLeftFractions[r], rowLeftFractions[r + 1] - 0.04f)
                val cr = minOf(rowRightFractions[r], rowRightFractions[r + 1] + 0.04f)
                if (cl <= cr) {
                    rowLeftFractions[r] = cl
                    rowRightFractions[r] = cr
                } else {
                    val mid = (cl + cr) * 0.5f
                    rowLeftFractions[r] = mid
                    rowRightFractions[r] = mid
                }
            }
            val botCut = (outH * 0.85f).toInt().coerceIn(topCut, outH - 1)
            for (r in botCut until outH) {
                val cl = maxOf(rowLeftFractions[r], rowLeftFractions[r - 1] - 0.04f)
                val cr = minOf(rowRightFractions[r], rowRightFractions[r - 1] + 0.04f)
                if (cl <= cr) {
                    rowLeftFractions[r] = cl
                    rowRightFractions[r] = cr
                } else {
                    val mid = (cl + cr) * 0.5f
                    rowLeftFractions[r] = mid
                    rowRightFractions[r] = mid
                }
            }
        }

        // 4. 2-Geçişli 5-Noktalı Gauss Yumuşatma (0.10, 0.20, 0.40, 0.20, 0.10) — basamaklı/tırtıklı konturu giderir.
        var curL = rowLeftFractions.copyOf()
        var curR = rowRightFractions.copyOf()
        repeat(2) {
            val nextL = FloatArray(outH)
            val nextR = FloatArray(outH)
            for (r in 0 until outH) {
                val l0 = curL[(r - 2).coerceAtLeast(0)]
                val l1 = curL[(r - 1).coerceAtLeast(0)]
                val l2 = curL[r]
                val l3 = curL[(r + 1).coerceAtMost(outH - 1)]
                val l4 = curL[(r + 2).coerceAtMost(outH - 1)]
                nextL[r] = (0.10f * l0 + 0.20f * l1 + 0.40f * l2 + 0.20f * l3 + 0.10f * l4).coerceIn(0f, 1f)

                val r0 = curR[(r - 2).coerceAtLeast(0)]
                val r1 = curR[(r - 1).coerceAtLeast(0)]
                val r2 = curR[r]
                val r3 = curR[(r + 1).coerceAtMost(outH - 1)]
                val r4 = curR[(r + 2).coerceAtMost(outH - 1)]
                nextR[r] = (0.10f * r0 + 0.20f * r1 + 0.40f * r2 + 0.20f * r3 + 0.10f * r4).coerceIn(nextL[r], 1f)
            }
            curL = nextL
            curR = nextR
        }
        val smoothL = curL
        val smoothR = curR

        val optX = if (validCount > 0) (sumOptX / validCount).toFloat().coerceIn(0.1f, 0.9f) else 0.5f
        val optY = if (validCount > 0) (sumOptY / validCount).toFloat().coerceIn(0.1f, 0.9f) else 0.5f

        return ContourScanlineProfile(smoothL, smoothR, optX, optY).takeIf { it.isUsable }
    }

    /**
     * Fast grid-and-scanline pre-check deciding whether an inverted YOLO pass is
     * warranted for this page or tile. Plain white manga pages (even with dark hair
     * or black ink) return false, while pages/tiles containing dark or colored
     * bubbles/boxes with bright white text return true.
     */
    internal fun shouldRunInvertedYoloPass(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled || bitmap.width < 16 || bitmap.height < 16) return false
        val w = bitmap.width
        val h = bitmap.height
        val stepY = max(1, h / 160)
        val stepX = max(1, w / 320)
        val cols = (w / 40).coerceIn(4, 20)
        val rows = (h / 40).coerceIn(4, 24)
        val cellCount = cols * rows
        val cellTotal = IntArray(cellCount)
        val cellDark = IntArray(cellCount)
        val cellNonWhite = IntArray(cellCount)
        val cellBright = IntArray(cellCount)
        val cellSandwichedBright = IntArray(cellCount)
        val cellLumaHist = IntArray(cellCount * 16)

        val rowPixels = IntArray(w)
        val maxStrokeSamples = max(6, ((w * 0.18f) / stepX).roundToInt())
        var totalSamples = 0
        var globalDark = 0

        var y = 0
        while (y < h) {
            bitmap.getPixels(rowPixels, 0, w, 0, y, w, 1)
            val cellRow = ((y * rows) / h).coerceIn(0, rows - 1)
            val rowCellBase = cellRow * cols

            var seenDarkBefore = false
            var leftMinLuma = 255
            var inContrastRun = false
            var contrastRunSamples = 0
            var contrastRunMaxLuma = 0
            var contrastRunHasBright = false
            var contrastRunCell = 0

            var x = 0
            while (x < w) {
                val pixel = rowPixels[x]
                val alpha = ((pixel ushr 24) and 0xFF) / 255f
                val r = ((pixel shr 16) and 0xFF) * alpha + 255f * (1f - alpha)
                val g = ((pixel shr 8) and 0xFF) * alpha + 255f * (1f - alpha)
                val b = (pixel and 0xFF) * alpha + 255f * (1f - alpha)
                val luma = (r * 0.299f + g * 0.587f + b * 0.114f).roundToInt()

                val cellCol = ((x * cols) / w).coerceIn(0, cols - 1)
                val cellIdx = rowCellBase + cellCol
                cellTotal[cellIdx]++
                totalSamples++
                cellLumaHist[cellIdx * 16 + (luma shr 4).coerceIn(0, 15)]++
                if (luma < 90) {
                    cellDark[cellIdx]++
                    globalDark++
                }
                if (luma < 198) cellNonWhite[cellIdx]++
                if (luma >= 205) cellBright[cellIdx]++

                if (luma >= 175 || (inContrastRun && luma >= 160)) {
                    if (!inContrastRun) {
                        inContrastRun = true
                        contrastRunSamples = 1
                        contrastRunMaxLuma = luma
                        contrastRunHasBright = luma >= 205
                        contrastRunCell = cellIdx
                    } else {
                        contrastRunSamples++
                        if (luma > contrastRunMaxLuma) contrastRunMaxLuma = luma
                        if (luma >= 205) contrastRunHasBright = true
                    }
                    if (contrastRunSamples > maxStrokeSamples) {
                        inContrastRun = false
                        seenDarkBefore = false
                        leftMinLuma = 255
                    }
                } else if (luma < 160) {
                    if (inContrastRun) {
                        val minContrast = if (contrastRunHasBright) 42 else 48
                        if (seenDarkBefore &&
                            contrastRunSamples <= maxStrokeSamples &&
                            contrastRunMaxLuma - leftMinLuma >= minContrast &&
                            contrastRunMaxLuma - luma >= minContrast
                        ) {
                            cellSandwichedBright[contrastRunCell]++
                        }
                        inContrastRun = false
                        leftMinLuma = luma
                    } else {
                        leftMinLuma = if (!seenDarkBefore) luma else min(leftMinLuma, luma)
                    }
                    seenDarkBefore = true
                }

                x += stepX
            }
            y += stepY
        }

        if (totalSamples <= 0) return false

        fun cellPercentileLuma(cellIdx: Int, pct: Int): Int {
            val total = cellTotal[cellIdx]
            if (total <= 0) return 0
            val target = (((total - 1) * pct) / 100).coerceIn(0, total - 1)
            var acc = 0
            val base = cellIdx * 16
            for (bin in 0 until 16) {
                acc += cellLumaHist[base + bin]
                if (acc > target) return bin * 16 + 8
            }
            return 248
        }

        var darkCellSandwichedRuns = 0
        for (c in 0 until cellCount) {
            val count = cellTotal[c]
            if (count < 8) continue
            val nonWhiteRatio = cellNonWhite[c].toFloat() / count
            val darkRatio = cellDark[c].toFloat() / count
            val brightRatio = cellBright[c].toFloat() / count
            val p95MinusMedian = cellPercentileLuma(c, 95) - cellPercentileLuma(c, 50)

            // İnce siyah hap / koyu balon (hücre darkRatio >= 0.25f + kontrastlı beyaz/açık metin)
            if (darkRatio >= 0.25f &&
                brightRatio <= 0.42f &&
                ((brightRatio >= 0.01f && cellSandwichedBright[c] >= 1) ||
                    (p95MinusMedian >= 48 && cellSandwichedBright[c] >= 2))
            ) {
                return true
            }

            if (nonWhiteRatio >= 0.55f && (darkRatio >= 0.06f || nonWhiteRatio >= 0.65f)) {
                darkCellSandwichedRuns += cellSandwichedBright[c]
                if (brightRatio in 0.01f..0.42f && cellBright[c] >= 2 && cellSandwichedBright[c] >= 2) {
                    return true
                }
            }
        }
        val globalDarkRatio = globalDark.toFloat() / totalSamples
        return globalDarkRatio >= 0.07f && darkCellSandwichedRuns >= 2
    }

    /**
     * Filters candidates produced by the inverted YOLO11s-seg pass so only genuine
     * dark-interior speech bubbles or dark text boxes not already covered by the primary pass survive.
     */
    internal fun filterInvertedBubbleDetections(
        bitmap: Bitmap,
        invertedDetections: List<YoloDetection>,
        primaryDetections: List<YoloDetection>
    ): List<YoloDetection> {
        if (invertedDetections.isEmpty() || bitmap.isRecycled || bitmap.width < 8 || bitmap.height < 8) {
            return emptyList()
        }
        val primaryBalloons = primaryDetections.filter { it.label in BUBBLE_LABELS }
        val primaryBalloonsOrText = primaryDetections.filter { it.label in PRIMARY_TEXT_SUPPRESS_LABELS }
        val accepted = mutableListOf<YoloDetection>()
        val prioritized = invertedDetections.sortedWith(
            compareBy<YoloDetection> { if (it.label in BUBBLE_LABELS) 0 else 1 }
                .thenByDescending { it.confidence }
        )
        for (cand in prioritized) {
            if (cand.label !in INVERTED_RESCUE_LABELS) continue
            val primaryToCheck = if (cand.label in BUBBLE_LABELS) primaryBalloons else primaryBalloonsOrText
            val overlapsPrimary = primaryToCheck.any { det ->
                iou(cand.box, det.box) > 0.30f || intersectionOverSmallerArea(cand.box, det.box) > 0.45f
            }
            if (overlapsPrimary) continue
            val overlapsAccepted = accepted.any { det ->
                iou(cand.box, det.box) > 0.30f || intersectionOverSmallerArea(cand.box, det.box) > 0.45f
            }
            if (overlapsAccepted) continue
            if (!hasDarkInterior(bitmap, cand.box)) continue
            accepted.add(cand)
        }
        return accepted
    }

    internal fun hasDarkInterior(
        bitmap: Bitmap,
        normBox: RectF,
        maxMedianLuma: Float = 85f
    ): Boolean {
        if (bitmap.isRecycled || bitmap.width < 8 || bitmap.height < 8) return false
        val pxLeft = (normBox.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val pxTop = (normBox.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val pxRight = (normBox.right * bitmap.width).toInt().coerceIn(pxLeft + 1, bitmap.width)
        val pxBottom = (normBox.bottom * bitmap.height).toInt().coerceIn(pxTop + 1, bitmap.height)
        val boxW = pxRight - pxLeft
        val boxH = pxBottom - pxTop
        if (boxW < 8 || boxH < 8) return false

        val insetX = (boxW * 0.08f).toInt().coerceIn(1, max(1, boxW / 4))
        val insetY = (boxH * 0.08f).toInt().coerceIn(1, max(1, boxH / 4))
        val sLeft = (pxLeft + insetX).coerceIn(0, bitmap.width - 1)
        val sRight = (pxRight - insetX).coerceIn(sLeft, bitmap.width - 1)
        val sTop = (pxTop + insetY).coerceIn(0, bitmap.height - 1)
        val sBottom = (pxBottom - insetY).coerceIn(sTop, bitmap.height - 1)

        val stepX = max(1, (sRight - sLeft) / 24)
        val stepY = max(1, (sBottom - sTop) / 24)
        val lums = ArrayList<Float>(625)
        for (y in sTop..sBottom step stepY) {
            for (x in sLeft..sRight step stepX) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = ((pixel ushr 24) and 0xFF) / 255f
                val r = ((pixel shr 16) and 0xFF) * alpha + 255f * (1f - alpha)
                val g = ((pixel shr 8) and 0xFF) * alpha + 255f * (1f - alpha)
                val b = (pixel and 0xFF) * alpha + 255f * (1f - alpha)
                lums.add(r * 0.299f + g * 0.587f + b * 0.114f)
            }
        }
        if (lums.isEmpty()) return false
        lums.sort()
        val medianLuma = lums[lums.size / 2]
        if (medianLuma < maxMedianLuma) return true
        val p95Index = (((lums.size - 1) * 95) / 100).coerceIn(0, lums.lastIndex)
        val p95Luma = lums[p95Index]
        return medianLuma < 198f && p95Luma >= 205f && (p95Luma - medianLuma) >= 42f
    }
}
