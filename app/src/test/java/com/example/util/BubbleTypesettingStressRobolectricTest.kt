package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import com.example.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class BubbleTypesettingStressRobolectricTest {

    private val trLocale = Locale("tr", "TR")
    private val condensedBold: Typeface by lazy { Typeface.create("sans-serif-condensed", Typeface.BOLD) }

    @Test
    fun `rectangular narration box contour fits larger Turkish text than analytical ellipse`() {
        val bmp = Bitmap.createBitmap(260, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val white = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawRect(8f, 8f, 252f, 172f, white)

        val bubbleBox = Rect(0, 0, 260, 180)
        val anchorBox = Rect(40, 40, 220, 140)
        val contour = BubbleContourExtractor.extractFromBitmap(
            bitmap = bmp,
            bubbleBox = bubbleBox,
            anchorBox = anchorBox,
            pageOriginalWidth = 260f,
            pageOriginalHeight = 180f,
            isDarkBubble = false
        )
        assertNotNull("Contour should be extracted from rectangular narration box", contour)

        val text = "Bu uzun anlatı kutusu metni dikdörtgen balonun üst ve alt köşelerini tamamen değerlendirmelidir."
        val boundsDp = RectF(0f, 0f, 130f, 90f)
        val contourFit = BubbleTextLayoutEngine.optimizeLayout(
            text = text,
            bubbleBounds = boundsDp,
            horizontalPaddingDp = 4f,
            verticalPaddingDp = 4f,
            minSp = 6f,
            maxSp = 22f,
            density = 2f,
            spToPx = { it * 2f },
            layoutLocale = trLocale,
            targetLanguage = "tr",
            measureTypeface = condensedBold,
            allowBrokenWords = true,
            contourProfile = contour
        )
        val ellipseFit = BubbleTextLayoutEngine.optimizeLayout(
            text = text,
            bubbleBounds = boundsDp,
            horizontalPaddingDp = 4f,
            verticalPaddingDp = 4f,
            minSp = 6f,
            maxSp = 22f,
            density = 2f,
            spToPx = { it * 2f },
            layoutLocale = trLocale,
            targetLanguage = "tr",
            measureTypeface = condensedBold,
            allowBrokenWords = true,
            contourProfile = null
        )

        assertNotNull(contourFit)
        assertNotNull(ellipseFit)
        assertTrue(contourFit!!.fitsInside)
        assertNotNull(contourFit.bubbleTextLayout?.contourProfile)
        assertTrue(
            "Expected contour (${contourFit.fontSizeSp}sp) > ellipse (${ellipseFit!!.fontSizeSp}sp)",
            contourFit.fontSizeSp > ellipseFit.fontSizeSp
        )
    }

    @Test
    fun `narrow vertical bubble fits agglutinative Turkish words with visible hyphen and stays in bounds`() {
        val bmp = Bitmap.createBitmap(120, 260, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawOval(6f, 6f, 114f, 254f, white)

        val bubbleBox = Rect(0, 0, 120, 260)
        val anchorBox = Rect(24, 50, 96, 210)
        val contour = BubbleContourExtractor.extractFromBitmap(
            bitmap = bmp,
            bubbleBox = bubbleBox,
            anchorBox = anchorBox,
            pageOriginalWidth = 120f,
            pageOriginalHeight = 260f
        )
        assertNotNull(contour)

        val text = "Karşılaşabileceğimiz sorumluluklarımızı üstlenebileceğimizden eminim!"
        val result = BubbleTextLayoutEngine.optimizeLayout(
            text = text,
            bubbleBounds = RectF(0f, 0f, 64f, 136f),
            horizontalPaddingDp = 3f,
            verticalPaddingDp = 4f,
            minSp = 6.5f,
            maxSp = 20f,
            density = 2f,
            spToPx = { it * 2f },
            layoutLocale = trLocale,
            targetLanguage = "tr",
            measureTypeface = condensedBold,
            allowBrokenWords = true,
            textScaleX = 0.88f,
            contourProfile = contour
        )

        assertNotNull("Narrow Turkish bubble must fit without fallback", result)
        assertTrue(result!!.fitsInside)
        assertTrue(result.fontSizeSp >= 6.5f)
        val layout = result.bubbleTextLayout!!
        assertTrue(layout.lines.any { it.text.endsWith("-") })
        for (line in layout.lines) {
            assertTrue("Line leftPx must be >= 0: ${line.leftPx}", line.leftPx >= -0.5f)
            assertTrue(
                "Line right must stay inside layout width: ${line.leftPx + line.widthPx} vs ${layout.widthPx}",
                line.leftPx + line.widthPx <= layout.widthPx + 0.5f
            )
            assertTrue(line.baselinePx.isFinite())
        }
    }

    @Test
    fun `spiky shout bubble does not bridge across deep black border wedges`() {
        val bmp = Bitmap.createBitmap(240, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawRect(12f, 12f, 228f, 188f, white)

        // Draw deep black spikes intruding from left (x=0..65) and right (x=175..240) at vertical center
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
        val leftSpike = Path().apply {
            moveTo(0f, 65f)
            lineTo(68f, 100f)
            lineTo(0f, 135f)
            close()
        }
        val rightSpike = Path().apply {
            moveTo(240f, 65f)
            lineTo(172f, 100f)
            lineTo(240f, 135f)
            close()
        }
        canvas.drawPath(leftSpike, black)
        canvas.drawPath(rightSpike, black)

        val contour = BubbleContourExtractor.extractFromBitmap(
            bitmap = bmp,
            bubbleBox = Rect(0, 0, 240, 200),
            anchorBox = Rect(80, 70, 160, 130),
            pageOriginalWidth = 240f,
            pageOriginalHeight = 200f
        )
        assertNotNull(contour)
        val midRow = contour!!.rowCount / 2
        val midLeftPx = contour.rowLeftFractions[midRow] * 240f
        val midRightPx = contour.rowRightFractions[midRow] * 240f
        assertTrue("Left spike should constrain midLeftPx >= 55f, got $midLeftPx", midLeftPx >= 55f)
        assertTrue("Right spike should constrain midRightPx <= 185f, got $midRightPx", midRightPx <= 185f)
    }

    @Test
    fun `dark speech bubble on white page extracts interior cleanly and fits Turkish text`() {
        val bmp = Bitmap.createBitmap(220, 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(18, 18, 18)
            style = Paint.Style.FILL
        }
        canvas.drawOval(10f, 10f, 210f, 150f, dark)

        val contour = BubbleContourExtractor.extractFromBitmap(
            bitmap = bmp,
            bubbleBox = Rect(0, 0, 220, 160),
            anchorBox = Rect(50, 40, 170, 120),
            pageOriginalWidth = 220f,
            pageOriginalHeight = 160f,
            isDarkBubble = true
        )
        assertNotNull(contour)

        val result = BubbleTextLayoutEngine.optimizeLayout(
            text = "Karanlıkta bile ne planladığını açıkça görebiliyorum!",
            bubbleBounds = RectF(0f, 0f, 110f, 80f),
            horizontalPaddingDp = 4f,
            verticalPaddingDp = 4f,
            minSp = 6.5f,
            maxSp = 20f,
            density = 2f,
            spToPx = { it * 2f },
            layoutLocale = trLocale,
            targetLanguage = "tr",
            measureTypeface = condensedBold,
            allowBrokenWords = true,
            contourProfile = contour
        )
        assertNotNull(result)
        assertTrue(result!!.fitsInside)
    }

    @Test
    fun `asymmetric bubble with bottom tail centers text inside upper-right speech chamber`() {
        val bmp = Bitmap.createBitmap(220, 260, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        // Upper-right speech chamber
        canvas.drawOval(60f, 12f, 212f, 176f, white)
        // Narrow tail extending downward
        val tail = Path().apply {
            moveTo(110f, 165f)
            lineTo(135f, 165f)
            lineTo(80f, 250f)
            close()
        }
        canvas.drawPath(tail, white)

        val contour = BubbleContourExtractor.extractFromBitmap(
            bitmap = bmp,
            bubbleBox = Rect(0, 0, 220, 260),
            anchorBox = Rect(90, 45, 190, 145),
            pageOriginalWidth = 220f,
            pageOriginalHeight = 260f
        )
        assertNotNull(contour)

        val result = BubbleTextLayoutEngine.optimizeLayout(
            text = "Buraya dikkat et, hemen harekete geçmeliyiz!",
            bubbleBounds = RectF(0f, 0f, 110f, 130f),
            horizontalPaddingDp = 3f,
            verticalPaddingDp = 3f,
            minSp = 6.5f,
            maxSp = 20f,
            density = 2f,
            spToPx = { it * 2f },
            layoutLocale = trLocale,
            targetLanguage = "tr",
            measureTypeface = condensedBold,
            allowBrokenWords = true,
            contourProfile = contour
        )
        assertNotNull(result)
        val layout = result!!.bubbleTextLayout!!
        val avgLineCenterX = layout.lines.map { it.leftPx + it.widthPx / 2f }.average().toFloat()
        val avgLineCenterY = layout.lines.map { it.topPx + it.heightPx / 2f }.average().toFloat()
        assertTrue("Expected right-shifted centerX > 55% of width, got $avgLineCenterX / ${layout.widthPx}", avgLineCenterX > layout.widthPx * 0.54f)
        assertTrue("Expected upper-chamber centerY < 50% of height, got $avgLineCenterY / ${layout.heightPx}", avgLineCenterY < layout.heightPx * 0.50f)
    }

    @Test
    fun `StaticTextLayoutOptimizer honors Turkish soft hyphens and textScaleX condensation`() {
        val raw = "Karşılaşabileceğimiz engelleri aşabilmek için hazırlanmalıyız!"
        val softHyphenated = TurkishSyllabifier.insertSoftHyphens(raw)
        val tightBox = RectF(0f, 0f, 68f, 82f)

        val normal = StaticTextLayoutOptimizer.optimizeLayout(
            text = softHyphenated,
            bounds = tightBox,
            minSp = 6f,
            maxSp = 18f,
            density = 2f,
            spToPx = { it * 2f },
            layoutLocale = trLocale,
            isOvalBubble = true,
            measureTypeface = condensedBold,
            allowBrokenWords = true,
            textScaleX = 1.0f
        )
        val condensed = StaticTextLayoutOptimizer.optimizeLayout(
            text = softHyphenated,
            bounds = tightBox,
            minSp = 6f,
            maxSp = 18f,
            density = 2f,
            spToPx = { it * 2f },
            layoutLocale = trLocale,
            isOvalBubble = true,
            measureTypeface = condensedBold,
            allowBrokenWords = true,
            textScaleX = 0.85f
        )

        assertTrue(condensed.fitsInside)
        assertNotNull(condensed.staticLayout)
        assertEquals(0.85f, condensed.staticLayout!!.paint.textScaleX, 0.001f)
        assertTrue(
            "Condensed (${condensed.fontSizeSp}sp) should be >= normal (${normal.fontSizeSp}sp)",
            condensed.fontSizeSp >= normal.fontSizeSp
        )
    }

    @Test
    fun `guards safely against recycled bitmap, tiny box, and NaN or Infinite textScaleX`() {
        val recycled = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        recycled.recycle()
        assertNull(
            BubbleContourExtractor.extractFromBitmap(
                bitmap = recycled,
                bubbleBox = Rect(0, 0, 40, 40),
                anchorBox = Rect(10, 10, 30, 30),
                pageOriginalWidth = 40f,
                pageOriginalHeight = 40f
            )
        )

        val validBmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        assertNull(
            BubbleContourExtractor.extractFromBitmap(
                bitmap = validBmp,
                bubbleBox = Rect(10, 10, 16, 16),
                anchorBox = Rect(11, 11, 15, 15),
                pageOriginalWidth = 100f,
                pageOriginalHeight = 100f
            )
        )

        val nanEnvelope = BubbleTextLayoutEngine.optimizeLayout(
            text = "Merhaba dünya",
            bubbleBounds = RectF(0f, 0f, 100f, 60f),
            horizontalPaddingDp = 4f,
            verticalPaddingDp = 4f,
            minSp = 6f,
            maxSp = 18f,
            density = 2f,
            spToPx = { it * 2f },
            layoutLocale = trLocale,
            targetLanguage = "tr",
            measureTypeface = condensedBold,
            textScaleX = Float.NaN
        )
        assertNotNull(nanEnvelope)
        assertEquals(1.0f, nanEnvelope!!.bubbleTextPaint!!.textScaleX, 0.001f)

        val infStatic = StaticTextLayoutOptimizer.optimizeLayout(
            text = "Merhaba dünya",
            bounds = RectF(0f, 0f, 100f, 60f),
            minSp = 6f,
            maxSp = 18f,
            density = 2f,
            spToPx = { it * 2f },
            layoutLocale = trLocale,
            textScaleX = Float.POSITIVE_INFINITY
        )
        assertTrue(infStatic.fitsInside)
        assertEquals(1.0f, infStatic.staticLayout!!.paint.textScaleX, 0.001f)
    }

    @Test
    fun `non-Turkish English text does not trigger Turkish diphthong syllabification`() {
        val result = BubbleTextLayoutEngine.optimizeLayout(
            text = "Although beautiful people thought through everything carefully",
            bubbleBounds = RectF(0f, 0f, 75f, 120f),
            horizontalPaddingDp = 3f,
            verticalPaddingDp = 3f,
            minSp = 6f,
            maxSp = 16f,
            density = 2f,
            spToPx = { it * 2f },
            layoutLocale = Locale.ENGLISH,
            targetLanguage = "en",
            measureTypeface = condensedBold,
            allowBrokenWords = true
        )
        assertNotNull(result)
        val lines = result!!.bubbleTextLayout!!.lines
        assertFalse("English diphthongs must not be split as Turkish", lines.any { it.text.endsWith("thro-") || it.text.endsWith("be-") })
    }

    @Test
    fun `condensed bold fits larger or equal font size than comic_neue with full Turkish diacritics`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val comicBase = ResourcesCompat.getFont(ctx, R.font.comic_neue) ?: Typeface.DEFAULT
        val comicBold = Typeface.create(comicBase, Typeface.BOLD)
        val turkishDiacritics = "Ğğ Şş İı Öö Üü Çç — Öğrencilerimizin karşılaştığı güçlükleri çözebiliriz!"

        val comicFit = BubbleTextLayoutEngine.optimizeLayout(
            text = turkishDiacritics,
            bubbleBounds = RectF(0f, 0f, 95f, 115f),
            horizontalPaddingDp = 4f,
            verticalPaddingDp = 4f,
            minSp = 6f,
            maxSp = 20f,
            density = 2f,
            spToPx = { it * 2f },
            layoutLocale = trLocale,
            targetLanguage = "tr",
            measureTypeface = comicBold,
            allowBrokenWords = true
        )
        val condensedFit = BubbleTextLayoutEngine.optimizeLayout(
            text = turkishDiacritics,
            bubbleBounds = RectF(0f, 0f, 95f, 115f),
            horizontalPaddingDp = 4f,
            verticalPaddingDp = 4f,
            minSp = 6f,
            maxSp = 20f,
            density = 2f,
            spToPx = { it * 2f },
            layoutLocale = trLocale,
            targetLanguage = "tr",
            measureTypeface = condensedBold,
            allowBrokenWords = true
        )

        assertNotNull(condensedFit)
        assertTrue(condensedFit!!.fitsInside)
        if (comicFit != null) {
            assertTrue(
                "Condensed (${condensedFit.fontSizeSp}sp) should be >= comic_neue (${comicFit.fontSizeSp}sp)",
                condensedFit.fontSizeSp >= comicFit.fontSizeSp
            )
        }
    }

    @Test
    fun `stress matrix across 16 varied bubble geometries and Turkish dialogues never crashes or produces NaN`() {
        val dialogues = listOf(
            "Dur!",
            "Bunu gerçekten başarabileceğimizi düşünüyor musun?",
            "Karşılaşabileceğimiz tüm engelleri aşmak için şimdi harekete geçmeliyiz!",
            "Bekle... o gölge az önce hareket mi etti? Yoksa bana mı öyle geldi?!"
        )
        val shapes = listOf(
            RectF(0f, 0f, 55f, 120f),   // narrow vertical
            RectF(0f, 0f, 150f, 65f),   // wide horizontal
            RectF(0f, 0f, 85f, 85f),    // medium square
            RectF(0f, 0f, 120f, 160f)   // tall generous
        )

        for (bounds in shapes) {
            for (dialogue in dialogues) {
                val res = BubbleTextLayoutEngine.optimizeLayout(
                    text = dialogue,
                    bubbleBounds = bounds,
                    horizontalPaddingDp = 3f,
                    verticalPaddingDp = 3f,
                    minSp = 6f,
                    maxSp = 24f,
                    density = 2f,
                    spToPx = { it * 2f },
                    layoutLocale = trLocale,
                    targetLanguage = "tr",
                    measureTypeface = condensedBold,
                    allowBrokenWords = true,
                    textScaleX = 0.88f
                ) ?: StaticTextLayoutOptimizer.optimizeLayout(
                    text = TurkishSyllabifier.insertSoftHyphens(dialogue),
                    bounds = RectF(0f, 0f, bounds.width() - 6f, bounds.height() - 6f),
                    minSp = 6f,
                    maxSp = 24f,
                    density = 2f,
                    spToPx = { it * 2f },
                    layoutLocale = trLocale,
                    measureTypeface = condensedBold,
                    allowBrokenWords = true,
                    textScaleX = 0.85f
                )
                assertTrue("Font size must be finite for '$dialogue' in $bounds", res.fontSizeSp.isFinite())
                assertTrue("Must fit inside for '$dialogue' in $bounds", res.fitsInside)
                assertTrue("Must have renderable layout", res.hasRenderableLayout)
            }
        }
    }
}
