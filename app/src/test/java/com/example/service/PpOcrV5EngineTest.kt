package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.example.data.pref.MangaPreferences
import com.example.data.pref.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PpOcrV5EngineTest {

    @Test
    fun `en_ppocrv5_dict asset exists with non-blank lines and matches ENGLISH_ONLY variant`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val variant = PpOcrV5Engine.Variant.ENGLISH_ONLY

        assertTrue(variant.englishOnly)
        assertEquals("en_ppocrv5_rec_v1.onnx", variant.cacheFileName)
        assertEquals(7_848_423L, variant.expectedBytes)
        assertTrue(
            "ENGLISH_ONLY model asset should be available",
            PpOcrV5Engine.hasModelAsset(context, variant)
        )

        val dictLines = context.assets.open(variant.dictAsset).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines().map { it.trimEnd('\r', '\n') }
        }
        assertEquals("Expected 436 character lines in en_ppocrv5_dict.txt", 436, dictLines.size)
        assertTrue("Dictionary must not contain blank lines", dictLines.none { it.isBlank() })
    }

    @Test
    fun `decodeCtc decodes ENGLISH_ONLY blank 0, dictionary 1 to N, and space N+1`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PpOcrV5Engine(context, PpOcrV5Engine.Variant.ENGLISH_ONLY)
        val dictionary = context.assets.open(PpOcrV5Engine.Variant.ENGLISH_ONLY.dictAsset)
            .bufferedReader(Charsets.UTF_8)
            .use { reader -> reader.readLines().map { it.trimEnd('\r', '\n') } }

        val spaceIndex = dictionary.size + 1
        val classCount = dictionary.size + 2

        fun dictIndex(token: String): Int {
            val idx = dictionary.indexOf(token)
            assertTrue("Token '$token' missing from en_ppocrv5_dict.txt", idx >= 0)
            return idx + 1
        }

        // Encode "I BOOK!" with repeated steps and blank=0 separating consecutive 'O's
        val steps = intArrayOf(
            0,
            dictIndex("I"),
            dictIndex("I"),
            spaceIndex,
            dictIndex("B"),
            dictIndex("O"),
            0, // CTC blank required to emit second 'O'
            dictIndex("O"),
            dictIndex("K"),
            dictIndex("!"),
            0
        )

        val buffer = ByteBuffer.allocateDirect(steps.size * classCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (t in steps.indices) {
            val base = t * classCount
            val targetIdx = steps[t]
            for (c in 0 until classCount) {
                buffer.put(base + c, if (c == targetIdx) 0.96f else 0.001f)
            }
        }
        buffer.position(0)

        val result = engine.decodeCtc(buffer, steps.size, classCount, dictionary)
        assertEquals("I BOOK!", result.text)
        assertTrue("Expected high confidence for strong CTC probs", result.confidence > 0.90f)

        // All-blank sequence must decode to empty text and zero confidence
        val blankSteps = 4
        val blankBuffer = ByteBuffer.allocateDirect(blankSteps * classCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (t in 0 until blankSteps) {
            val base = t * classCount
            for (c in 0 until classCount) {
                blankBuffer.put(base + c, if (c == 0) 0.99f else 0.001f)
            }
        }
        blankBuffer.position(0)

        val blankResult = engine.decodeCtc(blankBuffer, blankSteps, classCount, dictionary)
        assertEquals("", blankResult.text)
        assertEquals(0f, blankResult.confidence, 0.0001f)
    }

    @Test
    fun `decodeCtc trims leading and trailing spaces without lowering short word confidence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PpOcrV5Engine(context, PpOcrV5Engine.Variant.ENGLISH_ONLY)
        val dictionary = listOf("N", "O")
        val spaceIndex = dictionary.size + 1 // 3
        val classCount = dictionary.size + 2 // 4

        val steps = listOf(
            spaceIndex to 0.30f,
            1 to 0.90f, // 'N'
            2 to 0.90f, // 'O'
            spaceIndex to 0.30f
        )

        val buffer = ByteBuffer.allocateDirect(steps.size * classCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for ((t, pair) in steps.withIndex()) {
            val (targetIdx, prob) = pair
            val base = t * classCount
            for (c in 0 until classCount) {
                buffer.put(base + c, if (c == targetIdx) prob else 0.01f)
            }
        }
        buffer.position(0)

        val result = engine.decodeCtc(buffer, steps.size, classCount, dictionary)
        assertEquals("NO", result.text)
        assertEquals(0.90f, result.confidence, 0.001f)
    }

    @Test
    fun `acceptsLine enforces English thresholds and filters non-alphanumeric noise`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PpOcrV5Engine(context, PpOcrV5Engine.Variant.ENGLISH_ONLY)

        assertFalse(engine.acceptsLine(PpOcrV5Engine.LineResult("", 0.95f), "İngilizce"))
        assertFalse(engine.acceptsLine(PpOcrV5Engine.LineResult("   ", 0.95f), "İngilizce"))
        assertFalse(engine.acceptsLine(PpOcrV5Engine.LineResult("...", 0.95f), "İngilizce"))
        assertFalse(engine.acceptsLine(PpOcrV5Engine.LineResult("!?", 0.95f), "İngilizce"))

        // Short English text (<= 4 alphanumeric chars): threshold 0.50f
        assertTrue(engine.acceptsLine(PpOcrV5Engine.LineResult("NO", 0.50f), "İngilizce"))
        assertTrue(engine.acceptsLine(PpOcrV5Engine.LineResult("OK!", 0.50f), "İngilizce"))
        assertFalse(engine.acceptsLine(PpOcrV5Engine.LineResult("NO", 0.49f), "İngilizce"))

        // Standard English text (> 4 alphanumeric chars): threshold 0.55f
        assertTrue(engine.acceptsLine(PpOcrV5Engine.LineResult("HELLO", 0.55f), "İngilizce"))
        assertFalse(engine.acceptsLine(PpOcrV5Engine.LineResult("HELLO", 0.54f), "İngilizce"))
    }

    @Test
    fun `isDarkBackgroundRegion detects dark speech bubble and rejects white bubble`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = PpOcrV5Engine(context, PpOcrV5Engine.Variant.ENGLISH_ONLY)

        val darkBubble = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        Canvas(darkBubble).apply {
            drawColor(Color.BLACK)
            val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            drawText("I SAID", 80f, 60f, whitePaint)
        }
        val region = Rect(10, 10, 150, 90)
        assertTrue(
            "Black bubble with white text should be detected as dark background",
            engine.isDarkBackgroundRegion(darkBubble, region)
        )

        val inverted = engine.invertHighContrastBitmap(darkBubble)
        assertTrue(
            "Inverted dark bubble corner should become bright background",
            Color.red(inverted.getPixel(0, 0)) >= 245
        )
        assertFalse(
            "Inverted dark bubble should no longer be classified as dark background",
            engine.isDarkBackgroundRegion(inverted, region)
        )

        val whiteBubble = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        Canvas(whiteBubble).apply {
            drawColor(Color.WHITE)
            val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            drawText("I SAID", 80f, 60f, blackPaint)
        }
        assertFalse(
            "White bubble with black text must not be classified as dark background",
            engine.isDarkBackgroundRegion(whiteBubble, region)
        )
    }

    @Test
    fun `PpOcrV5DetPreprocess scaledSize upscales small crops when minSide is set`() {
        val upscaled = PpOcrV5DetPreprocess.scaledSize(160, 80, 960, minSide = 480)
        assertEquals(480, upscaled[0])
        assertEquals(256, upscaled[1])

        val legacySmall = PpOcrV5DetPreprocess.scaledSize(160, 80, 960)
        assertEquals(160, legacySmall[0])
        assertEquals(96, legacySmall[1])
    }

    @Test
    fun `MangaPreferences migrates legacy PARSeq values to PP-OCRv5 EN`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = MangaPreferences(context)

        context.dataStore.edit { it[MangaPreferences.OCR_ENGINE] = "PARSeq" }
        assertEquals("PP-OCRv5 EN", prefs.ocrEngine.first())

        context.dataStore.edit { it[MangaPreferences.OCR_ENGINE] = "PARSeq EN" }
        assertEquals("PP-OCRv5 EN", prefs.ocrEngine.first())

        prefs.setOcrEngine("PP-OCRv5")
        assertEquals("PP-OCRv5", prefs.ocrEngine.first())
    }
}
