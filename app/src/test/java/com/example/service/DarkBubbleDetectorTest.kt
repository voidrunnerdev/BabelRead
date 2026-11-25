package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import com.example.data.pref.MangaPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class DarkBubbleDetectorTest {

    private fun createWhitePage(width: Int = 480, height: Int = 720): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
    }

    private fun drawOctagon(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        chamfer: Float,
        paint: Paint
    ) {
        val path = Path().apply {
            moveTo(left + chamfer, top)
            lineTo(right - chamfer, top)
            lineTo(right, top + chamfer)
            lineTo(right, bottom - chamfer)
            lineTo(right - chamfer, bottom)
            lineTo(left + chamfer, bottom)
            lineTo(left, bottom - chamfer)
            lineTo(left, top + chamfer)
            close()
        }
        canvas.drawPath(path, paint)
    }

    @Test
    fun `computeLetterboxTransform and mapLetterboxBoxToNormalized preserve vertical and horizontal aspect ratios`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)

        // Vertical manga page: 1000 x 1400 into 1600 x 1600
        val vertical = detector.computeLetterboxTransform(1000, 1400, 1600, 1600)
        assertEquals(1143, vertical.contentW)
        assertEquals(1600, vertical.contentH)
        assertEquals(228, vertical.padLeft)
        assertEquals(0, vertical.padTop)

        val vertModelBox = RectF(
            vertical.padLeft + 0.20f * vertical.contentW,
            0.10f * vertical.contentH,
            vertical.padLeft + 0.60f * vertical.contentW,
            0.40f * vertical.contentH
        )
        val vertNorm = detector.mapLetterboxBoxToNormalized(vertModelBox, vertical)
        assertEquals(0.20f, vertNorm.left, 0.002f)
        assertEquals(0.10f, vertNorm.top, 0.002f)
        assertEquals(0.60f, vertNorm.right, 0.002f)
        assertEquals(0.40f, vertNorm.bottom, 0.002f)

        // Horizontal spread: 2400 x 1600 into 1600 x 1600
        val spread = detector.computeLetterboxTransform(2400, 1600, 1600, 1600)
        assertEquals(1600, spread.contentW)
        assertEquals(1067, spread.contentH)
        assertEquals(0, spread.padLeft)
        assertEquals(266, spread.padTop)

        // Box extending slightly into top padding must clamp cleanly to page top edge (0f)
        val spreadModelBox = RectF(
            0.15f * spread.contentW,
            spread.padTop - 12f,
            0.45f * spread.contentW,
            spread.padTop + 0.50f * spread.contentH
        )
        val spreadNorm = detector.mapLetterboxBoxToNormalized(spreadModelBox, spread)
        assertEquals(0.15f, spreadNorm.left, 0.002f)
        assertEquals(0.0f, spreadNorm.top, 0.0001f)
        assertEquals(0.45f, spreadNorm.right, 0.002f)
        assertEquals(0.50f, spreadNorm.bottom, 0.002f)
    }

    @Test
    fun `extractSegmentationContourProfile un-letterboxes 400x400 prototype mask without pad shift`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)

        // 800 x 1600 vertical page -> padLeft = 400, contentW = 800, contentH = 1600
        val letterbox = detector.computeLetterboxTransform(800, 1600, 1600, 1600)
        assertEquals(400, letterbox.padLeft)
        assertEquals(800, letterbox.contentW)

        val protoW = 400
        val protoH = 400
        val protoData = FloatArray(protoW * protoH) { -4f }

        // Normalized page box [0.0..0.5, 0.25..0.50] -> model x [400..800] (proto [100..200]), y [400..800] (proto [100..200])
        // Also put stray positive logits inside gray padding (proto x 90..99) to verify padding is never sampled.
        for (py in 100 until 200) {
            for (px in 90 until 200) {
                val insideInsetBubble = px in 110 until 190
                val strayPadding = px in 92 until 98
                if (insideInsetBubble || strayPadding) {
                    protoData[py * protoW + px] = 4f
                }
            }
        }

        val normBox = RectF(0.0f, 0.25f, 0.50f, 0.50f)
        val profile = detector.extractSegmentationContourProfile(
            normBox = normBox,
            letterbox = letterbox,
            protoData = protoData,
            protoW = protoW,
            protoH = protoH,
            maskCoeffs = floatArrayOf(1f),
            numMaskCoeffs = 1
        )
        assertNotNull("Expected non-null contour profile", profile)
        assertTrue("Expected usable contour profile", profile!!.isUsable)

        val midRow = profile.rowCount / 2
        // Expected span inside normBox [proto 100..200] is [110..190] -> fractions [0.10..0.90]
        assertEquals(0.10f, profile.rowLeftFractions[midRow], 0.03f)
        assertEquals(0.90f, profile.rowRightFractions[midRow], 0.03f)
        assertEquals(0.50f, profile.opticalCenterX, 0.05f)
        assertEquals(0.50f, profile.opticalCenterY, 0.05f)
    }

    @Test
    fun `filterInvertedBubbleDetections keeps dark interior bubbles and rejects white bubbles or duplicates`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)
        val page = createWhitePage(480, 720)
        val canvas = Canvas(page)

        // 1. Dark octagonal bubble with white "SO..." text
        val blackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        drawOctagon(canvas, left = 160f, top = 200f, right = 320f, bottom = 320f, chamfer = 26f, paint = blackFill)
        val whiteText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("SO...", 240f, 270f, whiteText)

        // 2. White bubble with black text (must be rejected by dark interior gate)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        canvas.drawOval(RectF(140f, 400f, 340f, 520f), outline)
        val blackText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("HELLO", 240f, 468f, blackText)

        val darkCandBox = RectF(160f / 480f, 200f / 720f, 320f / 480f, 320f / 720f)
        val whiteCandBox = RectF(140f / 480f, 400f / 720f, 340f / 480f, 520f / 720f)

        val invertedCandidates = listOf(
            YoloBubbleDetector.YoloDetection(classId = 0, label = "bubble", confidence = 0.72f, box = darkCandBox),
            YoloBubbleDetector.YoloDetection(classId = 0, label = "bubble", confidence = 0.85f, box = whiteCandBox)
        )

        assertTrue(detector.hasDarkInterior(page, darkCandBox))
        assertFalse(detector.hasDarkInterior(page, whiteCandBox))

        // Without Pass-1 overlap: dark bubble survives, white bubble is filtered out
        val rescued = detector.filterInvertedBubbleDetections(page, invertedCandidates, emptyList())
        assertEquals(1, rescued.size)
        assertEquals(darkCandBox, rescued.first().box)

        // With Pass-1 bubble covering darkCandBox: must not duplicate
        val primaryBubble = YoloBubbleDetector.YoloDetection(classId = 0, label = "bubble", confidence = 0.40f, box = darkCandBox)
        val afterPrimaryOverlap = detector.filterInvertedBubbleDetections(page, invertedCandidates, listOf(primaryBubble))
        assertTrue("Overlapping Pass-1 bubble must suppress duplicate", afterPrimaryOverlap.isEmpty())

        // Non-bubble text_free in Pass-1 must not suppress rescued dark bubble
        val primaryTextFree = YoloBubbleDetector.YoloDetection(
            classId = 2,
            label = "text_free",
            confidence = 0.55f,
            box = RectF(195f / 480f, 240f / 720f, 285f / 480f, 285f / 720f)
        )
        val afterTextFree = detector.filterInvertedBubbleDetections(page, invertedCandidates, listOf(primaryTextFree))
        assertEquals(1, afterTextFree.size)
    }

    @Test
    fun `default detector model and confidence threshold are aligned at 0_25f for YOLO11N_SEG`() = runBlocking {
        assertEquals(0.25f, DetectorModel.YOLO11N_SEG.defaultConfidence, 0.001f)
        assertEquals(0.25f, YoloBubbleDetector.DEFAULT_CONF_THRESHOLD, 0.001f)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = MangaPreferences(context)
        assertEquals(DetectorModel.YOLO11N_SEG.id, prefs.detectorModelType.first())
        assertEquals(0.25f, prefs.confidenceThreshold.first(), 0.001f)

        prefs.setDetectorModelType(DetectorModel.RTDETR_V2.id)
        assertEquals(0.35f, prefs.confidenceThreshold.first(), 0.001f)

        prefs.setDetectorModelType(DetectorModel.YOLO11N_SEG.id)
        assertEquals(0.25f, prefs.confidenceThreshold.first(), 0.001f)
    }
}
