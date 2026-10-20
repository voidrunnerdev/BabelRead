package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BubbleBox(
    val id: Int,
    val bounds: RectF, // Normalized 0.0f..1.0f (left, top, right, bottom)
    val confidence: Float,
    val label: String = "text",
    var originalText: String = "",
    var translatedText: String = ""
) {
    fun toPixelRect(bitmapWidth: Int, bitmapHeight: Int): Rect {
        val leftPx = (bounds.left * bitmapWidth).toInt().coerceIn(0, bitmapWidth - 1)
        val topPx = (bounds.top * bitmapHeight).toInt().coerceIn(0, bitmapHeight - 1)
        val rightPx = (bounds.right * bitmapWidth).toInt().coerceIn(leftPx + 1, bitmapWidth)
        val bottomPx = (bounds.bottom * bitmapHeight).toInt().coerceIn(topPx + 1, bitmapHeight)
        return Rect(leftPx, topPx, rightPx, bottomPx)
    }
}

class BubbleDetector(context: Context) {
    val yoloDetector = YoloBubbleDetector(context)

    fun isAiModelReady(): Boolean = yoloDetector.isModelLoaded()

    fun getModelPath(): String = if (yoloDetector.isModelLoaded()) {
        "RT-DETR-v2 Comic AI (detector.onnx) + ML Kit OCR"
    } else {
        "RT-DETR modeli hazır değil; OCR ve görüntü sezgiselleri kullanılabilir"
    }

    /**
     * Sayfadaki konuşma balonu ve metin bölgelerini RT-DETR/ONNX ile saptar.
     */
    suspend fun detectBubbles(
        bitmap: Bitmap?,
        pageIndex: Int = 0,
        confidenceThreshold: Float = 0.25f
    ): List<BubbleBox> = withContext(Dispatchers.Default) {
        yoloDetector.detectSpeechBubbles(bitmap, confidenceThreshold)
    }
}
