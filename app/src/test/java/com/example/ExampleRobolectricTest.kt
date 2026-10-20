package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.util.TextScalingUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `test YoloBubbleDetector initialization and model file presence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = com.example.service.YoloBubbleDetector(context)
        val file = detector.getModelFile()
        val isLoaded = detector.isModelLoaded()
        println("Detector isLoaded: $isLoaded")
        assertTrue(detector.modelInputWidth > 0)
        assertTrue(detector.modelInputHeight > 0)

        // A class name supplied by model metadata is used for class 0.
        detector.dynamicClassNames = mapOf(0 to "bubble")
        val labelSingle = detector.getLabelForClass(0, totalClassesInHead = 1)
        assertEquals("bubble", labelSingle)

        // Test unknown class doesn't fallback to SFX
        val labelUnknown = detector.getLabelForClass(999, totalClassesInHead = 4)
        assertEquals("unknown", labelUnknown)
    }

    @Test
    fun `test bounding box area calculation`() {
        val area = TextScalingUtil.calculateBoundingBoxArea(50f, 40f)
        assertEquals(2000f, area, 0.01f)
    }

    @Test
    fun `test dynamic text scaling adapts to bubble size`() {
        val text = "Bu manga panelindeki konuşma baloncuk metnidir."
        val largeBubble = TextScalingUtil.calculateScaledText(text, widthDp = 120f, heightDp = 80f)
        val smallBubble = TextScalingUtil.calculateScaledText(text, widthDp = 40f, heightDp = 30f)

        assertTrue(smallBubble.fontSize.value <= largeBubble.fontSize.value)
        assertTrue(smallBubble.fontSize.value >= 7.0f)
        assertTrue(largeBubble.fontSize.value <= 12.0f)
    }

    @Test
    fun `test sentence cohesion without artificial linebreaks`() {
        val sentence = "Bence oraya gitmemeliyiz çok tehlikeli görünüyor."
        val result = TextScalingUtil.calculateScaledText(sentence, widthDp = 80f, heightDp = 60f)

        assertTrue(!result.formattedText.contains("\n"))
        assertTrue(result.formattedText.contains("Bence"))
        assertTrue(result.maxLines >= 1)
    }

    @Test
    fun `test StaticTextLayoutOptimizer calculates line breaking and font sizing`() {
        val text = "Bu bir manga konuşma baloncuğu metnidir ve balon içine tam oturmalıdır."
        val bounds = android.graphics.RectF(0f, 0f, 100f, 70f)
        val result = com.example.util.StaticTextLayoutOptimizer.optimizeLayout(
            text = text,
            bounds = bounds,
            minSp = 7.0f,
            maxSp = 12.0f,
            density = 2.0f,
            spToPx = { it * 2.0f }
        )
        assertTrue(result.fontSizeSp in 5.0f..12.5f)
        assertTrue(result.lineCount >= 1)
        assertTrue(result.totalHeightPx > 0f)
    }

    @Test
    fun `test MangaSourceRegistry contains multiple major sources`() {
        val sources = com.example.data.parser.MangaSourceRegistry.sources.map { it.name }
        assertTrue(sources.contains("MangaDex"))
        assertTrue(sources.contains("ComicK"))
        assertTrue(sources.contains("Cihaz / Yerel Mangalar"))
        assertTrue(sources.contains("MangaFire"))
    }

    @Test
    fun `test Canvas drawing masks original text on bitmap`() {
        val bmp = android.graphics.Bitmap.createBitmap(200, 200, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        val textPaint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
        canvas.drawRect(50f, 50f, 80f, 70f, textPaint)

        val service = com.example.service.TranslationService(
            correctionDao = object : com.example.data.db.TranslationCorrectionDao {
                override suspend fun getCorrectionForText(originalText: String): com.example.data.db.TranslationCorrectionEntity? = null
                override suspend fun insertCorrection(correction: com.example.data.db.TranslationCorrectionEntity) {}
                override suspend fun deleteCorrection(id: Int) {}
                override suspend fun deleteCorrectionByText(originalText: String) {}
                override fun getAllCorrections(): Flow<List<com.example.data.db.TranslationCorrectionEntity>> = flowOf(emptyList())
                override suspend fun clearAll() {}
            }
        )

        val block = com.example.service.TextBlockObj(
            originalText = "Hello",
            translatedText = "Merhaba",
            boundingBox = android.graphics.Rect(50, 50, 80, 70),
            bubbleBox = android.graphics.Rect(50, 50, 80, 70),
            isBubble = true,
            isDarkBubble = false
        )
        val maskedBmp = service.maskOriginalTextOnBitmap(bmp, listOf(block))
        val pixelColor = maskedBmp.getPixel(60, 60)
        assertTrue("Pixel should be masked to white or near white", android.graphics.Color.red(pixelColor) > 200)
    }

    @Test
    fun `test OCR artifact cleaning preserves meaningful digits`() {
        val service = com.example.service.TranslationService(
            correctionDao = object : com.example.data.db.TranslationCorrectionDao {
                override suspend fun getCorrectionForText(originalText: String): com.example.data.db.TranslationCorrectionEntity? = null
                override suspend fun insertCorrection(correction: com.example.data.db.TranslationCorrectionEntity) {}
                override suspend fun deleteCorrection(id: Int) {}
                override suspend fun deleteCorrectionByText(originalText: String) {}
                override fun getAllCorrections(): Flow<List<com.example.data.db.TranslationCorrectionEntity>> = flowOf(emptyList())
                override suspend fun clearAll() {}
            }
        )

        val cleaned1 = service.cleanOcrArtifacts("G0T")
        assertEquals("G0T", cleaned1)
        val cleaned2 = service.cleanOcrArtifacts("t0-yarın")
        assertEquals("t0-yarın", cleaned2)
        val cleaned3 = service.cleanOcrArtifacts("S0")
        assertEquals("S0", cleaned3)
        val cleaned4 = service.cleanOcrArtifacts("WEL1")
        assertEquals("WEL1", cleaned4)
        val cleaned5 = service.cleanOcrArtifacts("YE5")
        assertEquals("YE5", cleaned5)
        val cleaned6 = service.cleanOcrArtifacts("kullanac- aksınız")
        assertEquals("kullanacaksınız", cleaned6)
        val cleaned7 = service.cleanOcrArtifacts("B AKABİLECEĞİZ")
        assertEquals("BAKABİLECEĞİZ", cleaned7)
        assertEquals("Seviye 50", service.cleanOcrArtifacts("Seviye 50"))
        assertEquals("Sektör 1", service.cleanOcrArtifacts("Sektör 1"))
    }

    @Test
    fun `test dialogue validation rejects SFX and garbage`() {
        val service = com.example.service.TranslationService(
            correctionDao = object : com.example.data.db.TranslationCorrectionDao {
                override suspend fun getCorrectionForText(originalText: String): com.example.data.db.TranslationCorrectionEntity? = null
                override suspend fun insertCorrection(correction: com.example.data.db.TranslationCorrectionEntity) {}
                override suspend fun deleteCorrection(id: Int) {}
                override suspend fun deleteCorrectionByText(originalText: String) {}
                override fun getAllCorrections(): Flow<List<com.example.data.db.TranslationCorrectionEntity>> = flowOf(emptyList())
                override suspend fun clearAll() {}
            }
        )

        assertTrue(!service.isValidDialogueText("Xxx x", isBubble = true))
        assertTrue(!service.isValidDialogueText("Xxxx xx x", isBubble = true))
        assertTrue(!service.isValidDialogueText("XXXX x balık", isBubble = true))
        assertTrue(!service.isValidDialogueText("209", isBubble = true))
        assertTrue(!service.isValidDialogueText("210", isBubble = false))
        assertTrue(!service.isValidDialogueText("p. 15", isBubble = false))
        assertTrue(service.isValidDialogueText("Önce bir yumurta kırın.", isBubble = true))
        assertTrue(service.isValidDialogueText("Hello, how are you?", isBubble = true))
        assertTrue(service.isValidDialogueText("Wait!", isBubble = true))
        assertTrue(service.isValidDialogueText("ALRIGHT, SHERRY THE EGG WHITES ARE YOURS.", isBubble = true))
        assertTrue(service.isValidDialogueText("IT.", isBubble = true))
        assertTrue(service.isValidDialogueText("Eh", isBubble = false))
        assertTrue(service.isValidDialogueText("Ah!", isBubble = false))
        assertTrue(service.isValidDialogueText("Ne?", isBubble = false))
        assertTrue(!service.isValidDialogueText("Wtakes", isBubble = false))
    }
}
