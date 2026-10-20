package com.example.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.example.data.db.TranslationCorrectionDao
import com.example.i18n.TranslationLanguages
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TextBlockObj(
    val originalText: String,
    val translatedText: String,
    val boundingBox: Rect,
    val bubbleBox: Rect = boundingBox,
    val isBubble: Boolean,
    val isDarkBubble: Boolean = false,
    val normalizedLeft: Float = 0f,
    val normalizedTop: Float = 0f,
    val normalizedWidth: Float = 0f,
    val normalizedHeight: Float = 0f,
    val backgroundColor: Int? = null,
    // Raw detector/heuristic bubble bounds, distinct from the text layout box.
    val layoutBox: Rect = bubbleBox,
    val translationSucceeded: Boolean = true,
    // Pixel coordinate space used by OCR and all rectangles above.
    val coordinateImageWidth: Int = 0,
    val coordinateImageHeight: Int = 0
)

data class TranslationRequest(val id: String, val text: String)
data class TranslationResult(val id: String, val translatedText: String?, val error: String? = null) {
    val succeeded: Boolean get() = !translatedText.isNullOrBlank()
}

data class TranslationOutcome(
    val results: List<TranslationResult>,
    val warning: String? = null
)

data class BubbleAnalysis(
    val isBubble: Boolean,
    val isDarkBubble: Boolean
)

class TranslationService(
    private val correctionDao: TranslationCorrectionDao
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val geminiClient = client.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val deepLSemaphore = Semaphore(3)
    private val cascadeSemaphore = Semaphore(4)

    companion object {
        private const val TAG = "BabelRead_TL"
        private const val GEMINI_MODEL_TIMEOUT_MS = 30_000L
        private const val GEMINI_TOTAL_TIMEOUT_MS = 60_000L
        private val ELONGATED_DIALOGUE_REGEX = Regex("^(?:n[o]{2,}|o{2,}h+|a{2,}h+|h+m{2,}|s+h{2,})$")

        val latinRecognizer by lazy {
            try {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            } catch (e: Exception) {
                null
            }
        }
        val japaneseRecognizer by lazy {
            try {
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            } catch (e: Exception) {
                null
            }
        }
        val koreanRecognizer by lazy {
            try {
                TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            } catch (e: Exception) {
                null
            }
        }
        val chineseRecognizer by lazy {
            try {
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            } catch (e: Exception) {
                null
            }
        }
    }

    private val translatorCache = ConcurrentHashMap<String, Translator>()
    private val translatorMutexes = ConcurrentHashMap<String, Mutex>()
    // ML Kit TextRecognizer tasks do not expose cancellation. Keep a timed-out
    // task serialized until its completion callback releases this permit.
    private val recognizerTaskSemaphore = Semaphore(1)
    private val mlKitCompletionExecutor = Executor { command -> command.run() }
    // Tracks short-lived cache access and active/waiting per-language users.
    private val translatorLifecycleMutex = Mutex()
    private val translatorCloseMutex = Mutex()
    private val translatorCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var translatorCleanupInProgress = false
    private var activeTranslatorLeases = 0
    private var translatorsAvailable = CompletableDeferred(Unit)
    private var noActiveTranslatorLeases = CompletableDeferred<Unit>().apply { complete(Unit) }

    private data class TranslatorLease(val translator: Translator, val mutex: Mutex)

    /** Defers recycling an owned bitmap until every ML Kit task using it completes. */
    private class BitmapRecycleGuard(private val bitmap: Bitmap) {
        private val lock = Any()
        private var activeTasks = 0
        private var recycleRequested = false
        private var recycleClaimed = false

        private fun claimRecycleIfReady(): Boolean {
            if (recycleRequested && activeTasks == 0 && !recycleClaimed) {
                recycleClaimed = true
                return true
            }
            return false
        }

        fun retainForTask() {
            synchronized(lock) {
                check(!recycleRequested && !bitmap.isRecycled) { "Bitmap recycling already requested" }
                activeTasks++
            }
        }

        fun releaseFromTask() {
            val shouldRecycle = synchronized(lock) {
                check(activeTasks > 0) { "Bitmap task lease released more than once" }
                activeTasks--
                claimRecycleIfReady()
            }
            if (shouldRecycle && !bitmap.isRecycled) bitmap.recycle()
        }

        fun recycleWhenSafe() {
            val shouldRecycle = synchronized(lock) {
                recycleRequested = true
                claimRecycleIfReady()
            }
            if (shouldRecycle && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    private class GeminiHttpException(
        val statusCode: Int,
        message: String
    ) : IOException(message)

    private class GeminiBatchParseException(
        message: String,
        cause: Throwable? = null
    ) : IOException(message, cause)

    data class RectAndText(val rect: Rect, val text: String)

    /** OCR satırlarını bozmadan normalize eder. */
    fun cleanOcrArtifacts(rawText: String, originLang: String = "İngilizce"): String {
        if (rawText.isBlank()) return rawText
        var text = rawText.trim()
        val isCjkSource = originLang.equals("Japonca", ignoreCase = true) ||
                originLang.equals("Korece", ignoreCase = true) ||
                originLang.equals("Çince", ignoreCase = true)

        // Join only words split by a line-break hyphen (e.g. "trans-\\nlation").
        // Keep ordinary hyphens and short tokens such as "L is" intact.
        text = text.replace(
            Regex("([A-Za-zğüşıöçĞÜŞİÖÇ]+)-[ \\t]*\\r?\\n[ \\t]*([A-Za-zğüşıöçĞÜŞİÖÇ]+)"),
            "$1$2"
        )
        text = text.replace(Regex("[_~`|^]+"), " ")
        if (!isCjkSource) {
            text = text.replace(Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff\\uac00-\\ud7af]"), "")
        } else {
            text = text.replace(Regex("[\\p{Cntrl}&&[^\n\t]]"), "")
        }
        return text.replace("\\s+".toRegex(), " ").trim()
    }

    /**
     * SFX (ses efekti), çizim kalıntısı ve OCR çöp metinlerini eler.
     */
    fun isValidDialogueText(text: String, isBubble: Boolean): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false

        if (clean.none { it.isLetter() }) return false
        if (clean.matches(Regex("^(p\\.?\\s*)?\\d{1,4}$", RegexOption.IGNORE_CASE))) return false

        val shortDialogue = clean.trimEnd('.', ',', '!', '?', ' ').lowercase(Locale.ROOT)
        if (shortDialogue in setOf("ah", "eh", "oh", "ha", "hı", "hmm", "ne", "ya", "hey", "hayır", "evet", "no", "yes")) {
            return true
        }
        if (ELONGATED_DIALOGUE_REGEX.matches(shortDialogue)) return true

        if (clean.length == 1) {
            val ch = clean[0]
            val isCjk = (ch.code in 0x3040..0x9FAF) || (ch.code in 0xAC00..0xD7AF)
            return isCjk || clean.equals("I", ignoreCase = true) || clean.equals("A", ignoreCase = true) || clean.equals("O", ignoreCase = true)
        }

        val lower = clean.lowercase(Locale.ROOT)
        if (lower.contains("xxx") || lower.contains("xxxx")) return false
        val words = clean.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.isEmpty()) return false

        if (words.any { w ->
            val wLetters = w.filter { it.isLetter() }
            wLetters.length >= 2 && wLetters.all { it.lowercaseChar() == 'x' }
        }) {
            return false
        }
        val strippedLetters = clean.filter { it.isLetter() }
        if (strippedLetters.length >= 2 && strippedLetters.all { it.lowercaseChar() == 'x' }) {
            return false
        }

        val totalNonSpace = clean.count { !it.isWhitespace() }
        val letterRatio = clean.count { it.isLetter() }.toFloat() / totalNonSpace.toFloat()
        if (letterRatio < 0.35f) return false

        if (!isBubble && words.size == 1) {
            val w = words[0].filter { it.isLetter() }
            val isCjk = w.any { (it.code in 0x3040..0x9FAF) || (it.code in 0xAC00..0xD7AF) }
            if (!isCjk) {
                if (w.length <= 2 && !setOf("no", "hi", "oh", "ah", "ha", "ok", "go", "up", "me", "it", "so", "he", "we").contains(w.lowercase(Locale.ROOT))) {
                    return false
                }
                val commonDialogue = setOf("yes", "no", "what", "wait", "stop", "here", "look", "thanks", "hello", "goodbye", "please", "help")
                val hasSentencePunctuation = clean.contains(".") || clean.contains("?") || clean.contains("!")
                if (w.length in 3..10 && !commonDialogue.contains(w.lowercase(Locale.ROOT)) && !hasSentencePunctuation) {
                    return false
                }
            }
        }
        return true
    }

    /** Returns true for common unchanged reactions and likely proper-name tokens. */
    private fun isLikelyUntranslatableToken(text: String): Boolean {
        val clean = text.trim()
        val token = clean.trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT)
        if (token in setOf("ok", "okay", "ah", "oh", "eh", "hmm", "hm", "huh", "hah", "ha", "uh", "um", "mm")) {
            return true
        }
        if (ELONGATED_DIALOGUE_REGEX.matches(token)) return true

        val possibleName = clean.trim { !it.isLetterOrDigit() && it != '-' && it != '\'' && it != '’' }
        if (clean.any(Char::isWhitespace) || possibleName.length !in 3..40) return false

        val hyphenatedName = Regex("^[\\p{Lu}][\\p{L}\\p{M}'’]*(?:-[\\p{Lu}]?[\\p{L}\\p{M}'’]+)+$")
        if (hyphenatedName.matches(possibleName)) return true

        // A single capitalized word can also be an ordinary command or reaction.
        // Exclude common dialogue words before treating it as a likely name.
        val commonDialogueWords = setOf(
            "come", "enough", "fine", "go", "goodbye", "hello", "help", "here", "how",
            "leave", "listen", "look", "move", "never", "no", "okay", "please", "really",
            "right", "run", "sorry", "stay", "stop", "sure", "thanks", "there", "wait",
            "what", "when", "where", "who", "why", "wow", "yes"
        )
        return possibleName.matches(Regex("^[\\p{Lu}][\\p{L}\\p{M}'’]+$")) &&
            possibleName.lowercase(Locale.ROOT) !in commonDialogueWords
    }

    fun enhanceContrast(src: Bitmap): Bitmap {
        val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(0f)
        val contrast = 1.85f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val matrix = floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
        colorMatrix.postConcat(ColorMatrix(matrix))
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return bmp
    }

    fun invertAndEnhance(src: Bitmap): Bitmap {
        val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val contrast = 1.85f
        val translate = (0.5f * contrast + 0.5f) * 255f
        val matrix = floatArrayOf(
            -contrast, 0f, 0f, 0f, translate,
            0f, -contrast, 0f, 0f, translate,
            0f, 0f, -contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix))
        canvas.drawBitmap(src, 0f, 0f, paint)
        return bmp
    }

    fun detectRealLanguage(blocks: List<RectAndText>, defaultLanguage: String): String {
        val combinedText = blocks.joinToString(" ") { it.text }
        var hangulCount = 0
        var kanaCount = 0
        var hanziCount = 0
        var latinCount = 0
        for (ch in combinedText) {
            val code = ch.code
            when {
                (code in 0xAC00..0xD7AF) || (code in 0x1100..0x11FF) -> hangulCount++
                (code in 0x3040..0x309F) || (code in 0x30A0..0x30FF) -> kanaCount++
                (code in 0x4E00..0x9FAF) -> hanziCount++
                (ch in 'A'..'Z') || (ch in 'a'..'z') -> latinCount++
            }
        }
        return when {
            hangulCount >= 2 -> "Korece"
            kanaCount >= 2 -> "Japonca"
            hanziCount >= 3 && kanaCount == 0 && hangulCount == 0 && defaultLanguage.equals("Çince", ignoreCase = true) -> "Çince"
            (hanziCount + kanaCount) >= 3 -> "Japonca"
            latinCount > 15 && kanaCount == 0 && hangulCount == 0 && hanziCount == 0 -> {
                val code = TranslationLanguages.codeForDisplayName(defaultLanguage)
                if (code == "ja" || code == "ko" || code == "zh") "İngilizce" else defaultLanguage
            }
            else -> defaultLanguage
        }
    }

    suspend fun performOcr(bitmap: Bitmap, originLanguage: String): List<RectAndText> =
        performOcr(bitmap, originLanguage, applyPageMarginFilter = true)

    private suspend fun performOcr(
        bitmap: Bitmap,
        originLanguage: String,
        applyPageMarginFilter: Boolean,
        inputBitmapRecycleGuard: BitmapRecycleGuard? = null
    ): List<RectAndText> = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled || bitmap.width < 10 || bitmap.height < 10) {
            return@withContext emptyList()
        }
        val origW = bitmap.width
        val origH = bitmap.height
        val isJapanese = originLanguage.equals("Japonca", ignoreCase = true)
        val isKorean = originLanguage.equals("Korece", ignoreCase = true)
        val isChinese = originLanguage.equals("Çince", ignoreCase = true)
        val isRtl = isJapanese

        if (origH > origW * 2.2f && origH > 2400) {
            return@withContext performTiledOcr(bitmap, originLanguage)
        }

        val targetScale = if (origW < 1300) (1300f / origW).coerceIn(1.0f, 1.6f) else 1.0f
        var scanBitmap = bitmap
        var actualScale = 1.0f
        if (targetScale > 1.05f) {
            try {
                scanBitmap = Bitmap.createScaledBitmap(bitmap, (origW * targetScale).toInt(), (origH * targetScale).toInt(), true)
                actualScale = scanBitmap.width.toFloat() / origW.toFloat()
            } catch (_: OutOfMemoryError) {
                scanBitmap = bitmap
                actualScale = 1.0f
            }
        }
        val scanBitmapRecycleGuard = if (scanBitmap !== bitmap) {
            BitmapRecycleGuard(scanBitmap)
        } else {
            inputBitmapRecycleGuard
        }

        suspend fun scanWithRecognizer(
            scanBmp: Bitmap,
            recognizer: com.google.mlkit.vision.text.TextRecognizer,
            scaleFactor: Float,
            recycleGuard: BitmapRecycleGuard?
        ): List<RectAndText> {
            val image = try {
                InputImage.fromBitmap(scanBmp, 0)
            } catch (e: Exception) {
                return emptyList()
            }
            recognizerTaskSemaphore.acquire()
            val resourcesReleased = AtomicBoolean(false)
            var bitmapRetained = false
            var taskOwnsPermit = false
            fun releaseTaskResources() {
                if (resourcesReleased.compareAndSet(false, true)) {
                    if (bitmapRetained) recycleGuard?.releaseFromTask()
                    recognizerTaskSemaphore.release()
                }
            }
            return try {
                suspendCancellableCoroutine { continuation ->
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    recycleGuard?.retainForTask()
                    bitmapRetained = recycleGuard != null
                    val task = try {
                        recognizer.process(image)
                    } catch (e: Exception) {
                        releaseTaskResources()
                        Log.w(TAG, "Recognizer start failed: ${e.message}")
                        if (continuation.isActive) continuation.resume(emptyList())
                        return@suspendCancellableCoroutine
                    }
                    task.addOnCompleteListener(mlKitCompletionExecutor) {
                        releaseTaskResources()
                    }
                    // From here, the ML Kit task owns the permit even if the
                    // waiting coroutine times out or is cancelled.
                    taskOwnsPermit = true
                    task.addOnSuccessListener(mlKitCompletionExecutor) { visionText ->
                        if (!continuation.isActive) return@addOnSuccessListener
                        try {
                            val detected = mutableListOf<RectAndText>()
                            for (block in visionText.textBlocks) {
                                val box = block.boundingBox ?: continue
                                val rawText = block.text.trim()
                                if (rawText.isBlank()) continue
                                if (box.width().toLong() * box.height().toLong() > (scanBmp.width.toLong() * scanBmp.height.toLong() * 0.65)) continue
                                if (isJapanese && block.lines.size > 1) {
                                    for (line in block.lines) {
                                        val lBox = line.boundingBox ?: continue
                                        val lText = line.text.trim()
                                        if (lText.isBlank()) continue
                                        val origLeft = (lBox.left / scaleFactor).toInt().coerceIn(0, origW - 1)
                                        val origTop = (lBox.top / scaleFactor).toInt().coerceIn(0, origH - 1)
                                        val origRight = (lBox.right / scaleFactor).toInt().coerceIn(origLeft + 1, origW)
                                        val origBottom = (lBox.bottom / scaleFactor).toInt().coerceIn(origTop + 1, origH)
                                        detected.add(RectAndText(Rect(origLeft, origTop, origRight, origBottom), lText))
                                    }
                                } else {
                                    val origLeft = (box.left / scaleFactor).toInt().coerceIn(0, origW - 1)
                                    val origTop = (box.top / scaleFactor).toInt().coerceIn(0, origH - 1)
                                    val origRight = (box.right / scaleFactor).toInt().coerceIn(origLeft + 1, origW)
                                    val origBottom = (box.bottom / scaleFactor).toInt().coerceIn(origTop + 1, origH)
                                    val fullBlockText = if (block.lines.size > 1) {
                                        // Preserve line endings so cleanOcrArtifacts can repair
                                        // words split by a line-end hyphen before whitespace is flattened.
                                        block.lines.joinToString("\n") { it.text.trim() }
                                    } else {
                                        rawText
                                    }
                                    detected.add(RectAndText(Rect(origLeft, origTop, origRight, origBottom), fullBlockText))
                                }
                            }
                            continuation.resume(detected)
                        } catch (e: Exception) {
                            Log.w(TAG, "Recognizer result handling failed: ${e.message}")
                            if (continuation.isActive) continuation.resume(emptyList())
                        }
                    }
                    task.addOnFailureListener(mlKitCompletionExecutor) { e ->
                        if (continuation.isActive) {
                            Log.w(TAG, "Recognizer note: ${e.message}")
                            continuation.resume(emptyList())
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            } finally {
                if (!taskOwnsPermit) releaseTaskResources()
            }
        }

        val collectedBlocks = mutableListOf<RectAndText>()
        try {
            val primaryBlocks = when {
                isJapanese && japaneseRecognizer != null -> scanWithRecognizer(scanBitmap, japaneseRecognizer!!, actualScale, scanBitmapRecycleGuard)
                isKorean && koreanRecognizer != null -> scanWithRecognizer(scanBitmap, koreanRecognizer!!, actualScale, scanBitmapRecycleGuard)
                isChinese && chineseRecognizer != null -> scanWithRecognizer(scanBitmap, chineseRecognizer!!, actualScale, scanBitmapRecycleGuard)
                else -> emptyList()
            }
            collectedBlocks.addAll(primaryBlocks)

            if (latinRecognizer != null) {
                val hasRealCjk = collectedBlocks.any { b ->
                    b.text.any { ch -> (ch.code in 0x3040..0x9FAF) || (ch.code in 0xAC00..0xD7AF) }
                }
                val shouldScanLatin = collectedBlocks.isEmpty() || !hasRealCjk || (!isJapanese && !isKorean && !isChinese)
                if (shouldScanLatin) {
                    val latinBlocks = scanWithRecognizer(scanBitmap, latinRecognizer!!, actualScale, scanBitmapRecycleGuard)
                    for (lb in latinBlocks) {
                        val lbArea = (lb.rect.width().toLong() * lb.rect.height().toLong()).coerceAtLeast(1)
                        val alreadyCovered = collectedBlocks.any { exist ->
                            val interL = max(exist.rect.left, lb.rect.left)
                            val interT = max(exist.rect.top, lb.rect.top)
                            val interR = min(exist.rect.right, lb.rect.right)
                            val interB = min(exist.rect.bottom, lb.rect.bottom)
                            val interW = max(0, interR - interL)
                            val interH = max(0, interB - interT)
                            (interW.toLong() * interH.toLong()) > lbArea * 0.45f
                        }
                        if (!alreadyCovered) {
                            collectedBlocks.add(lb)
                        }
                    }
                }
            }

            val retryRecognizer = when {
                isJapanese -> japaneseRecognizer ?: latinRecognizer
                isKorean -> koreanRecognizer ?: latinRecognizer
                isChinese -> chineseRecognizer ?: latinRecognizer
                else -> latinRecognizer
            }
            if (collectedBlocks.isEmpty() && retryRecognizer != null) {
                var enhancedBmp: Bitmap? = null
                var enhancedBitmapRecycleGuard: BitmapRecycleGuard? = null
                try {
                    enhancedBmp = enhanceContrast(scanBitmap)
                    enhancedBitmapRecycleGuard = BitmapRecycleGuard(enhancedBmp)
                    val retryPass = scanWithRecognizer(enhancedBmp, retryRecognizer, actualScale, enhancedBitmapRecycleGuard)
                    collectedBlocks.addAll(retryPass)
                } finally {
                    enhancedBitmapRecycleGuard?.recycleWhenSafe()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "OCR pass error: ${e.message}")
        } finally {
            scanBitmapRecycleGuard?.recycleWhenSafe()
        }

        val cleanedBlocks = collectedBlocks.map {
            RectAndText(it.rect, cleanOcrArtifacts(it.text, originLanguage))
        }.filter { block ->
            val topThreshold = origH * 0.035f
            val bottomThreshold = origH * 0.965f
            val isMarginCredit = (block.rect.bottom < topThreshold) || (block.rect.top > bottomThreshold)
            !applyPageMarginFilter || !isMarginCredit
        }

        // Keep OCR regions separate until the reader has a chance to associate them
        // with the detector's actual bubble boxes. Geometry-only clustering here can
        // irreversibly join text from two neighboring balloons.
        val nonCollidingBlocks = resolveCollidingBlocks(cleanedBlocks)
        val rowHeight = max(30, (origH * 0.035f).toInt())
        val sortedBlocks = nonCollidingBlocks.sortedWith(Comparator { a, b ->
            val rowA = a.rect.top / rowHeight
            val rowB = b.rect.top / rowHeight
            if (rowA != rowB) {
                rowA.compareTo(rowB)
            } else {
                if (isRtl) b.rect.right.compareTo(a.rect.right) else a.rect.left.compareTo(b.rect.left)
            }
        })
        sortedBlocks
    }

    private suspend fun performTiledOcr(bitmap: Bitmap, originLanguage: String): List<RectAndText> {
        val origW = bitmap.width
        val origH = bitmap.height
        val tileH = 1400
        val overlap = 450
        val allResults = mutableListOf<RectAndText>()
        val step = tileH - overlap
        val lastStart = (origH - tileH).coerceAtLeast(0)
        val tileStarts = mutableListOf<Int>()
        var nextStart = 0
        while (nextStart < lastStart) {
            tileStarts.add(nextStart)
            nextStart += step
        }
        tileStarts.add(lastStart)
        if (tileStarts.size >= 3 && lastStart - tileStarts[tileStarts.lastIndex - 1] < step / 2) {
            tileStarts.removeAt(tileStarts.lastIndex - 1)
        }

        for (y in tileStarts) {
            val h = min(tileH, origH - y)
            val tile = try {
                Bitmap.createBitmap(bitmap, 0, y, origW, h)
            } catch (_: Exception) {
                null
            }
            if (tile != null) {
                val tileRecycleGuard = BitmapRecycleGuard(tile)
                try {
                    val tileBlocks = performOcr(
                        tile,
                        originLanguage,
                        applyPageMarginFilter = false,
                        inputBitmapRecycleGuard = tileRecycleGuard
                    )
                    for (b in tileBlocks) {
                        val shifted = Rect(b.rect.left, b.rect.top + y, b.rect.right, b.rect.bottom + y)
                        allResults.add(RectAndText(shifted, b.text))
                    }
                } finally {
                    tileRecycleGuard.recycleWhenSafe()
                }
            }
        }
        val topThreshold = origH * 0.035f
        val bottomThreshold = origH * 0.965f
        val deduplicated = resolveCollidingBlocks(allResults).filter { block ->
            block.rect.bottom >= topThreshold && block.rect.top <= bottomThreshold
        }
        // Tile overlap can produce duplicates, but defer paragraph grouping until the
        // caller can use bubble detections as a stronger grouping signal.
        val rowHeight = max(30, (origH * 0.035f).toInt())
        val isRtl = originLanguage.equals("Japonca", ignoreCase = true)
        return deduplicated.sortedWith(Comparator { a, b ->
            val rowA = a.rect.top / rowHeight
            val rowB = b.rect.top / rowHeight
            if (rowA != rowB) rowA.compareTo(rowB)
            else if (isRtl) b.rect.right.compareTo(a.rect.right)
            else a.rect.left.compareTo(b.rect.left)
        })
    }

    /**
     * Bölge OCR: tüm sayfayı taramak yerine yalnızca verilen (YOLO'nun bulduğu
     * balon/metin) bölgeleri kırpıp okur. Kırpma payı kenardaki yazıları
     * kurtarır; çakışan kırpmaların kopyaları sonda ayıklanır.
     */
    suspend fun performRegionOcr(
        bitmap: Bitmap,
        regions: List<Rect>,
        originLanguage: String
    ): List<RectAndText> = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled || regions.isEmpty()) return@withContext emptyList()
        val results = mutableListOf<RectAndText>()
        for (region in regions) {
            if (region.isEmpty) continue
            val marginX = max(12, (region.width() * 0.08f).toInt())
            val marginY = max(12, (region.height() * 0.08f).toInt())
            val left = (region.left - marginX).coerceAtLeast(0)
            val top = (region.top - marginY).coerceAtLeast(0)
            val right = (region.right + marginX).coerceAtMost(bitmap.width)
            val bottom = (region.bottom + marginY).coerceAtMost(bitmap.height)
            if (right - left < 8 || bottom - top < 8) continue
            val crop = try {
                Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            } catch (_: Exception) {
                null
            } ?: continue
            val cropGuard = BitmapRecycleGuard(crop)
            try {
                val blocks = performOcr(
                    crop,
                    originLanguage,
                    applyPageMarginFilter = false,
                    inputBitmapRecycleGuard = cropGuard
                )
                for (block in blocks) {
                    results.add(
                        RectAndText(
                            Rect(
                                block.rect.left + left,
                                block.rect.top + top,
                                block.rect.right + left,
                                block.rect.bottom + top
                            ),
                            block.text
                        )
                    )
                }
            } finally {
                cropGuard.recycleWhenSafe()
            }
        }
        resolveCollidingBlocks(results)
    }

    fun clusterAdjacentBlocks(
        blocks: List<RectAndText>,
        bitmap: Bitmap,
        detectedBubbles: List<Rect> = emptyList()
    ): List<RectAndText> {
        if (blocks.size <= 1) return blocks
        data class TextGroup(
            var block: RectAndText,
            // Keep the assigned bubble identity stable as the group's bounds grow;
            // an unassigned group adopts its sibling's identity on merge.
            var bubbleIndex: Int?,
            // Preserve column orientation; the merged envelope may no longer be narrow.
            val isVerticalCjkColumn: Boolean
        )

        fun isCjkText(text: String): Boolean = text.any {
            it.code in 0x3040..0x9FAF || it.code in 0xAC00..0xD7AF
        }

        fun isVerticalCjkColumn(block: RectAndText): Boolean =
            isCjkText(block.text) && block.rect.height() > block.rect.width() * 1.5f

        fun overlapFraction(rect: Rect, bubble: Rect): Float {
            val left = max(rect.left, bubble.left)
            val top = max(rect.top, bubble.top)
            val right = min(rect.right, bubble.right)
            val bottom = min(rect.bottom, bubble.bottom)
            val intersection = (right - left).coerceAtLeast(0).toLong() *
                (bottom - top).coerceAtLeast(0).toLong()
            val area = (rect.width().toLong() * rect.height()).coerceAtLeast(1L)
            return intersection.toFloat() / area
        }

        // Assign each OCR block once. A weak 12% overlap is not sufficient: a
        // block needs its center inside the bubble or at least half its area in it.
        fun assignedBubbleIndex(rect: Rect): Int? {
            if (detectedBubbles.isEmpty()) return null
            val scored = detectedBubbles.mapIndexedNotNull { index, rawBubble ->
                val bubble = Rect(rawBubble)
                if (bubble.isEmpty) return@mapIndexedNotNull null
                val centerInside = bubble.contains(rect.centerX(), rect.centerY())
                val coverage = overlapFraction(rect, bubble)
                if (!centerInside && coverage < 0.50f) return@mapIndexedNotNull null
                val score = (if (centerInside) 1f else 0f) + coverage
                Triple(index, score, bubble.width().toLong() * bubble.height())
            }.sortedWith(compareByDescending<Triple<Int, Float, Long>> { it.second }.thenBy { it.third })
            if (scored.isEmpty()) return null
            // Ambiguous assignment between nearby balloons is safer left unassigned.
            val first = scored[0]
            val second = scored.getOrNull(1)
            if (second != null && first.second - second.second < 0.15f) return null
            return first.first
        }

        fun groupEnvelopeAllowed(group: Rect, candidate: Rect, bubbleIndex: Int?): Boolean {
            val union = Rect(
                min(group.left, candidate.left).coerceAtLeast(0),
                min(group.top, candidate.top).coerceAtLeast(0),
                max(group.right, candidate.right).coerceAtMost(bitmap.width),
                max(group.bottom, candidate.bottom).coerceAtMost(bitmap.height)
            )
            val parent = bubbleIndex?.let { detectedBubbles.getOrNull(it) }
            val maxWidth = parent?.width()?.times(1.10f) ?: bitmap.width * 0.35f
            val maxHeight = parent?.height()?.times(1.10f) ?: bitmap.height * 0.25f
            if (union.isEmpty || union.width() > maxWidth || union.height() > maxHeight) return false
            if (parent != null) {
                // The evolving group must remain associated with its original bubble.
                if (overlapFraction(union, parent) < 0.50f) return false
            }
            return true
        }

        val groups = blocks
            .filter { !it.rect.isEmpty && it.text.isNotBlank() }
            .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
            .map {
                TextGroup(
                    RectAndText(Rect(it.rect), it.text),
                    assignedBubbleIndex(it.rect),
                    isVerticalCjkColumn(it)
                )
            }
            .toMutableList()

        var index = 0
        while (index < groups.size) {
            val current = groups[index]
            var mergedIntoCurrent: Boolean
            do {
                mergedIntoCurrent = false
                var candidateIndex = index + 1
                while (candidateIndex < groups.size) {
                    val candidate = groups[candidateIndex]
                    // When only one side has a stable balloon identity, use the
                    // assigned sibling's balloon as the parent: a fragment that
                    // clearly sits inside that balloon still belongs to the same
                    // speech bubble and must not stay separate.
                    val effectiveBubbleIndex = current.bubbleIndex ?: candidate.bubbleIndex
                    val sameBubble = when {
                        detectedBubbles.isEmpty() -> true
                        current.bubbleIndex == null || candidate.bubbleIndex == null -> true
                        else -> current.bubbleIndex == candidate.bubbleIndex
                    }
                    val unionAllowed = groupEnvelopeAllowed(
                        current.block.rect,
                        candidate.block.rect,
                        effectiveBubbleIndex
                    )
                    if (sameBubble && unionAllowed && areInSameSpeechBubble(
                            current.block,
                            candidate.block,
                            bitmap,
                            effectiveBubbleIndex?.let { detectedBubbles.getOrNull(it) }
                        )
                    ) {
                        val unionRect = Rect(
                            min(current.block.rect.left, candidate.block.rect.left).coerceAtLeast(0),
                            min(current.block.rect.top, candidate.block.rect.top).coerceAtLeast(0),
                            max(current.block.rect.right, candidate.block.rect.right).coerceAtMost(bitmap.width),
                            max(current.block.rect.bottom, candidate.block.rect.bottom).coerceAtMost(bitmap.height)
                        )
                        val vDist = abs(current.block.rect.top - candidate.block.rect.top)
                        val hDist = abs(current.block.rect.left - candidate.block.rect.left)
                        val sideBySideColumns = current.isVerticalCjkColumn &&
                            candidate.isVerticalCjkColumn && hDist > vDist
                        val currentFirst = if (sideBySideColumns) {
                            current.block.rect.right >= candidate.block.rect.right
                        } else {
                            current.block.rect.top <= candidate.block.rect.top
                        }
                        current.block = RectAndText(
                            unionRect,
                            joinTextFragments(current.block.text, candidate.block.text, currentFirst)
                        )
                        if (current.bubbleIndex == null && candidate.bubbleIndex != null) {
                            current.bubbleIndex = candidate.bubbleIndex
                        }
                        groups.removeAt(candidateIndex)
                        mergedIntoCurrent = true
                        // Restart from the first remaining group because the enlarged
                        // bounds may now be adjacent to a previously rejected fragment.
                        break
                    }
                    candidateIndex++
                }
            } while (mergedIntoCurrent)
            index++
        }
        return groups.map { it.block }
    }

    fun joinTextFragments(t1: String, t2: String, isFirstBeforeSecond: Boolean): String {
        val first = if (isFirstBeforeSecond) t1.trim() else t2.trim()
        val second = if (isFirstBeforeSecond) t2.trim() else t1.trim()
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first
        if (first.equals(second, ignoreCase = true)) return first
        return if (first.endsWith("-") && first.length > 1 && first[first.length - 2].isLetter()) {
            first.dropLast(1) + second
        } else {
            "$first $second"
        }.replace("\\s+".toRegex(), " ")
    }

    fun areInSameSpeechBubble(
        b1: RectAndText,
        b2: RectAndText,
        bitmap: Bitmap,
        parentBubble: Rect? = null
    ): Boolean {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
        if (b1.text.isBlank() || b2.text.isBlank()) return false

        val imageBounds = Rect(0, 0, bitmap.width, bitmap.height)
        val a = Rect(b1.rect)
        val b = Rect(b2.rect)
        if (a.isEmpty || b.isEmpty || !a.intersect(imageBounds) || !b.intersect(imageBounds)) return false

        val w1 = a.width().toFloat()
        val h1 = a.height().toFloat()
        val w2 = b.width().toFloat()
        val h2 = b.height().toFloat()
        // Compare text scale on the axis shared by the candidate orientation.
        // A narrow punctuation box must not be rejected just because its width
        // is smaller than the height of neighboring horizontal text.
        val horizontalScaleRatio = min(h1, h2) / max(h1, h2)
        val verticalScaleRatio = min(w1, w2) / max(w1, w2)

        val shortSide = min(bitmap.width, bitmap.height).toFloat()
        val scale = max(0.5f, shortSide / 1000f)
        val horizontalMaxGap = max(4f * scale, min(((h1 + h2) / 2f) * 1.45f, 40f * scale))
        val verticalMaxGap = max(4f * scale, min(((w1 + w2) / 2f) * 1.45f, 40f * scale))

        val overlapX = (min(a.right, b.right) - max(a.left, b.left)).toFloat()
        val overlapY = (min(a.bottom, b.bottom) - max(a.top, b.top)).toFloat()
        val gapX = max(0f, -overlapX)
        val gapY = max(0f, -overlapY)
        val validParentBubble = parentBubble?.takeUnless { it.isEmpty }
        if (validParentBubble != null) {
            fun belongsToParent(rect: Rect): Boolean {
                if (validParentBubble.contains(rect.centerX(), rect.centerY())) return true
                val left = max(rect.left, validParentBubble.left)
                val top = max(rect.top, validParentBubble.top)
                val right = min(rect.right, validParentBubble.right)
                val bottom = min(rect.bottom, validParentBubble.bottom)
                val intersection = (right - left).coerceAtLeast(0).toLong() *
                    (bottom - top).coerceAtLeast(0).toLong()
                val area = (rect.width().toLong() * rect.height()).coerceAtLeast(1L)
                return intersection.toFloat() / area >= 0.50f
            }
            if (!belongsToParent(a) || !belongsToParent(b)) return false

            // OCR often returns the upper and lower lines of one speech balloon
            // as separate blocks with a large vertical gap. A stable parent bubble
            // identity is stronger evidence than the generic neighbor-gap heuristic:
            // allow those lines to regroup while keeping the evolving envelope within
            // that same balloon and retaining the divider check for adjacent balloons.
            val unionWidth = max(a.right, b.right) - min(a.left, b.left)
            val unionHeight = max(a.bottom, b.bottom) - min(a.top, b.top)
            if (unionWidth > validParentBubble.width() * 1.10f ||
                unionHeight > validParentBubble.height() * 1.10f
            ) return false
            return !hasBalloonDividerBetween(a, b, bitmap, scale)
        }

        val overlapRatioX = max(0f, overlapX) / min(w1, w2)
        val overlapRatioY = max(0f, overlapY) / min(h1, h2)
        val alignedOnRow = overlapRatioY >= 0.35f ||
            abs(a.exactCenterY() - b.exactCenterY()) <= min(h1, h2) * 0.25f
        val alignedOnColumn = overlapRatioX >= 0.35f ||
            abs(a.exactCenterX() - b.exactCenterX()) <= min(w1, w2) * 0.25f

        // Horizontal text fragments share a row; vertical text fragments share a column.
        // This is deliberately symmetric and remains a geometric hint, not proof of a bubble.
        val geometricallyAdjacent = (horizontalScaleRatio >= 0.50f &&
            alignedOnRow && gapX <= horizontalMaxGap) ||
            (verticalScaleRatio >= 0.50f && alignedOnColumn && gapY <= verticalMaxGap)
        if (!geometricallyAdjacent) return false

        // A visible ink-like stroke across the corridor often marks the outline
        // between neighboring balloons. Sample only the gap, not the OCR text.
        return !hasBalloonDividerBetween(a, b, bitmap, scale)
    }

    private fun hasBalloonDividerBetween(a: Rect, b: Rect, bitmap: Bitmap, scale: Float): Boolean {
        val horizontalGap = max(0, max(a.left, b.left) - min(a.right, b.right))
        val verticalGap = max(0, max(a.top, b.top) - min(a.bottom, b.bottom))
        if (horizontalGap == 0 && verticalGap == 0) return false

        val sampleCount = 9
        val extremeCountNeeded = 4
        val lumas = FloatArray(sampleCount)
        val horizontalSeparation = horizontalGap > 0 &&
            (verticalGap == 0 || horizontalGap <= verticalGap)

        if (horizontalSeparation) {
            val x = ((max(a.left, b.left) + min(a.right, b.right)) / 2)
                .coerceIn(0, bitmap.width - 1)
            val overlapTop = max(a.top, b.top)
            val overlapBottom = min(a.bottom, b.bottom)
            val centerY = (a.exactCenterY() + b.exactCenterY()) / 2f
            val halfSpan = if (overlapBottom > overlapTop) {
                (overlapBottom - overlapTop) / 2f
            } else {
                min(a.height(), b.height()) * 0.20f
            }
            for (i in 0 until sampleCount) {
                val fraction = if (sampleCount == 1) 0.5f else i / (sampleCount - 1f)
                val y = (centerY - halfSpan + 2f * halfSpan * fraction)
                    .toInt().coerceIn(0, bitmap.height - 1)
                lumas[i] = pixelLuma(bitmap.getPixel(x, y))
            }
        } else {
            val y = ((max(a.top, b.top) + min(a.bottom, b.bottom)) / 2)
                .coerceIn(0, bitmap.height - 1)
            val overlapLeft = max(a.left, b.left)
            val overlapRight = min(a.right, b.right)
            val centerX = (a.exactCenterX() + b.exactCenterX()) / 2f
            val halfSpan = if (overlapRight > overlapLeft) {
                (overlapRight - overlapLeft) / 2f
            } else {
                min(a.width(), b.width()) * 0.20f
            }
            for (i in 0 until sampleCount) {
                val fraction = if (sampleCount == 1) 0.5f else i / (sampleCount - 1f)
                val x = (centerX - halfSpan + 2f * halfSpan * fraction)
                    .toInt().coerceIn(0, bitmap.width - 1)
                lumas[i] = pixelLuma(bitmap.getPixel(x, y))
            }
        }

        // A divider is a stroke that contrasts with the local background: dark
        // ink on light paper, but light ink inside a dark balloon. Absolute
        // darkness alone made every gap in a dark bubble look like a divider
        // and blocked legitimate merges.
        val sortedLumas = lumas.sortedArray()
        val backgroundIsDark = sortedLumas[sampleCount / 2] <= 96f
        val strokePixels = if (backgroundIsDark) {
            lumas.count { it >= 190f }
        } else {
            lumas.count { it <= 65f }
        }
        val minGapForDivider = max(2f, 1.5f * scale)
        return (horizontalSeparation && horizontalGap >= minGapForDivider.toInt() && strokePixels >= extremeCountNeeded) ||
            (!horizontalSeparation && verticalGap >= minGapForDivider.toInt() && strokePixels >= extremeCountNeeded)
    }

    private fun pixelLuma(pixel: Int): Float =
        Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f + Color.blue(pixel) * 0.114f

    fun resolveCollidingBlocks(blocks: List<RectAndText>): List<RectAndText> {
        if (blocks.size <= 1) return blocks
        val sorted = blocks.sortedByDescending { it.rect.width().toLong() * it.rect.height().toLong() }.toMutableList()
        val accepted = mutableListOf<RectAndText>()

        fun textQuality(text: String): Int =
            text.count { it.isLetter() } * 2 +
                text.count { it.isWhitespace() } -
                text.count { it.isDigit() } * 3 -
                text.count { it == '\uFFFD' } * 5

        for (candidate in sorted) {
            val candText = candidate.text.trim()
            var mergedWithExisting = false
            for (i in accepted.indices) {
                val existing = accepted[i]
                val existText = existing.text.trim()
                val interLeft = max(existing.rect.left, candidate.rect.left)
                val interTop = max(existing.rect.top, candidate.rect.top)
                val interRight = min(existing.rect.right, candidate.rect.right)
                val interBottom = min(existing.rect.bottom, candidate.rect.bottom)
                val interW = max(0, interRight - interLeft)
                val interH = max(0, interBottom - interTop)
                val interArea = interW.toLong() * interH.toLong()
                val candArea = candidate.rect.width().toLong() * candidate.rect.height().toLong()
                val existArea = existing.rect.width().toLong() * existing.rect.height().toLong()
                val minArea = min(candArea, existArea)
                val overlapRatio = if (minArea > 0) interArea.toFloat() / minArea else 0f
                val gapX = max(0, max(existing.rect.left, candidate.rect.left) - min(existing.rect.right, candidate.rect.right))
                val gapY = max(0, max(existing.rect.top, candidate.rect.top) - min(existing.rect.bottom, candidate.rect.bottom))
                val closeCenters = abs(existing.rect.exactCenterX() - candidate.rect.exactCenterX()) <=
                    max(existing.rect.width(), candidate.rect.width()) * 0.75f &&
                    abs(existing.rect.exactCenterY() - candidate.rect.exactCenterY()) <=
                    max(existing.rect.height(), candidate.rect.height()) * 0.75f
                val closeBoxes = overlapRatio > 0f ||
                    (closeCenters && gapX <= max(8, min(existing.rect.width(), candidate.rect.width()) / 2) &&
                        gapY <= max(8, min(existing.rect.height(), candidate.rect.height()) / 2))
                val hasUsefulText = min(candText.length, existText.length) >= 3
                val textMatch = closeBoxes && hasUsefulText && (
                    candText.equals(existText, ignoreCase = true) ||
                        (candText.length > 3 && existText.contains(candText, ignoreCase = true)) ||
                        (existText.length > 3 && candText.contains(existText, ignoreCase = true))
                    )

                if (textMatch || overlapRatio > 0.45f) {
                    val mergedRect = Rect(
                        min(existing.rect.left, candidate.rect.left),
                        min(existing.rect.top, candidate.rect.top),
                        max(existing.rect.right, candidate.rect.right),
                        max(existing.rect.bottom, candidate.rect.bottom)
                    )
                    val mergedText = when {
                        textMatch && candText.length <= existText.length -> existText
                        textMatch -> candText
                        // Strongly overlapping boxes are duplicate OCR hypotheses, not
                        // adjacent fragments. Keep the more readable hypothesis instead
                        // of concatenating both and duplicating the same words.
                        textQuality(candText) > textQuality(existText) -> candText
                        else -> existText
                    }
                    accepted[i] = RectAndText(mergedRect, mergedText)
                    mergedWithExisting = true
                    break
                }
            }
            if (!mergedWithExisting) {
                accepted.add(candidate)
            }
        }
        return accepted
    }

    fun analyzeBubble(bitmap: Bitmap, rect: Rect): BubbleAnalysis {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0 || rect.width() <= 0 || rect.height() <= 0) {
                return BubbleAnalysis(isBubble = true, isDarkBubble = false)
            }
            val padX = (rect.width() * 0.15f).toInt().coerceIn(4, 25)
            val padY = (rect.height() * 0.15f).toInt().coerceIn(4, 25)
            val left = (rect.left - padX).coerceIn(0, width - 1)
            val top = (rect.top - padY).coerceIn(0, height - 1)
            val right = (rect.right + padX).coerceIn(0, width - 1)
            val bottom = (rect.bottom + padY).coerceIn(0, height - 1)

            val sampledLums = mutableListOf<Float>()
            val stepX = max(2, (right - left) / 10)
            val stepY = max(2, (bottom - top) / 10)
            for (y in top..bottom step stepY) {
                for (x in left..right step stepX) {
                    val px = bitmap.getPixel(x, y)
                    val lum = (Color.red(px) * 0.299f + Color.green(px) * 0.587f + Color.blue(px) * 0.114f)
                    sampledLums.add(lum)
                }
            }
            if (sampledLums.isEmpty()) {
                return BubbleAnalysis(isBubble = true, isDarkBubble = false)
            }
            sampledLums.sort()
            val bgStartIndex = (sampledLums.size * 0.35f).toInt().coerceIn(0, sampledLums.size - 1)
            val medianLum = sampledLums[sampledLums.size / 2]
            val darkBackgroundLikely = medianLum < 75f
            val bgEndIndex = (sampledLums.size * 0.65f).toInt().coerceIn(1, sampledLums.size)
            // Use the darker population for dark balloons so white lettering does not
            // dominate the background estimate. For light balloons, ignore dark ink.
            val bgLums = if (darkBackgroundLikely) {
                sampledLums.subList(0, bgEndIndex)
            } else {
                sampledLums.subList(bgStartIndex, sampledLums.size)
            }
            val avgBgLum = bgLums.average().toFloat()
            val isDark = avgBgLum < 75f
            // Keep a small gray ambiguity band (75..85) between light and dark fills.
            val isBubble = avgBgLum >= 85f || isDark
            BubbleAnalysis(isBubble = isBubble, isDarkBubble = isDark)
        } catch (_: Exception) {
            BubbleAnalysis(isBubble = true, isDarkBubble = false)
        }
    }

    fun refineBubbleBounds(bitmap: Bitmap, textRect: Rect, isDark: Boolean = false): Rect {
        if (bitmap.isRecycled || textRect.width() <= 0 || textRect.height() <= 0) return textRect
        val width = bitmap.width
        val height = bitmap.height
        val maxExpandX = (textRect.width() * 0.35f).toInt().coerceIn(6, 36)
        val maxExpandY = (textRect.height() * 0.30f).toInt().coerceIn(6, 26)

        fun isBorderPixel(px: Int): Boolean {
            val lum = (Color.red(px) * 0.299 + Color.green(px) * 0.587 + Color.blue(px) * 0.114)
            return if (isDark) lum > 140 else lum < 100
        }

        val centerY = (textRect.top + textRect.bottom) / 2
        val centerX = (textRect.left + textRect.right) / 2

        var foundBorderLeft = false
        var expLeft = textRect.left
        for (d in 1..maxExpandX) {
            val x = textRect.left - d
            if (x <= 1) break
            val p1 = bitmap.getPixel(x, (textRect.top + centerY) / 2)
            val p2 = bitmap.getPixel(x, centerY)
            val p3 = bitmap.getPixel(x, (textRect.bottom + centerY) / 2)
            if (isBorderPixel(p1) || isBorderPixel(p2) || isBorderPixel(p3)) {
                foundBorderLeft = true
                break
            }
            expLeft = x
        }

        var foundBorderRight = false
        var expRight = textRect.right
        for (d in 1..maxExpandX) {
            val x = textRect.right + d
            if (x >= width - 2) break
            val p1 = bitmap.getPixel(x, (textRect.top + centerY) / 2)
            val p2 = bitmap.getPixel(x, centerY)
            val p3 = bitmap.getPixel(x, (textRect.bottom + centerY) / 2)
            if (isBorderPixel(p1) || isBorderPixel(p2) || isBorderPixel(p3)) {
                foundBorderRight = true
                break
            }
            expRight = x
        }

        var foundBorderTop = false
        var expTop = textRect.top
        for (d in 1..maxExpandY) {
            val y = textRect.top - d
            if (y <= 1) break
            val p1 = bitmap.getPixel((textRect.left + centerX) / 2, y)
            val p2 = bitmap.getPixel(centerX, y)
            val p3 = bitmap.getPixel((textRect.right + centerX) / 2, y)
            if (isBorderPixel(p1) || isBorderPixel(p2) || isBorderPixel(p3)) {
                foundBorderTop = true
                break
            }
            expTop = y
        }

        var foundBorderBottom = false
        var expBottom = textRect.bottom
        for (d in 1..maxExpandY) {
            val y = textRect.bottom + d
            if (y >= height - 2) break
            val p1 = bitmap.getPixel((textRect.left + centerX) / 2, y)
            val p2 = bitmap.getPixel(centerX, y)
            val p3 = bitmap.getPixel((textRect.right + centerX) / 2, y)
            if (isBorderPixel(p1) || isBorderPixel(p2) || isBorderPixel(p3)) {
                foundBorderBottom = true
                break
            }
            expBottom = y
        }

        val bordersFoundCount = (if (foundBorderLeft) 1 else 0) + (if (foundBorderRight) 1 else 0) +
                (if (foundBorderTop) 1 else 0) + (if (foundBorderBottom) 1 else 0)
        if (bordersFoundCount < 2) {
            return textRect
        }

        val finalLeft = if (foundBorderLeft) expLeft else textRect.left
        val finalRight = if (foundBorderRight) expRight else textRect.right
        val finalTop = if (foundBorderTop) expTop else textRect.top
        val finalBottom = if (foundBorderBottom) expBottom else textRect.bottom

        val safeLeft = (finalLeft + 2).coerceAtMost(textRect.left)
        val safeRight = (finalRight - 2).coerceAtLeast(textRect.right)
        val safeTop = (finalTop + 2).coerceAtMost(textRect.top)
        val safeBottom = (finalBottom - 2).coerceAtLeast(textRect.bottom)

        return Rect(safeLeft, safeTop, safeRight, safeBottom)
    }

    data class MaskedTextResult(
        val bitmap: Bitmap,
        val paintedRects: List<Rect>,
        val skippedAuxBoxes: List<Rect>
    )

    private fun textReachOf(block: TextBlockObj): RectF {
        val reach = Rect(block.boundingBox).apply { union(block.layoutBox) }
        val reachPadX = max(18f, reach.width() * 0.15f)
        val reachPadY = max(14f, reach.height() * 0.15f)
        return RectF(
            reach.left - reachPadX,
            reach.top - reachPadY,
            reach.right + reachPadX,
            reach.bottom + reachPadY
        )
    }

    fun maskOriginalTextOnBitmap(
        sourceBitmap: Bitmap,
        blocks: List<TextBlockObj>,
        auxBoxes: List<Rect> = emptyList(),
        protectedBlocks: List<TextBlockObj> = emptyList()
    ): MaskedTextResult {
        if (blocks.isEmpty() || sourceBitmap.isRecycled) {
            return MaskedTextResult(sourceBitmap, emptyList(), emptyList())
        }
        val resultBitmap = try {
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        } catch (t: Throwable) {
            return MaskedTextResult(sourceBitmap, emptyList(), emptyList())
        }
        val paintedRects = mutableListOf<Rect>()
        val skippedAuxBoxes = mutableListOf<Rect>()
        val canvas = Canvas(resultBitmap)
        for (block in blocks) {
            if (!block.isBubble) continue
            val textRect = block.boundingBox
            val bubbleBox = block.bubbleBox
            val maskColor = if (block.isDarkBubble) {
                Color.rgb(18, 18, 18)
            } else if (block.backgroundColor != null) {
                val c = block.backgroundColor
                val lum = (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114)
                if (lum > 215) Color.WHITE else c
            } else {
                Color.WHITE
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = maskColor
                style = Paint.Style.FILL
            }
            val padX = max(18f, textRect.width() * 0.15f)
            val padY = max(14f, textRect.height() * 0.15f)

            // An unchanged bubbleBox is the TextBlockObj default/fallback, not a
            // reliable speech-bubble boundary. In that case, allow the mask padding
            // to extend beyond the OCR rectangle while staying inside the bitmap.
            val hasDistinctBubbleBounds = bubbleBox != textRect
            val minX = if (hasDistinctBubbleBounds) max(bubbleBox.left.toFloat() + 4f, 0f) else 0f
            val maxX = if (hasDistinctBubbleBounds) {
                min(bubbleBox.right.toFloat() - 4f, sourceBitmap.width.toFloat())
            } else sourceBitmap.width.toFloat()
            val minY = if (hasDistinctBubbleBounds) max(bubbleBox.top.toFloat() + 4f, 0f) else 0f
            val maxY = if (hasDistinctBubbleBounds) {
                min(bubbleBox.bottom.toFloat() - 4f, sourceBitmap.height.toFloat())
            } else sourceBitmap.height.toFloat()

            val leftBound = min(minX, textRect.left.toFloat())
            val rightBound = max(maxX, textRect.right.toFloat())
            val topBound = min(minY, textRect.top.toFloat())
            val bottomBound = max(maxY, textRect.bottom.toFloat())

            val maskLeft = max(leftBound, textRect.left.toFloat() - padX)
            val maskRight = min(rightBound, textRect.right.toFloat() + padX)
            val maskTop = max(topBound, textRect.top.toFloat() - padY)
            val maskBottom = min(bottomBound, textRect.bottom.toFloat() + padY)

            if (maskRight > maskLeft && maskBottom > maskTop) {
                val maskRect = RectF(maskLeft, maskTop, maskRight, maskBottom)
                val cornerRadius = (min(maskRect.width(), maskRect.height()) * 0.35f).coerceIn(4f, 24f)
                canvas.drawRoundRect(maskRect, cornerRadius, cornerRadius, paint)
                val paintedRect = Rect()
                maskRect.round(paintedRect)
                paintedRects.add(paintedRect)
            }

            // Balon kutusu balonun tamamını kapsamıyorsa balon içinde kalan
            // orijinal harf parçaları (ör. büyük stilize kelimelerin kesilen
            // sonları) ekranda kalıyordu. Metnin çevresine ya da balona değen
            // ham text_free aday kutularını da temizleyerek bunları kaldır.
            if (auxBoxes.isNotEmpty()) {
                val textReach = RectF(
                    textRect.left - padX,
                    textRect.top - padY,
                    textRect.right + padX,
                    textRect.bottom + padY
                )
                // Komşu balonların yazı alanları: bir aux kutusu bunların
                // üstüne taşıyorsa maskeleme atlanır, aksi halde opak zemin
                // komşu balonun yazısını kapatır. Çevirisi başarısız blokların
                // orijinal metni görünür kalacağı için bu alanlar ayrıca korunur.
                val otherTextReaches = (blocks + protectedBlocks)
                    .filter { it !== block && it.isBubble }
                    .map { textReachOf(it) }
                val protectedReaches = protectedBlocks
                    .filter { it.isBubble }
                    .map { textReachOf(it) }
                for (aux in auxBoxes) {
                    if (aux.isEmpty) continue
                    val auxRect = RectF(aux)
                    val hitsText = RectF().setIntersect(auxRect, textReach)
                    val hitsBubble = RectF().setIntersect(auxRect, RectF(bubbleBox))
                    if (!hitsText && !hitsBubble) continue
                    val growX = max(10f, auxRect.width() * 0.10f)
                    val growY = max(10f, auxRect.height() * 0.10f)
                    val grown = RectF(
                        (auxRect.left - growX).coerceAtLeast(0f),
                        (auxRect.top - growY).coerceAtLeast(0f),
                        (auxRect.right + growX).coerceAtMost(sourceBitmap.width.toFloat()),
                        (auxRect.bottom + growY).coerceAtMost(sourceBitmap.height.toFloat())
                    )
                    if (grown.width() <= 0f || grown.height() <= 0f) continue
                    val grownArea = (grown.width() * grown.height()).coerceAtLeast(1f)
                    var coversProtectedText = false
                    for (other in protectedReaches) {
                        val inter = RectF()
                        if (inter.setIntersect(grown, other) &&
                            inter.width() * inter.height() > grownArea * 0.25f
                        ) {
                            coversProtectedText = true
                            break
                        }
                    }
                    if (coversProtectedText) continue
                    var coversOtherText = false
                    for (other in otherTextReaches) {
                        val inter = RectF()
                        if (inter.setIntersect(grown, other) &&
                            inter.width() * inter.height() > grownArea * 0.25f
                        ) {
                            coversOtherText = true
                            break
                        }
                    }
                    if (coversOtherText) {
                        // Çevrilmiş komşunun üstü düz renkle kapatılmaz; bölge
                        // yapay zekâ arka plan onarımına bırakılır.
                        val skipped = Rect()
                        grown.round(skipped)
                        skippedAuxBoxes.add(skipped)
                        continue
                    }
                    val auxRadius = (min(grown.width(), grown.height()) * 0.35f).coerceIn(4f, 24f)
                    canvas.drawRoundRect(grown, auxRadius, auxRadius, paint)
                    val paintedAux = Rect()
                    grown.round(paintedAux)
                    paintedRects.add(paintedAux)
                }
            }
        }
        return MaskedTextResult(resultBitmap, paintedRects, skippedAuxBoxes)
    }

    fun sampleSpeechBubbleColor(bitmap: Bitmap, rect: Rect, isDarkBubble: Boolean): Int {
        val width = bitmap.width
        val height = bitmap.height
        val sampledColors = mutableListOf<Int>()
        val offsets = listOf(3, 6, 9)

        for (dist in offsets) {
            val left = (rect.left - dist).coerceIn(0, width - 1)
            val top = (rect.top - dist).coerceIn(0, height - 1)
            val right = (rect.right + dist).coerceIn(0, width - 1)
            val bottom = (rect.bottom + dist).coerceIn(0, height - 1)

            val stepX = max(2, (right - left) / 8)
            for (x in left..right step stepX) {
                sampledColors.add(bitmap.getPixel(x, top))
                sampledColors.add(bitmap.getPixel(x, bottom))
            }
            val stepY = max(2, (bottom - top) / 8)
            for (y in top..bottom step stepY) {
                sampledColors.add(bitmap.getPixel(left, y))
                sampledColors.add(bitmap.getPixel(right, y))
            }
        }

        if (sampledColors.isEmpty()) {
            return if (isDarkBubble) Color.rgb(18, 18, 18) else Color.WHITE
        }

        val filtered = sampledColors.filter { c ->
            val lum = (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114)
            if (isDarkBubble) lum < 80 else lum > 165
        }
        val pool = if (filtered.isNotEmpty()) filtered else sampledColors
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (c in pool) {
            sumR += Color.red(c)
            sumG += Color.green(c)
            sumB += Color.blue(c)
        }
        val count = pool.size
        val avgR = (sumR / count).toInt()
        val avgG = (sumG / count).toInt()
        val avgB = (sumB / count).toInt()
        val avgLum = (avgR * 0.299 + avgG * 0.587 + avgB * 0.114)

        return if (!isDarkBubble) {
            if (avgLum > 240) Color.WHITE else Color.rgb(avgR, avgG, avgB)
        } else {
            if (avgLum < 30) Color.rgb(18, 18, 18) else Color.rgb(avgR, avgG, avgB)
        }
    }

    suspend fun translateWithMlKit(text: String, originLanguage: String, targetLangCode: String): String =
        withContext(Dispatchers.IO) {
            translateOffline(text, originLanguage, targetLangCode)
        }

    suspend fun translateWithDeepL(
        text: String,
        apiKey: String,
        originLang: String,
        targetLangCode: String
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext ""
        }
        val targetLang = TranslationLanguages.deeplTarget(targetLangCode) ?: return@withContext ""
        deepLSemaphore.withPermit {
            val isFreeTier = cleanKey.endsWith(":fx", ignoreCase = true)
            val endpoint = if (isFreeTier) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
            val sourceLangCode = TranslationLanguages.deeplTarget(
                TranslationLanguages.codeForDisplayName(originLang) ?: ""
            )?.substringBefore('-')
            try {
                val formBuilder = okhttp3.FormBody.Builder()
                    .add("text", text)
                    .add("target_lang", targetLang)
                if (sourceLangCode != null) {
                    formBuilder.add("source_lang", sourceLangCode)
                }
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "DeepL-Auth-Key $cleanKey")
                    .post(formBuilder.build())
                    .build()
                executeCancellable(request).use { response ->
                    if (!response.isSuccessful) return@use ""
                    val body = response.body?.string() ?: return@use ""
                    val json = JSONObject(body)
                    val translations = json.getJSONArray("translations")
                    if (translations.length() > 0) {
                        translations.getJSONObject(0).getString("text")
                    } else {
                        ""
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
        }
    }

    suspend fun translateBlocks(
        requests: List<TranslationRequest>,
        engine: String,
        userGeminiApiKey: String,
        userDeepLApiKey: String = "",
        originLang: String,
        targetLangCode: String,
        turkishConvertEnabled: Boolean,
        geminiModel: String = "gemini-flash-latest"
    ): TranslationOutcome = withContext(Dispatchers.IO) {
        if (requests.isEmpty()) return@withContext TranslationOutcome(emptyList())
        val results = arrayOfNulls<TranslationResult>(requests.size)
        val indicesToTranslate = mutableListOf<Int>()
        val convertTurkishChars = turkishConvertEnabled && targetLangCode == "tr"
        val targetEnglishName = TranslationLanguages.englishName(targetLangCode) ?: "English"
        for (i in requests.indices) {
            val clean = requests[i].text.trim()
            if (clean.isBlank()) {
                results[i] = TranslationResult(requests[i].id, null, "Kaynak metin boş")
                continue
            }
            val cached = correctionDao.getCorrectionForText(clean, targetLangCode)
                ?: correctionDao.getCorrectionForText(normalizeForCache(clean), targetLangCode)
            if (cached != null) {
                results[i] = TranslationResult(
                    requests[i].id,
                    postProcessTranslatedOutput(cached.turkishText, convertTurkishChars)
                )
            } else {
                indicesToTranslate.add(i)
            }
        }
        if (indicesToTranslate.isEmpty()) {
            return@withContext TranslationOutcome(
                results.mapIndexed { index, result ->
                    result ?: TranslationResult(requests[index].id, null, "Çeviri sonucu üretilmedi")
                }
            )
        }

        // Never fall back to a developer key compiled into the APK. Each user
        // must provide their own key in settings for Gemini requests.
        val effectiveGeminiKey = userGeminiApiKey.trim()
        val normalizedEngine = engine.trim()
        val isGeminiSelected = normalizedEngine.equals("Gemini", ignoreCase = true)
        val isDeepLSelected = normalizedEngine.equals("DeepL", ignoreCase = true)
        val warnings = mutableListOf<String>()
        if (isGeminiSelected && effectiveGeminiKey.isBlank()) {
            warnings += "Gemini API anahtarı girilmemiş; yedek çeviri motorları kullanılıyor. Ayarlar bölümünden anahtar ekleyin."
        }
        if (isDeepLSelected && userDeepLApiKey.isBlank()) {
            warnings += "DeepL API anahtarı girilmemiş; yedek çeviri motorları kullanılıyor. Ayarlar bölümünden anahtar ekleyin."
        }
        val pendingTexts = indicesToTranslate.map { requests[it].text }
        var batchTranslated: List<String?>? = null
        val batchAttemptedEngines = mutableSetOf<String>()
        var geminiFailure: String? = null

        if (isDeepLSelected && userDeepLApiKey.isNotBlank()) {
            batchAttemptedEngines.add("DeepL")
            batchTranslated = coroutineScope {
                pendingTexts.map { text ->
                    async { translateWithDeepL(text, userDeepLApiKey, originLang, targetLangCode) }
                }.awaitAll()
            }
        }

        val useGeminiFirst = batchTranslated == null &&
            (isGeminiSelected || normalizedEngine.equals("Otomatik", ignoreCase = true)) &&
            effectiveGeminiKey.isNotBlank()
        if (useGeminiFirst) {
            batchAttemptedEngines.add("Gemini")
            batchTranslated = try {
                // Birden çok model/deneme üst üste binerse sayfa dakikalarca
                // "çeviriliyor" ekranında kalabiliyordu.
                withTimeoutOrNull(GEMINI_TOTAL_TIMEOUT_MS) {
                    translateBatchViaGemini(pendingTexts, effectiveGeminiKey, geminiModel, targetEnglishName)
                } ?: run {
                    geminiFailure = "zaman aşımı"
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                geminiFailure = e.message?.take(140) ?: e.javaClass.simpleName
                null
            }
            if (geminiFailure != null && isGeminiSelected) {
                warnings += "Gemini çevirisi başarısız ($geminiFailure); yedek motorlar denendi."
            }
        }

        var batchUsedCascade = false
        if (batchTranslated == null || batchTranslated.size != pendingTexts.size) {
            val fallbackEngine = if (engine.equals("Gemini", ignoreCase = true) || engine.equals("Otomatik", ignoreCase = true)) "Google" else engine
            batchUsedCascade = true
            batchTranslated = coroutineScope {
                pendingTexts.map { text ->
                    async {
                        cascadeSemaphore.withPermit {
                            translateSingleCascade(
                                text,
                                fallbackEngine,
                                effectiveGeminiKey,
                                userDeepLApiKey,
                                originLang,
                                targetLangCode,
                                geminiModel,
                                batchAttemptedEngines
                            )
                        }
                    }
                }.awaitAll()
            }
        }

        val finalizedBatchTranslations = batchTranslated.orEmpty()
        val fallbackResults = coroutineScope {
            val fallbackJobs = indicesToTranslate.indices.mapNotNull { idx ->
                val translated = finalizedBatchTranslations.getOrNull(idx)?.takeIf { it.isNotBlank() }.orEmpty()
                // Kaynak metinle aynı gelen sonuç (isim, kısaltma, zaten hedef
                // dilde olan söz) başarısızlık sayılmaz; aksi halde her isim
                // için tüm motorlar + ML Kit model indirmesi tetikleniyordu.
                val needsFallback = !batchUsedCascade && translated.isBlank()
                if (!needsFallback) {
                    null
                } else {
                    idx to async {
                        cascadeSemaphore.withPermit {
                            translateSingleCascade(
                                requests[indicesToTranslate[idx]].text,
                                "Google",
                                effectiveGeminiKey,
                                userDeepLApiKey,
                                originLang,
                                targetLangCode,
                                geminiModel,
                                batchAttemptedEngines
                            )
                        }
                    }
                }
            }
            fallbackJobs.map { (idx, deferred) -> idx to deferred.await() }.toMap()
        }

        for (idx in indicesToTranslate.indices) {
            val originalIndex = indicesToTranslate[idx]
            val request = requests[originalIndex]
            var translated = finalizedBatchTranslations.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: ""
            if (fallbackResults.containsKey(idx)) {
                translated = fallbackResults[idx].orEmpty()
            }
            if (translated.isBlank()) {
                val error = "Tüm çeviri sağlayıcıları boş veya başarısız sonuç döndürdü"
                Log.e("TranslationService", "Çeviri başarısız: id=${request.id}, error=$error")
                results[originalIndex] = TranslationResult(request.id, null, error)
                continue
            }
            translated = postProcessTranslatedOutput(translated, convertTurkishChars)
            results[originalIndex] = TranslationResult(request.id, translated)
        }
        TranslationOutcome(
            results = results.mapIndexed { index, result ->
                result ?: TranslationResult(requests[index].id, null, "Çeviri sonucu üretilmedi")
            },
            warning = warnings.takeIf { it.isNotEmpty() }?.joinToString(" "),
        )
    }

    private suspend fun translateSingleCascade(
        text: String,
        primaryEngine: String,
        geminiKey: String,
        deepLKey: String,
        originLang: String,
        targetLangCode: String,
        geminiModel: String,
        skipEngines: Set<String> = emptySet()
    ): String? {
        if (isLikelyUntranslatableToken(text)) return text
        val targetEnglishName = TranslationLanguages.englishName(targetLangCode) ?: "English"

        // Engine names can come from persisted/user settings with arbitrary
        // casing (for example "deepl" or "google"). Normalize the known
        // names once so skipEngines and the dispatch below use the same keys.
        fun canonicalEngineName(name: String): String = when (name.trim().lowercase(Locale.ROOT)) {
            "deepl" -> "DeepL"
            "gemini" -> "Gemini"
            "google" -> "Google"
            "mymemory" -> "MyMemory"
            "ml kit", "mlkit", "offline" -> "ML Kit"
            else -> name.trim()
        }

        val normalizedPrimaryEngine = canonicalEngineName(primaryEngine)
        val normalizedSkipEngines = skipEngines
            .mapTo(mutableSetOf()) { canonicalEngineName(it).lowercase(Locale.ROOT) }
        val engines = mutableListOf<String>()
        if (normalizedPrimaryEngine.isNotBlank() &&
            normalizedPrimaryEngine.lowercase(Locale.ROOT) !in normalizedSkipEngines
        ) {
            engines.add(normalizedPrimaryEngine)
        }
        // ML Kit yalnızca kullanıcı açıkça seçtiyse denenir; aksi halde
        // arka planda dil modeli indirip çeviriyi dakikalarca bekletiyordu.
        listOf("Google", "MyMemory", "DeepL", "Gemini").forEach {
            if (it.lowercase(Locale.ROOT) !in normalizedSkipEngines && !engines.contains(it)) {
                engines.add(it)
            }
        }

        for (curEngine in engines) {
            try {
                val res = when (curEngine) {
                    "DeepL" -> if (deepLKey.isNotBlank()) translateWithDeepL(text, deepLKey, originLang, targetLangCode) else ""
                    "Gemini" -> if (geminiKey.isNotBlank()) translateSingleViaGemini(text, geminiKey, geminiModel, targetEnglishName) else ""
                    "ML Kit", "Offline" -> translateOffline(text, originLang, targetLangCode)
                    "Google" -> translateViaGoogle(text, targetLangCode)
                    "MyMemory" -> translateViaMyMemory(text, targetLangCode)
                    else -> ""
                }
                if (res.isNotBlank() && !res.contains("Translation Error", ignoreCase = true)) {
                    return res
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return null
    }

    private suspend fun translateBatchViaGemini(
        texts: List<String>,
        apiKey: String,
        preferredModel: String = "gemini-flash-latest",
        targetEnglishName: String = "Turkish"
    ): List<String?> = withContext(Dispatchers.IO) {
        translateBatchViaGeminiInternal(texts, apiKey, preferredModel, targetEnglishName, depth = 0)
    }

    private suspend fun translateBatchViaGeminiInternal(
        texts: List<String>,
        apiKey: String,
        preferredModel: String,
        targetEnglishName: String,
        depth: Int
    ): List<String?> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val models = mutableListOf<String>()
        if (preferredModel.isNotBlank() && !preferredModel.startsWith("gemini-1.5-")) {
            models.add(preferredModel)
        }
        listOf("gemini-flash-latest", "gemini-3.8-flash", "gemini-3.5-flash-lite").forEach {
            if (!models.contains(it)) models.add(it)
        }

        val jsonArrayInput = JSONArray()
        texts.forEachIndexed { idx, t ->
            jsonArrayInput.put(JSONObject().put("i", idx).put("t", t))
        }

        val prompt = buildString {
            appendLine("You are an expert manga and webtoon dialogue translator into natural, expressive $targetEnglishName.")
            appendLine("CRITICAL RULES:")
            appendLine("1. Contextual Scene Flow: Read these dialogue entries as speech from a single continuous manga page/scene. Understand characters, context, and emotion.")
            appendLine("2. Punchy Comic Phrasing: Keep dialogue punchy, lively, and concise so it fits comfortably inside manga speech bubbles. Avoid clumsy or overly wordy phrasing.")
            appendLine("3. Return ONLY a valid JSON array where each element is an object {\"i\": <original index>, \"t\": \"<$targetEnglishName translation>\"}. Cover every input index exactly once, in any order. Do not wrap in markdown or backticks.")
            appendLine("Input JSON:")
            append(jsonArrayInput.toString())
        }

        var lastError: Exception? = null
        var onlyParseFailures = true
        for (model in models) {
            try {
                val requestBody = JSONObject().apply {
                    val contentArr = JSONArray()
                    val contentObj = JSONObject()
                    val partsArr = JSONArray()
                    partsArr.put(JSONObject().put("text", prompt))
                    contentObj.put("parts", partsArr)
                    contentArr.put(contentObj)
                    put("contents", contentArr)
                    geminiGenerationConfig(model)?.let { put("generationConfig", it) }
                }
                val requestBodyData = requestBody.toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                val request = Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", apiKey)
                    .post(requestBodyData)
                    .build()

                // Her model için ayrı süre tanı; düşünme modu uzun süren model
                // tüm bütçeyi tüketip diğer modelleri denemeden çeviriyi
                // Google'a düşürmesin.
                val parsed = withTimeoutOrNull(GEMINI_MODEL_TIMEOUT_MS) {
                    executeCancellable(request, geminiClient).use { response ->
                        if (!response.isSuccessful) {
                            val err = response.body?.string() ?: ""
                            throw GeminiHttpException(response.code, "Gemini API Error ($model - HTTP ${response.code}): $err")
                        }
                        val body = response.body?.string()
                            ?: throw GeminiBatchParseException("Gemini API boş yanıt döndürdü ($model)")
                        parseGeminiBatchResponse(body, texts.size, model)
                    }
                } ?: throw IOException("$model zaman aşımı")
                return@withContext parsed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is GeminiHttpException && e.statusCode in setOf(401, 403)) {
                    throw e
                }
                if (e !is GeminiBatchParseException) onlyParseFailures = false
                lastError = e
                Log.w(TAG, "Model $model failed: ${e.message}")
            }
        }

        if (onlyParseFailures && lastError is GeminiBatchParseException && texts.size > 1 && depth < 2) {
            Log.w(TAG, "Batch bölünüyor (depth=$depth, size=${texts.size})")
            val mid = texts.size / 2
            val left = try {
                translateBatchViaGeminiInternal(texts.subList(0, mid), apiKey, preferredModel, targetEnglishName, depth + 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sol Gemini alt grubu başarısız; diğer sonuçlar korunuyor: ${e.message}")
                List<String?>(mid) { null }
            }
            val rightSize = texts.size - mid
            val right = try {
                translateBatchViaGeminiInternal(texts.subList(mid, texts.size), apiKey, preferredModel, targetEnglishName, depth + 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sağ Gemini alt grubu başarısız; diğer sonuçlar korunuyor: ${e.message}")
                List<String?>(rightSize) { null }
            }
            return@withContext left + right
        }
        throw (lastError ?: IOException("All Gemini model attempts failed"))
    }

    private fun geminiGenerationConfig(model: String): JSONObject? {
        // Gemini 3.8/flash-latest varsayılan olarak "medium" düşünme seviyesiyle
        // çalışıyor; kısa manga diyaloglarında bu gecikme zaman aşımına yol
        // açıyordu. 3.x için en düşük desteklenen seviyeyi, 2.5 için sıfır
        // düşünme bütçesini kullan.
        val thinking = when {
            model.startsWith("gemini-2.5-flash", ignoreCase = true) ->
                JSONObject().put("thinkingBudget", 0)
            model.startsWith("gemini-3", ignoreCase = true) ->
                JSONObject().put(
                    "thinkingLevel",
                    if (model.contains("flash-lite", ignoreCase = true)) "minimal" else "low",
                )
            model.equals("gemini-flash-latest", ignoreCase = true) ->
                JSONObject().put("thinkingLevel", "low")
            else -> null
        } ?: return null
        return JSONObject().put("thinkingConfig", thinking)
    }

    private fun parseGeminiBatchResponse(body: String, expected: Int, model: String): List<String> {
        try {
            val json = JSONObject(body)
            val candidates = json.optJSONArray("candidates")
                ?: throw GeminiBatchParseException("Gemini yanıtında aday bulunamadı ($model)")
            if (candidates.length() == 0) throw GeminiBatchParseException("Gemini boş aday listesi döndürdü ($model)")
            val candidate = candidates.getJSONObject(0)
            val content = candidate.optJSONObject("content")
                ?: throw GeminiBatchParseException("Gemini yanıtında içerik yok ($model)")
            val parts = content.optJSONArray("parts")
                ?: throw GeminiBatchParseException("Gemini yanıtında parts yok ($model)")
            if (parts.length() == 0) throw GeminiBatchParseException("Gemini boş parts döndürdü ($model)")
            // Gemini 3 modelleri düşünme parçaları döndürebilir; yanıtı
            // yalnızca ilk parçadan okumak JSON'un kaybolmasına yol açıyordu.
            val text = buildString {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optBoolean("thought", false)) continue
                    append(part.optString("text", ""))
                }
            }.trim()
            if (text.isBlank()) throw GeminiBatchParseException("Gemini boş metin döndürdü ($model)")

            val startIdx = text.indexOf('[')
            val endIdx = text.lastIndexOf(']')
            val cleanJson = if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                text.substring(startIdx, endIdx + 1)
            } else {
                text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            }
            return parseNumberedBatch(cleanJson, expected)
                ?: throw GeminiBatchParseException("Batch doğrulama başarısız ($model): eksik/hatalı indeks")
        } catch (e: GeminiBatchParseException) {
            throw e
        } catch (e: Exception) {
            throw GeminiBatchParseException("Gemini yanıtı ayrıştırılamadı ($model)", e)
        }
    }

    private fun parseNumberedBatch(cleanJson: String, expected: Int): List<String>? {
        return try {
            val arr = JSONArray(cleanJson)
            if (arr.length() == 0 || expected <= 0) return null
            val first = arr.opt(0)
            if (first is JSONObject && (first.has("i") || first.has("t"))) {
                val out = MutableList(expected) { "" }
                val seen = BooleanArray(expected)
                for (k in 0 until arr.length()) {
                    val o = arr.optJSONObject(k) ?: continue
                    val idx = o.optInt("i", -1)
                    if (idx in 0 until expected) {
                        val translation = o.optString("t", "").trim()
                        if (translation.isNotEmpty()) {
                            out[idx] = translation
                            seen[idx] = true
                        }
                    }
                }
                if (seen.all { it }) out else null
            } else {
                if (arr.length() != expected) return null
                val translations = MutableList(expected) { i -> arr.optString(i, "").trim() }
                if (translations.all { it.isNotEmpty() }) translations else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun translateSingleViaGemini(
        text: String,
        apiKey: String,
        model: String = "gemini-flash-latest",
        targetEnglishName: String = "Turkish"
    ): String = withContext(Dispatchers.IO) {
        val list = translateBatchViaGemini(listOf(text), apiKey, model, targetEnglishName)
        list.firstOrNull()?.takeIf { it.isNotBlank() } ?: ""
    }

    fun closeAllTranslators() {
        translatorCleanupScope.launch {
            translatorCloseMutex.withLock {
                val waitForUsers = translatorLifecycleMutex.withLock {
                    translatorCleanupInProgress = true
                    translatorsAvailable = CompletableDeferred()
                    if (activeTranslatorLeases == 0) null else noActiveTranslatorLeases
                }
                try {
                    waitForUsers?.await()
                    translatorLifecycleMutex.withLock {
                        translatorCache.values.forEach { translator ->
                            runCatching { translator.close() }
                        }
                        translatorCache.clear()
                        translatorMutexes.clear()
                    }
                } finally {
                    translatorLifecycleMutex.withLock {
                        translatorCleanupInProgress = false
                        translatorsAvailable.complete(Unit)
                    }
                }
            }
        }
    }

    private suspend fun acquireTranslatorLease(
        cacheKey: String,
        sourceLangTag: String,
        targetLangTag: String
    ): TranslatorLease {
        while (true) {
            val lease = translatorLifecycleMutex.withLock {
                if (translatorCleanupInProgress) {
                    null
                } else {
                    val translator = translatorCache.computeIfAbsent(cacheKey) {
                        val options = TranslatorOptions.Builder()
                            .setSourceLanguage(sourceLangTag)
                            .setTargetLanguage(targetLangTag)
                            .build()
                        Translation.getClient(options)
                    }
                    val mutex = translatorMutexes.computeIfAbsent(cacheKey) { Mutex() }
                    activeTranslatorLeases++
                    if (activeTranslatorLeases == 1) {
                        noActiveTranslatorLeases = CompletableDeferred()
                    }
                    TranslatorLease(translator, mutex)
                }
            }
            if (lease != null) return lease

            val available = translatorLifecycleMutex.withLock { translatorsAvailable }
            available.await()
        }
    }

    private suspend fun releaseTranslatorLease() {
        translatorLifecycleMutex.withLock {
            activeTranslatorLeases--
            if (activeTranslatorLeases == 0) noActiveTranslatorLeases.complete(Unit)
        }
    }

    fun normalizeForCache(s: String): String {
        return s.trim().lowercase(Locale.ROOT).replace("\\s+".toRegex(), " ")
    }

    // Önbellekten yüklenen bloklara kullanıcı düzeltmelerini uygulamak için.
    suspend fun lookupCorrection(originalText: String, targetLangCode: String): String? {
        val clean = originalText.trim()
        if (clean.isBlank()) return null
        return (correctionDao.getCorrectionForText(clean, targetLangCode)
            ?: correctionDao.getCorrectionForText(normalizeForCache(clean), targetLangCode))?.turkishText
    }

    private suspend fun translateOffline(
        text: String,
        originLang: String,
        targetLangCode: String
    ): String = withContext(Dispatchers.IO) {
        val targetLangTag = TranslationLanguages.mlKitTag(targetLangCode) ?: return@withContext ""
        val hasCjk = text.any { it.code in 0x3040..0x9FAF }
        val hasHangul = text.any { it.code in 0xAC00..0xD7AF }
        val sourceLangTag = TranslationLanguages.mlKitTag(
            TranslationLanguages.codeForDisplayName(originLang) ?: ""
        ) ?: when {
            hasHangul -> "ko"
            hasCjk -> "ja"
            else -> "en"
        }
        if (sourceLangTag == targetLangTag) return@withContext text

        val cacheKey = "$sourceLangTag->$targetLangTag"
        val lease = acquireTranslatorLease(cacheKey, sourceLangTag, targetLangTag)
        try {
            lease.mutex.withLock {
                val translator = lease.translator
                val conditions = DownloadConditions.Builder().build()
                try {
                    val downloadOk = withTimeoutOrNull(60_000L) {
                        suspendCancellableCoroutine<Boolean> { continuation ->
                            translator.downloadModelIfNeeded(conditions)
                                .addOnSuccessListener {
                                    if (continuation.isActive) continuation.resume(true)
                                }
                                .addOnFailureListener {
                                    if (continuation.isActive) continuation.resume(false)
                                }
                        }
                    } ?: false
                    if (!downloadOk) return@withLock ""

                    val translatedText = withTimeoutOrNull(15_000L) {
                        suspendCancellableCoroutine<String> { continuation ->
                            translator.translate(text)
                                .addOnSuccessListener {
                                    if (continuation.isActive) continuation.resume(it)
                                }
                                .addOnFailureListener {
                                    if (continuation.isActive) continuation.resume("")
                                }
                        }
                    }
                    translatedText ?: ""
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ""
                }
            }
        } finally {
            withContext(NonCancellable) {
                releaseTranslatorLease()
            }
        }
    }

    private suspend fun executeCancellable(
        request: Request,
        httpClient: OkHttpClient = client
    ): okhttp3.Response =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (continuation.isActive) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    } else {
                        response.close()
                    }
                }
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }

    private suspend fun translateViaGoogle(text: String, targetLangCode: String): String = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val targetCode = if (targetLangCode == "zh") "zh-CN" else targetLangCode
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetCode&dt=t&q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()

            executeCancellable(request).use { response ->
                if (!response.isSuccessful) return@use ""
                val body = response.body?.string() ?: return@use ""
                val json = JSONArray(body)
                val sentences = json.optJSONArray(0) ?: return@use ""
                val sb = StringBuilder()
                for (i in 0 until sentences.length()) {
                    val sentence = sentences.optJSONArray(i)
                    if (sentence != null) {
                        val part = sentence.optString(0, "")
                        if (part.isNotEmpty() && part != "null") {
                            sb.append(part)
                        }
                    }
                }
                sb.toString()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun translateViaMyMemory(text: String, targetLangCode: String): String = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val targetCode = if (targetLangCode == "zh") "zh-CN" else targetLangCode
            val url = "https://api.mymemory.translated.net/get?q=$encoded&langpair=autodetect|$targetCode"
            val request = Request.Builder().url(url).build()
            executeCancellable(request).use { response ->
                if (!response.isSuccessful) return@use ""
                val body = response.body?.string() ?: return@use ""
                val json = JSONObject(body)
                if (json.optInt("responseStatus", -1) != 200) return@use ""
                val resData = json.optJSONObject("responseData") ?: return@use ""
                val translated = resData.optString("translatedText", "").trim()
                if (translated.contains("MYMEMORY WARNING", ignoreCase = true)) "" else translated
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
    }

    private fun cleanTranslatedOutput(text: String): String {
        var str = text.trim()
        // Remove hyphens only when they split a word across a line break ("e-\nposta").
        str = str.replace(
            Regex("([A-Za-zğüşıöçĞÜŞİÖÇ]+)-[ \\t]*\\r?\\n[ \\t]*([A-Za-zğüşıöçĞÜŞİÖÇ]+)"),
            "$1$2"
        )
        str = str.replace("\\s+".toRegex(), " ")
        return str.trim()
    }

    private fun postProcessTranslatedOutput(text: String, turkishConvertEnabled: Boolean): String {
        val cleaned = cleanTranslatedOutput(text)
        return if (turkishConvertEnabled) convertTurkishChars(cleaned) else cleaned
    }

    private fun convertTurkishChars(text: String): String {
        var str = text
        val map = mapOf(
            'ğ' to 'g', 'Ğ' to 'G',
            'ş' to 's', 'Ş' to 'S',
            'ç' to 'c', 'Ç' to 'C',
            'ı' to 'i', 'İ' to 'I',
            'ö' to 'o', 'Ö' to 'O',
            'ü' to 'u', 'Ü' to 'U'
        )
        map.forEach { (turkish, standard) ->
            str = str.replace(turkish, standard)
        }
        return str
    }
}
