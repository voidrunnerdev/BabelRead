package com.example.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.example.ui.screens.computeBubblePaddingDp
import com.example.ui.screens.computeCanvasTextTransform
import com.example.ui.screens.resolveDrawIsRectangularBox
import com.example.ui.screens.resolveFloatingHaloStrokeColor
import com.example.ui.screens.resolveIsRectangularBox
import com.example.ui.screens.resolveOverlayBackgroundBoundsDp
import com.example.ui.screens.resolveShouldDrawFloatingHaloText
import com.example.ui.screens.shouldDrawNaturalBubbleBorder
import com.example.ui.screens.shouldDrawRectangularBorder
import com.example.ui.screens.shouldExpandMicroSliver
import java.util.Locale
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class BubbleTextLayoutOverflowTest {

    private val trLocale = Locale("tr", "TR")
    private val condensedBold: Typeface by lazy { Typeface.create("sans-serif-condensed", Typeface.BOLD) }

    private fun buildEllipticalContourProfile(rows: Int = 48): ContourScanlineProfile {
        val lefts = FloatArray(rows)
        val rights = FloatArray(rows)
        for (i in 0 until rows) {
            val ny = ((i + 0.5f) / rows) * 2f - 1f
            val halfSpan = 0.44f * sqrt((1f - ny * ny).coerceAtLeast(0f))
            lefts[i] = 0.5f - halfSpan
            rights[i] = 0.5f + halfSpan
        }
        return ContourScanlineProfile(lefts, rights)
    }

    @Test
    fun `wide curved bubble with usable contour is never forced rectangular by aspect ratio`() {
        val curvedContour = buildEllipticalContourProfile()
        assertTrue(curvedContour.isUsable)
        assertFalse(curvedContour.isRectangularLike)

        // Wide curved bubble (aspect = 260 / 130 = 2.0 > 1.25)
        val wideBubble = Rect(20, 40, 280, 170)
        assertFalse(
            "Layout pass must honor curved contour instead of aspect > 1.25f",
            resolveIsRectangularBox(
                isBubble = true,
                bubbleBox = wideBubble,
                targetBox = wideBubble,
                contourProfile = curvedContour
            )
        )
        assertFalse(
            "Draw pass must honor curved contour",
            resolveDrawIsRectangularBox(
                isBubble = true,
                bubbleBox = wideBubble,
                widthDp = 130f,
                heightDp = 65f,
                contourProfile = curvedContour
            )
        )

        // When contour is absent on a real speech bubble, layout (ELLIPSE) and draw (drawOval)
        // must both stay non-rectangular so corner glyphs never collide with the oval border.
        assertFalse(
            resolveIsRectangularBox(
                isBubble = true,
                bubbleBox = wideBubble,
                targetBox = wideBubble,
                contourProfile = null
            )
        )
        assertFalse(
            resolveDrawIsRectangularBox(
                isBubble = true,
                bubbleBox = wideBubble,
                widthDp = 130f,
                heightDp = 65f,
                contourProfile = null
            )
        )
        // Only non-bubble or empty-bubbleBox falls back to rectangular
        assertTrue(
            resolveIsRectangularBox(
                isBubble = true,
                bubbleBox = Rect(),
                targetBox = wideBubble,
                contourProfile = null
            )
        )

        // Rectangular contour IS classified rectangular
        val rectRows = 40
        val rectContour = ContourScanlineProfile(
            rowLeftFractions = FloatArray(rectRows) { 0.05f },
            rowRightFractions = FloatArray(rectRows) { 0.95f }
        )
        assertTrue(rectContour.isRectangularLike)
        assertTrue(
            resolveIsRectangularBox(
                isBubble = true,
                bubbleBox = wideBubble,
                targetBox = wideBubble,
                contourProfile = rectContour
            )
        )
        assertTrue(
            resolveDrawIsRectangularBox(
                isBubble = true,
                bubbleBox = wideBubble,
                widthDp = 130f,
                heightDp = 65f,
                contourProfile = rectContour
            )
        )
    }

    @Test
    fun `reader safe inset ratio enlarges border clearance while preserving default envelope contract`() {
        val bounds = RectF(0f, 0f, 200f, 120f)
        val defaultEnv = BubbleTextLayoutEngine.envelopeForBubble(
            bubbleBounds = bounds,
            horizontalPaddingDp = 4f,
            verticalPaddingDp = 4f,
            density = 1f
        )!!
        assertEquals(1.2f, defaultEnv.horizontalInsetPx, 0.01f)

        val readerEnv = BubbleTextLayoutEngine.envelopeForBubble(
            bubbleBounds = bounds,
            horizontalPaddingDp = 4f,
            verticalPaddingDp = 4f,
            density = 1f,
            safeInsetRatio = BubbleTextLayoutEngine.READER_SAFE_INSET_RATIO,
            minSafeInsetPx = BubbleTextLayoutEngine.READER_MIN_SAFE_INSET_PX,
            maxSafeInsetPx = BubbleTextLayoutEngine.READER_MAX_SAFE_INSET_PX
        )!!
        // min(200, 120) * 0.03 = 3.6px
        assertEquals(3.6f, readerEnv.horizontalInsetPx, 0.01f)
        assertEquals(3.6f, readerEnv.verticalInsetPx, 0.01f)

        // Clamped to minSafeInsetPx (2.5f) on small bubbles and maxSafeInsetPx (8f) on huge bubbles
        val smallEnv = BubbleTextLayoutEngine.envelopeForBubble(
            bubbleBounds = RectF(0f, 0f, 40f, 40f),
            horizontalPaddingDp = 2f,
            verticalPaddingDp = 2f,
            density = 1f,
            safeInsetRatio = BubbleTextLayoutEngine.READER_SAFE_INSET_RATIO,
            minSafeInsetPx = BubbleTextLayoutEngine.READER_MIN_SAFE_INSET_PX,
            maxSafeInsetPx = BubbleTextLayoutEngine.READER_MAX_SAFE_INSET_PX
        )!!
        assertEquals(2.5f, smallEnv.horizontalInsetPx, 0.01f)

        val largeEnv = BubbleTextLayoutEngine.envelopeForBubble(
            bubbleBounds = RectF(0f, 0f, 400f, 400f),
            horizontalPaddingDp = 5f,
            verticalPaddingDp = 5f,
            density = 1f,
            safeInsetRatio = BubbleTextLayoutEngine.READER_SAFE_INSET_RATIO,
            minSafeInsetPx = BubbleTextLayoutEngine.READER_MIN_SAFE_INSET_PX,
            maxSafeInsetPx = BubbleTextLayoutEngine.READER_MAX_SAFE_INSET_PX
        )!!
        assertEquals(8.0f, largeEnv.horizontalInsetPx, 0.01f)
    }

    @Test
    fun `long Turkish text in narrow curved contour stays inside scanline widths and safe inset`() {
        val bmp = Bitmap.createBitmap(150, 220, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawOval(10f, 10f, 140f, 210f, white)

        val contour = BubbleContourExtractor.extractFromBitmap(
            bitmap = bmp,
            bubbleBox = Rect(0, 0, 150, 220),
            anchorBox = Rect(35, 50, 115, 170),
            pageOriginalWidth = 150f,
            pageOriginalHeight = 220f
        )
        assertNotNull(contour)
        assertFalse(contour!!.isRectangularLike)

        val bubbleBoundsDp = RectF(0f, 0f, 75f, 110f)
        val (padH, padV) = computeBubblePaddingDp(
            widthDp = bubbleBoundsDp.width(),
            heightDp = bubbleBoundsDp.height(),
            isRectangularBox = false
        )
        val density = 2f
        val envelope = BubbleTextLayoutEngine.envelopeForBubble(
            bubbleBounds = bubbleBoundsDp,
            horizontalPaddingDp = padH,
            verticalPaddingDp = padV,
            density = density,
            contourProfile = contour,
            isRectangular = false,
            safeInsetRatio = BubbleTextLayoutEngine.READER_SAFE_INSET_RATIO,
            minSafeInsetPx = BubbleTextLayoutEngine.READER_MIN_SAFE_INSET_PX,
            maxSafeInsetPx = BubbleTextLayoutEngine.READER_MAX_SAFE_INSET_PX
        )!!
        assertEquals(4.5f, envelope.horizontalInsetPx, 0.05f) // min(150, 220) * 0.03 = 4.5px

        val text = "Hm... Sanırım burada biraz daha zaman alabilirim, ama hiç endişelenme!"
        val result = BubbleTextLayoutEngine.optimizeLayout(
            text = text,
            bubbleBounds = bubbleBoundsDp,
            horizontalPaddingDp = padH,
            verticalPaddingDp = padV,
            minSp = 6.0f,
            maxSp = 22f,
            density = density,
            spToPx = { it * density },
            layoutLocale = trLocale,
            targetLanguage = "tr",
            measureTypeface = condensedBold,
            allowBrokenWords = true,
            textScaleX = 0.80f,
            contourProfile = contour,
            isRectangular = false,
            safeInsetRatio = BubbleTextLayoutEngine.READER_SAFE_INSET_RATIO,
            minSafeInsetPx = BubbleTextLayoutEngine.READER_MIN_SAFE_INSET_PX,
            maxSafeInsetPx = BubbleTextLayoutEngine.READER_MAX_SAFE_INSET_PX
        )

        assertNotNull(result)
        assertTrue(result!!.fitsInside)
        val layout = result.bubbleTextLayout!!
        assertNotNull(layout.contourProfile)

        for (line in layout.lines) {
            assertTrue("Line top must be >= contentTop", line.topPx >= envelope.contentTop - 0.5f)
            assertTrue("Line bottom must be <= contentBottom", line.topPx + line.heightPx <= envelope.contentBottom + 0.5f)
            val span = envelope.spanAtBand(line.topPx, line.topPx + line.heightPx)
            assertTrue(
                "Line '${line.text}' leftPx=${line.leftPx} crossed contour span.leftPx=${span.leftPx}",
                line.leftPx >= span.leftPx - 0.5f
            )
            assertTrue(
                "Line '${line.text}' right=${line.leftPx + line.widthPx} crossed contour span.rightPx=${span.rightPx}",
                line.leftPx + line.widthPx <= span.rightPx + 0.5f
            )
        }
    }

    @Test
    fun `rectangular box padding keeps text clear of border stroke and canvas transform centers overflowed StaticLayout`() {
        val (rectPadH, rectPadV) = computeBubblePaddingDp(
            widthDp = 50f,
            heightDp = 32f,
            isRectangularBox = true
        )
        assertTrue("Rectangular box horizontal padding must be >= 3.5dp, got $rectPadH", rectPadH >= 3.5f)
        assertTrue("Rectangular box vertical padding must be >= 3.5dp, got $rectPadV", rectPadV >= 3.5f)

        // When StaticLayout has a line wider than staticLayout.width (120px vs 100px),
        // ALIGN_CENTER centers the line at x = layoutWidthPx / 2 = 50px (spanning -10..110).
        // The canvas transform must place x = 50px at canvasWidthPx / 2 = 50px so [-10..110] maps to [0..100].
        val transform = computeCanvasTextTransform(
            canvasWidthPx = 100f,
            canvasHeightPx = 60f,
            layoutWidthPx = 100f,
            layoutHeightPx = 40f,
            maxLineWidthPx = 120f
        )
        assertEquals(100f / 120f, transform.scale, 0.0001f)
        val mappedLeft = transform.translateX + (-10f) * transform.scale
        val mappedRight = transform.translateX + 110f * transform.scale
        assertEquals(0f, mappedLeft, 0.01f)
        assertEquals(100f, mappedRight, 0.01f)
    }

    @Test
    fun `narration box with isBubble false never draws oval or synthetic border`() {
        val box = Rect(40, 60, 180, 150) // aspect = 1.55 (previously < 2.2 fell into drawOval)
        assertTrue(
            "Narration box (!isBubble) without contour must resolve rectangular",
            resolveDrawIsRectangularBox(
                isBubble = false,
                bubbleBox = box,
                widthDp = 70f,
                heightDp = 45f,
                contourProfile = null
            )
        )
        assertTrue(
            "Narration box (!isBubble) even with curved text mask must stay rectangular",
            resolveDrawIsRectangularBox(
                isBubble = false,
                bubbleBox = box,
                widthDp = 70f,
                heightDp = 45f,
                contourProfile = buildEllipticalContourProfile()
            )
        )
        assertFalse(
            "Narration box (!isBubble) must not draw synthetic black border",
            shouldDrawRectangularBorder(
                isSubtitleMode = false,
                isRectangularBox = true,
                isBubble = false
            )
        )
        assertTrue(
            "Rectangular speech bubble (isBubble=true) keeps its border",
            shouldDrawRectangularBorder(
                isSubtitleMode = false,
                isRectangularBox = true,
                isBubble = true
            )
        )
    }

    @Test
    fun `oval fallback background expands to cover all four anchor corners`() {
        val bounds = resolveOverlayBackgroundBoundsDp(
            isSubtitleMode = false,
            isOvalFallback = true,
            anchorLeftDp = 50f,
            anchorTopDp = 100f,
            anchorRightDp = 130f,
            anchorBottomDp = 180f,
            bubbleLeftDp = 50f,
            bubbleTopDp = 100f,
            bubbleRightDp = 130f,
            bubbleBottomDp = 180f,
            placedLeftDp = 50f,
            placedTopDp = 100f,
            placedRightDp = 130f,
            placedBottomDp = 180f,
            marginDp = 2f
        )
        val cx = (bounds.left + bounds.right) * 0.5f
        val cy = (bounds.top + bounds.bottom) * 0.5f
        val rx = bounds.width * 0.5f
        val ry = bounds.height * 0.5f
        val corners = listOf(
            50f to 100f,
            130f to 100f,
            50f to 180f,
            130f to 180f
        )
        for ((x, y) in corners) {
            val nx = (x - cx) / rx
            val ny = (y - cy) / ry
            assertTrue(
                "Anchor corner ($x,$y) escaped oval fallback: nx^2+ny^2=${nx * nx + ny * ny}",
                nx * nx + ny * ny <= 1.0f
            )
        }
    }

    @Test
    fun `narrow vertical oval gets safe horizontal padding without shrinking normal bubbles`() {
        // Normal bubble remains unchanged at min(w * 0.045, 5)
        val (normalPadH, _) = computeBubblePaddingDp(
            widthDp = 120f,
            heightDp = 100f,
            isRectangularBox = false
        )
        assertEquals(5.0f, normalPadH, 0.01f)

        // Narrow vertical oval ("DING DONG!") gets protective horizontal clearance
        val (narrowPadH, _) = computeBubblePaddingDp(
            widthDp = 46f,
            heightDp = 120f,
            isRectangularBox = false
        )
        assertTrue("Narrow vertical oval padding must be >= 4.0dp, got $narrowPadH", narrowPadH >= 4.0f)

        val contour = buildEllipticalContourProfile()
        val bubbleBoundsDp = RectF(0f, 0f, 46f, 120f)
        val (padH, padV) = computeBubblePaddingDp(46f, 120f, isRectangularBox = false)
        val density = 2f
        val envelope = BubbleTextLayoutEngine.envelopeForBubble(
            bubbleBounds = bubbleBoundsDp,
            horizontalPaddingDp = padH,
            verticalPaddingDp = padV,
            density = density,
            contourProfile = contour,
            isRectangular = false,
            safeInsetRatio = BubbleTextLayoutEngine.READER_SAFE_INSET_RATIO,
            minSafeInsetPx = BubbleTextLayoutEngine.READER_MIN_SAFE_INSET_PX,
            maxSafeInsetPx = BubbleTextLayoutEngine.READER_MAX_SAFE_INSET_PX
        )!!
        val result = BubbleTextLayoutEngine.optimizeLayout(
            text = "DING DONG!",
            bubbleBounds = bubbleBoundsDp,
            horizontalPaddingDp = padH,
            verticalPaddingDp = padV,
            minSp = 6.0f,
            maxSp = 22f,
            density = density,
            spToPx = { it * density },
            layoutLocale = trLocale,
            targetLanguage = "tr",
            measureTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
            allowBrokenWords = false,
            textScaleX = 0.90f,
            contourProfile = contour,
            isRectangular = false,
            safeInsetRatio = BubbleTextLayoutEngine.READER_SAFE_INSET_RATIO,
            minSafeInsetPx = BubbleTextLayoutEngine.READER_MIN_SAFE_INSET_PX,
            maxSafeInsetPx = BubbleTextLayoutEngine.READER_MAX_SAFE_INSET_PX
        )
        assertNotNull(result)
        assertTrue(result!!.fitsInside)
        for (line in result.bubbleTextLayout!!.lines) {
            val span = envelope.spanAtBand(line.topPx, line.topPx + line.heightPx)
            assertTrue(line.leftPx >= span.leftPx - 0.5f)
            assertTrue(line.leftPx + line.widthPx <= span.rightPx + 0.5f)
        }
    }

    @Test
    fun `natural bubble border is suppressed when real mask bitmap preserves artwork outline`() {
        assertFalse(
            "Masked normal bubble must rely on artist's real brush stroke instead of synthetic 1.5dp border",
            shouldDrawNaturalBubbleBorder(
                isSubtitleMode = false,
                isBubble = true,
                hasRealMaskBitmap = true,
                isFallbackExpanded = false,
                bubbleStyle = "Doğal Balon"
            )
        )
        assertTrue(
            "Unmasked bubble draws fallback border",
            shouldDrawNaturalBubbleBorder(
                isSubtitleMode = false,
                isBubble = true,
                hasRealMaskBitmap = false,
                isFallbackExpanded = false,
                bubbleStyle = "Doğal Balon"
            )
        )
        assertTrue(
            "Expanded fallback bubble draws protective border",
            shouldDrawNaturalBubbleBorder(
                isSubtitleMode = false,
                isBubble = true,
                hasRealMaskBitmap = true,
                isFallbackExpanded = true,
                bubbleStyle = "Doğal Balon"
            )
        )
        assertTrue(
            "Dark theme style keeps contrast border",
            shouldDrawNaturalBubbleBorder(
                isSubtitleMode = false,
                isBubble = true,
                hasRealMaskBitmap = true,
                isFallbackExpanded = false,
                bubbleStyle = "Koyu Zemin"
            )
        )
        assertTrue(
            "Classic box style keeps border",
            shouldDrawNaturalBubbleBorder(
                isSubtitleMode = false,
                isBubble = true,
                hasRealMaskBitmap = true,
                isFallbackExpanded = false,
                bubbleStyle = "Klasik Kutu"
            )
        )
        assertFalse(
            shouldDrawNaturalBubbleBorder(
                isSubtitleMode = true,
                isBubble = true,
                hasRealMaskBitmap = false,
                isFallbackExpanded = true
            )
        )
    }

    @Test
    fun `floating free text without background color uses halo text instead of opaque box`() {
        assertTrue(
            resolveShouldDrawFloatingHaloText(
                isBubble = false,
                hasBackgroundColor = false,
                bubbleStyle = "Doğal Balon"
            )
        )
        assertFalse(
            "Narration box with detected backgroundColor keeps opaque box",
            resolveShouldDrawFloatingHaloText(
                isBubble = false,
                hasBackgroundColor = true,
                bubbleStyle = "Doğal Balon"
            )
        )
        assertFalse(
            resolveShouldDrawFloatingHaloText(
                isBubble = true,
                hasBackgroundColor = false,
                bubbleStyle = "Doğal Balon"
            )
        )
        assertFalse(
            resolveShouldDrawFloatingHaloText(
                isBubble = false,
                hasBackgroundColor = false,
                bubbleStyle = "Alt Yazı (Şeffaf)"
            )
        )
        assertEquals(
            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.92f),
            resolveFloatingHaloStrokeColor(androidx.compose.ui.graphics.Color.White)
        )
        assertEquals(
            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.96f),
            resolveFloatingHaloStrokeColor(androidx.compose.ui.graphics.Color.Black)
        )
    }

    @Test
    fun `rectangular bubble layout succeeds via rounded rectangle envelope and vertical pill resists horizontal widening`() {
        assertFalse(shouldExpandMicroSliver(widthDp = 18f, heightDp = 90f, isSliver = true))
        assertTrue(shouldExpandMicroSliver(widthDp = 10f, heightDp = 50f, isSliver = true))

        val density = 2f
        val rectResult = BubbleTextLayoutEngine.optimizeLayout(
            text = "Bu dikdörtgen kutu metni sorunsuz yerleşmeli!",
            bubbleBounds = RectF(0f, 0f, 120f, 64f),
            horizontalPaddingDp = 5f,
            verticalPaddingDp = 4f,
            minSp = 6.0f,
            maxSp = 20f,
            density = density,
            spToPx = { it * density },
            layoutLocale = trLocale,
            targetLanguage = "tr",
            measureTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
            isRectangular = true,
            safeInsetRatio = BubbleTextLayoutEngine.READER_SAFE_INSET_RATIO,
            minSafeInsetPx = BubbleTextLayoutEngine.READER_MIN_SAFE_INSET_PX,
            maxSafeInsetPx = BubbleTextLayoutEngine.READER_MAX_SAFE_INSET_PX
        )
        assertNotNull(rectResult)
        assertTrue("ROUNDED_RECTANGLE layout must fit cleanly", rectResult!!.fitsInside)
    }
}
