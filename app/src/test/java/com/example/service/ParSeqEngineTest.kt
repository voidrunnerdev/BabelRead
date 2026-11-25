package com.example.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
class ParSeqEngineTest {

    private val vocab = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~°£€¥¢฿àâéèêëîïôùûüçÀÂÉÈÊËÎÏÔÙÛÜÇ"
    private val classCount = 127 // 126 vocab chars + 1 EOS token at index 126

    @Test
    fun `decodeParSeq decodes words correctly and stops at eos token without truncating on zero`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = ParSeqEngine(context)

        val eosIndex = vocab.length // 126

        // Target word to encode: "100% COOL!"
        // Notice it contains '0' at index 0 in vocab!
        val targetText = "100% COOL!"
        val timesteps = targetText.length + 3 // target + EOS + padding

        val buffer = ByteBuffer.allocateDirect(timesteps * classCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        for (t in 0 until timesteps) {
            val base = t * classCount
            val targetChar = if (t < targetText.length) targetText[t] else null
            val targetIndex = when {
                targetChar != null -> vocab.indexOf(targetChar)
                t == targetText.length -> eosIndex // EOS
                else -> classCount - 1 // PAD
            }
            for (c in 0 until classCount) {
                buffer.put(base + c, if (c == targetIndex) 8.0f else -8.0f)
            }
        }
        buffer.position(0)

        val result = engine.decodeParSeq(buffer, timesteps, classCount, vocab)

        assertEquals("Decoded text should match target exactly without truncating at '0'", targetText, result.text)
        assertTrue("Confidence should be very high for strong logits", result.confidence > 0.95f)
    }

    @Test
    fun `decodeParSeq stops immediately at EOS token and ignores trailing hallucinated repetitions`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = ParSeqEngine(context)

        val eosIndex = vocab.length // 126
        val targetText = "OHASHI-KUN"
        val totalTimesteps = 33 // Full unrolled ONNX sequence

        val buffer = ByteBuffer.allocateDirect(totalTimesteps * classCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        // Steps 0..9: "OHASHI-KUN"
        // Step 10: EOS (index 126)
        // Steps 11..32: Trailing hallucinations/repetitions ("HHIKUNHIIA-OOHSOOOO")
        for (t in 0 until totalTimesteps) {
            val base = t * classCount
            val targetIndex = when {
                t < targetText.length -> vocab.indexOf(targetText[t])
                t == targetText.length -> eosIndex
                else -> vocab.indexOf("H") // Trailing repeating token
            }
            for (c in 0 until classCount) {
                buffer.put(base + c, if (c == targetIndex) 8.0f else -8.0f)
            }
        }
        buffer.position(0)

        val result = engine.decodeParSeq(buffer, totalTimesteps, classCount, vocab)

        assertEquals("Decoded text should strictly stop at EOS and not stutter or repeat trailing characters", targetText, result.text)
    }

    @Test
    fun `splitLineIntoWords separates I SAID into two ordered word rects`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = ParSeqEngine(context)

        val bitmap = renderSyntheticLineBitmap(
            words = listOf("I", "SAID"),
            bgColor = Color.WHITE,
            inkColor = Color.BLACK
        )

        val wordRects = engine.splitLineIntoWords(bitmap)

        assertEquals("Expected 'I SAID' to split into 2 words", 2, wordRects.size)
        assertTrue("First word 'I' must appear left of 'SAID'", wordRects[0].right <= wordRects[1].left + 1)
        assertTrue(
            "Single-letter word 'I' (${wordRects[0].width()}px) should be narrower than 'SAID' (${wordRects[1].width()}px)",
            wordRects[0].width() < wordRects[1].width() / 2
        )
    }

    @Test
    fun `splitLineIntoWords separates BUT I SAID NO into four word rects`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = ParSeqEngine(context)

        val bitmap = renderSyntheticLineBitmap(
            words = listOf("BUT", "I", "SAID", "NO"),
            bgColor = Color.WHITE,
            inkColor = Color.BLACK
        )

        val wordRects = engine.splitLineIntoWords(bitmap)

        assertEquals("Expected 'BUT I SAID NO' to split into 4 words", 4, wordRects.size)
        for (i in 0 until wordRects.size - 1) {
            assertTrue(
                "Word $i (${wordRects[i]}) must be strictly left of word ${i + 1} (${wordRects[i + 1]})",
                wordRects[i].left < wordRects[i + 1].left && wordRects[i].right <= wordRects[i + 1].left + 1
            )
        }
        assertTrue(
            "Single-letter word 'I' should remain narrower than 'BUT' and 'SAID'",
            wordRects[1].width() < wordRects[0].width() && wordRects[1].width() < wordRects[2].width()
        )
    }

    @Test
    fun `dark crop polarity inversion turns white-on-black into black-on-white`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = ParSeqEngine(context)

        val darkCrop = renderSyntheticLineBitmap(
            words = listOf("I", "SAID"),
            bgColor = Color.BLACK,
            inkColor = Color.WHITE
        )

        assertTrue("Dark crop should be detected as dark background", engine.isDarkBackgroundCrop(darkCrop))
        assertTrue(
            "Dark region should be detected as dark background",
            engine.isDarkBackgroundRegion(darkCrop, Rect(0, 0, darkCrop.width, darkCrop.height))
        )

        val normalized = engine.normalizePolarityIfDark(darkCrop)
        assertNotSame("Dark crop should return a newly inverted bitmap", darkCrop, normalized)
        assertFalse("Inverted crop should no longer look dark-backgrounded", engine.isDarkBackgroundCrop(normalized))

        val cornerPixel = normalized.getPixel(0, 0)
        assertTrue("Inverted background corner should be white", Color.red(cornerPixel) >= 245)

        // Check an ink pixel inside the 'I' stroke (padX=6, padY=5)
        val inkPixel = normalized.getPixel(7, 12)
        assertTrue("Inverted white text stroke should become dark ink", Color.red(inkPixel) <= 15)

        val splitWords = engine.splitLineIntoWords(normalized)
        assertEquals("Normalized inverted 'I SAID' should split into 2 words", 2, splitWords.size)
    }

    @Test
    fun `decodeParSeq rejects sequence when any character probability is below minCharProb`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = ParSeqEngine(context)

        val eosIndex = vocab.length
        val targetText = "1AP"
        val timesteps = targetText.length + 1
        val buffer = ByteBuffer.allocateDirect(timesteps * classCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        for (t in 0 until timesteps) {
            val base = t * classCount
            val targetIdx = if (t < targetText.length) vocab.indexOf(targetText[t]) else eosIndex
            for (c in 0 until classCount) {
                val logit = when {
                    t == 1 && c == targetIdx -> 0.5f // Weak probability (< 0.25) across 127 classes
                    t == 1 -> 0.0f
                    c == targetIdx -> 8.0f
                    else -> -8.0f
                }
                buffer.put(base + c, logit)
            }
        }
        buffer.position(0)

        val result = engine.decodeParSeq(buffer, timesteps, classCount, vocab)
        assertTrue("minCharConfidence (${result.minCharConfidence}) should be < 0.25f", result.minCharConfidence < 0.25f)
        assertEquals("Overall confidence must be 0f when a character is below minCharProb", 0f, result.confidence, 0.0001f)
    }

    @Test
    fun `parseVocabFromConfigJson preserves all 126 chars across escaped quotes`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = ParSeqEngine(context)

        val json = """
            {
              "mean": [0.694, 0.695, 0.693],
              "std": [0.299, 0.296, 0.301],
              "input_height": 32,
              "input_width": 128,
              "vocab": "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!\"#${'$'}%&'()*+,-./:;<=>?@[\\]^_`{|}~°£€¥¢฿àâéèêëîïôùûüçÀÂÉÈÊËÎÏÔÙÛÜÇ"
            }
        """.trimIndent()

        val parsed = engine.parseVocabFromConfigJson(json)
        assertEquals(vocab, parsed)
        assertEquals(126, parsed?.length)
    }

    @Test
    fun `splitLineIntoWords keeps hyphenated and apostrophe words intact for B-BUT and COULDN'T`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = ParSeqEngine(context)

        val hyphenSingle = renderSyntheticLineBitmap(
            words = listOf("B-BUT"),
            bgColor = Color.WHITE,
            inkColor = Color.BLACK
        )
        assertEquals(1, engine.splitLineIntoWords(hyphenSingle).size)

        val hyphenPhrase = renderSyntheticLineBitmap(
            words = listOf("B-BUT", "WHY"),
            bgColor = Color.WHITE,
            inkColor = Color.BLACK
        )
        assertEquals("B-BUT WHY should split into 2 words keeping B-BUT intact", 2, engine.splitLineIntoWords(hyphenPhrase).size)

        val apostropheWord = renderSyntheticLineBitmap(
            words = listOf("COULDN'T"),
            bgColor = Color.WHITE,
            inkColor = Color.BLACK
        )
        assertEquals("COULDN'T must stay a single word", 1, engine.splitLineIntoWords(apostropheWord).size)
    }

    @Test
    fun `prepareInputBitmap uses Color WHITE symmetric_pad and 2x upscale without black wall`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = ParSeqEngine(context)

        // Small tight "I" crop (< 36px height -> triggers 2x upscale)
        val narrowCrop = renderSyntheticLineBitmap(
            words = listOf("I"),
            bgColor = Color.WHITE,
            inkColor = Color.BLACK
        )
        val preparedNarrow = engine.prepareInputBitmap(narrowCrop)
        assertNotNull(preparedNarrow)
        preparedNarrow!!
        assertEquals(128, preparedNarrow.width)
        assertEquals(32, preparedNarrow.height)
        assertEquals("Left symmetric pad must be Color.WHITE", Color.WHITE, preparedNarrow.getPixel(0, 16))
        assertEquals("Right symmetric pad must be Color.WHITE (no black wall)", Color.WHITE, preparedNarrow.getPixel(127, 16))
        // Centered ink around x=64
        var hasDarkCenter = false
        for (x in 52..76) {
            if (Color.red(preparedNarrow.getPixel(x, 16)) < 80) {
                hasDarkCenter = true
                break
            }
        }
        assertTrue("Symmetric padding should center 'I' horizontally near x=64", hasDarkCenter)

        // Wide 240x24 crop (ratio = 10.0 > 4.0) with ink touching left & right edges
        val wideCrop = Bitmap.createBitmap(240, 24, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(wideCrop)
        canvas.drawColor(Color.WHITE)
        val inkPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        canvas.drawRect(0f, 2f, 240f, 22f, inkPaint)

        val prepared = engine.prepareInputBitmap(wideCrop)
        assertNotNull(prepared)
        prepared!!
        assertEquals(128, prepared.width)
        assertEquals(32, prepared.height)

        assertTrue("Top-left margin should remain white", Color.red(prepared.getPixel(0, 0)) >= 245)
        assertEquals("Bottom symmetric padding should be Color.WHITE", Color.WHITE, prepared.getPixel(64, 31))
        assertEquals("Top symmetric padding should be Color.WHITE", Color.WHITE, prepared.getPixel(64, 0))
    }

    @Test
    fun `mergeMultiStrings joins overlapping split crop outputs cleanly`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = ParSeqEngine(context)

        assertEquals(
            "CONGRATULATIONS",
            engine.mergeMultiStrings(listOf("CONGRAT", "ATULATIONS"))
        )
        assertEquals(
            "DON'T GO HOME UNTIL",
            engine.mergeMultiStrings(listOf("DON'T GO HO", "HOME UNTIL"))
        )
    }

    @Test
    fun `hasValidTextStrokes rejects blank or thin border line and accepts real text`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = ParSeqEngine(context)

        val blank = Bitmap.createBitmap(80, 30, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        assertFalse("Blank crop must be rejected", engine.hasValidTextStrokes(blank))

        val thinBorder = Bitmap.createBitmap(100, 30, Bitmap.Config.ARGB_8888)
        Canvas(thinBorder).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 14f, 100f, 16f, Paint().apply { color = Color.BLACK })
        }
        assertFalse("Single 2px horizontal line must be rejected", engine.hasValidTextStrokes(thinBorder))

        val validText = renderSyntheticLineBitmap(
            words = listOf("I", "SAID"),
            bgColor = Color.WHITE,
            inkColor = Color.BLACK
        )
        assertTrue("Real text crop must pass stroke gate", engine.hasValidTextStrokes(validText))
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
    fun `TextLineSegmenter preserves edge-touching letters on tight BUT crop`() {
        val segmenter = TextLineSegmenter()
        val crop = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(crop)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

        // 'B' touching left edge (x=0) and top edge (y=0)
        canvas.drawRect(0f, 0f, 5f, 36f, paint)
        canvas.drawRect(0f, 0f, 18f, 5f, paint)
        canvas.drawRect(0f, 16f, 18f, 20f, paint)
        canvas.drawRect(0f, 31f, 18f, 36f, paint)
        canvas.drawRect(14f, 4f, 19f, 32f, paint)

        // 'U' in the middle
        canvas.drawRect(36f, 4f, 41f, 36f, paint)
        canvas.drawRect(36f, 31f, 56f, 36f, paint)
        canvas.drawRect(51f, 4f, 56f, 36f, paint)

        // 'T' touching right edge (x=99) and bottom edge (y=39)
        canvas.drawRect(76f, 4f, 100f, 9f, paint)
        canvas.drawRect(86f, 4f, 91f, 40f, paint)

        val lines = segmenter.segment(
            bitmap = crop,
            region = Rect(0, 0, 100, 40),
            originLanguage = "İngilizce",
            forceHorizontal = true
        )

        assertTrue("Segmenter should produce at least one horizontal line", lines.isNotEmpty())
        val lineRect = lines.first().rect
        assertFalse("English text line must not be marked vertical", lines.first().vertical)
        assertTrue("Edge-touching 'B' must not be deleted (left=${lineRect.left})", lineRect.left <= 4)
        assertTrue("Edge-touching 'T' must not be deleted (right=${lineRect.right})", lineRect.right >= 95)
    }

    private fun renderSyntheticLineBitmap(
        words: List<String>,
        bgColor: Int,
        inkColor: Int
    ): Bitmap {
        val letterHeight = 22
        val letterGap = 2
        val wordGap = 9
        val padX = 6
        val padY = 5

        fun letterWidth(ch: Char): Int = when (ch) {
            'I' -> 3
            '\'' -> 3
            '-' -> 8
            else -> 12
        }

        var contentWidth = 0
        for ((wordIdx, word) in words.withIndex()) {
            if (wordIdx > 0) contentWidth += wordGap
            for ((charIdx, ch) in word.withIndex()) {
                if (charIdx > 0) contentWidth += letterGap
                contentWidth += letterWidth(ch)
            }
        }

        val width = contentWidth + padX * 2
        val height = letterHeight + padY * 2
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bgColor)
        val paint = Paint().apply { color = inkColor; style = Paint.Style.FILL }

        var cursorX = padX
        val top = padY.toFloat()
        val bottom = (padY + letterHeight).toFloat()
        val midY = top + letterHeight / 2f
        val stroke = 3f

        for ((wordIdx, word) in words.withIndex()) {
            if (wordIdx > 0) cursorX += wordGap
            for ((charIdx, ch) in word.withIndex()) {
                if (charIdx > 0) cursorX += letterGap
                val x = cursorX.toFloat()
                val w = letterWidth(ch).toFloat()
                when (ch) {
                    'I' -> canvas.drawRect(x, top, x + w, bottom, paint)
                    'B' -> {
                        canvas.drawRect(x, top, x + stroke, bottom, paint)
                        canvas.drawRect(x, top, x + w, top + stroke, paint)
                        canvas.drawRect(x, midY - stroke / 2f, x + w, midY + stroke / 2f, paint)
                        canvas.drawRect(x, bottom - stroke, x + w, bottom, paint)
                        canvas.drawRect(x + w - stroke, top, x + w, bottom, paint)
                    }
                    'U' -> {
                        canvas.drawRect(x, top, x + stroke, bottom, paint)
                        canvas.drawRect(x + w - stroke, top, x + w, bottom, paint)
                        canvas.drawRect(x, bottom - stroke, x + w, bottom, paint)
                    }
                    'T' -> {
                        canvas.drawRect(x, top, x + w, top + stroke, paint)
                        canvas.drawRect(x + w / 2f - stroke / 2f, top, x + w / 2f + stroke / 2f, bottom, paint)
                    }
                    'S' -> {
                        canvas.drawRect(x, top, x + w, top + stroke, paint)
                        canvas.drawRect(x, top, x + stroke, midY, paint)
                        canvas.drawRect(x, midY - stroke / 2f, x + w, midY + stroke / 2f, paint)
                        canvas.drawRect(x + w - stroke, midY, x + w, bottom, paint)
                        canvas.drawRect(x, bottom - stroke, x + w, bottom, paint)
                    }
                    'A' -> {
                        canvas.drawRect(x, top, x + stroke, bottom, paint)
                        canvas.drawRect(x + w - stroke, top, x + w, bottom, paint)
                        canvas.drawRect(x, top, x + w, top + stroke, paint)
                        canvas.drawRect(x, midY, x + w, midY + stroke, paint)
                    }
                    'D', 'O' -> {
                        canvas.drawRect(x, top, x + stroke, bottom, paint)
                        canvas.drawRect(x + w - stroke, top, x + w, bottom, paint)
                        canvas.drawRect(x, top, x + w, top + stroke, paint)
                        canvas.drawRect(x, bottom - stroke, x + w, bottom, paint)
                    }
                    'N' -> {
                        canvas.drawRect(x, top, x + stroke, bottom, paint)
                        canvas.drawRect(x + w - stroke, top, x + w, bottom, paint)
                        canvas.drawRect(x, top, x + w, top + stroke, paint)
                    }
                    'C' -> {
                        canvas.drawRect(x, top, x + stroke, bottom, paint)
                        canvas.drawRect(x, top, x + w, top + stroke, paint)
                        canvas.drawRect(x, bottom - stroke, x + w, bottom, paint)
                    }
                    'L' -> {
                        canvas.drawRect(x, top, x + stroke, bottom, paint)
                        canvas.drawRect(x, bottom - stroke, x + w, bottom, paint)
                    }
                    'W' -> {
                        canvas.drawRect(x, top, x + stroke, bottom, paint)
                        canvas.drawRect(x + w / 2f - stroke / 2f, midY, x + w / 2f + stroke / 2f, bottom, paint)
                        canvas.drawRect(x + w - stroke, top, x + w, bottom, paint)
                        canvas.drawRect(x, bottom - stroke, x + w, bottom, paint)
                    }
                    'H' -> {
                        canvas.drawRect(x, top, x + stroke, bottom, paint)
                        canvas.drawRect(x + w - stroke, top, x + w, bottom, paint)
                        canvas.drawRect(x, midY - stroke / 2f, x + w, midY + stroke / 2f, paint)
                    }
                    'Y' -> {
                        canvas.drawRect(x, top, x + stroke, midY, paint)
                        canvas.drawRect(x + w - stroke, top, x + w, midY, paint)
                        canvas.drawRect(x, midY, x + w, midY + stroke, paint)
                        canvas.drawRect(x + w / 2f - stroke / 2f, midY, x + w / 2f + stroke / 2f, bottom, paint)
                    }
                    '-' -> {
                        canvas.drawRect(x, midY - stroke / 2f, x + w, midY + stroke / 2f, paint)
                    }
                    '\'' -> {
                        canvas.drawRect(x, top, x + w, top + stroke * 2f, paint)
                    }
                }
                cursorX += letterWidth(ch)
            }
        }
        return bitmap
    }
}
