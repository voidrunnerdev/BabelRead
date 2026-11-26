package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.example.util.ContourScanlineProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BubbleBox(
    val id: Int,
    val bounds: RectF, // Normalized 0.0f..1.0f (left, top, right, bottom)
    val confidence: Float,
    val label: String = "text",
    var originalText: String = "",
    var translatedText: String = "",
    val contourProfile: ContourScanlineProfile? = null
) {
    fun toPixelRect(bitmapWidth: Int, bitmapHeight: Int): Rect {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return Rect()
        val leftPx = (bounds.left * bitmapWidth).toInt().coerceIn(0, bitmapWidth - 1)
        val topPx = (bounds.top * bitmapHeight).toInt().coerceIn(0, bitmapHeight - 1)
        val rightPx = (bounds.right * bitmapWidth).toInt().coerceIn(leftPx + 1, bitmapWidth)
        val bottomPx = (bounds.bottom * bitmapHeight).toInt().coerceIn(topPx + 1, bitmapHeight)
        return Rect(leftPx, topPx, rightPx, bottomPx)
    }
}

data class BubbleDetectionOutput(
    val bubbles: List<BubbleBox>,
    val panels: List<RectF>
)

class BubbleDetector(context: Context) {
    val yoloDetector = YoloBubbleDetector(context)

    fun isAiModelReady(): Boolean = yoloDetector.isModelLoaded()

    fun getModelPath(): String = if (yoloDetector.isModelLoaded()) {
        "${yoloDetector.activeModelType.title} (${yoloDetector.activeModelType.subtitle}) + ML Kit OCR"
    } else {
        "Yapay zeka modeli hazır değil; OCR ve görüntü sezgiselleri kullanılabilir"
    }

    /**
     * Sayfadaki konuşma balonu ve metin bölgelerini seçili model ile saptar.
     */
    suspend fun detectBubbles(
        bitmap: Bitmap?,
        pageIndex: Int = 0,
        confidenceThreshold: Float = 0.25f
    ): List<BubbleBox> = detectBubblesWithPanels(bitmap, pageIndex, confidenceThreshold).bubbles

    /**
     * Sayfadaki konuşma balonları/metin bölgeleri ile okuma sırası için panel sınırlarını döndürür.
     */
    suspend fun detectBubblesWithPanels(
        bitmap: Bitmap?,
        pageIndex: Int = 0,
        confidenceThreshold: Float = 0.25f
    ): BubbleDetectionOutput = withContext(Dispatchers.Default) {
        val result = yoloDetector.detectSpeechBubblesWithPanelsResult(bitmap, confidenceThreshold)
        BubbleDetectionOutput(
            bubbles = result.detections,
            panels = result.panels
        )
    }
}
