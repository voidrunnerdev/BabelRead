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

    private fun drawOctagon(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, chamfer: Float, paint: Paint) {
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
    fun `detectDarkSpeechBubbles detects black octagonal bubble with white SO text and contour profile`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)
        val page = createWhitePage(480, 720)
        val canvas = Canvas(page)

        val blackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        drawOctagon(canvas, left = 160f, top = 240f, right = 320f, bottom = 360f, chamfer = 26f, paint = blackFill)

        val whiteText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("SO...", 240f, 310f, whiteText)

        val detections = detector.detectDarkSpeechBubbles(page, emptyList())
        assertEquals("Expected exactly 1 dark speech bubble detection", 1, detections.size)

        val det = detections.first()
        assertEquals(0, det.classId)
        assertEquals("bubble", det.label)
        assertTrue("Confidence should be substantial", det.confidence >= 0.70f)
        assertTrue("Expected left bound near bubble", det.box.left in 0.28f..0.40f)
        assertTrue("Expected right bound near bubble", det.box.right in 0.60f..0.72f)
        assertTrue("Expected top bound near bubble", det.box.top in 0.28f..0.38f)
        assertTrue("Expected bottom bound near bubble", det.box.bottom in 0.45f..0.55f)

        val profile = det.contourProfile
        assertNotNull("Dark bubble should generate ContourScanlineProfile", profile)
        assertTrue("ContourScanlineProfile should be usable", profile!!.isUsable)
        assertFalse("Octagonal bubble should not be classified as rectangular", profile.isRectangularLike)
    }

    @Test
    fun `detectDarkSpeechBubbles detects black jagged bubble with multi-line white dialogue`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)
        val page = createWhitePage(480, 720)
        val canvas = Canvas(page)

        val blackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        drawOctagon(canvas, left = 110f, top = 160f, right = 350f, bottom = 360f, chamfer = 30f, paint = blackFill)

        val whiteText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("DON'T GO HOME", 230f, 225f, whiteText)
        canvas.drawText("UNTIL YOU'VE", 230f, 255f, whiteText)
        canvas.drawText("FINISHED YOUR", 230f, 285f, whiteText)
        canvas.drawText("WORK. GET IT?", 230f, 315f, whiteText)

        val detections = detector.detectDarkSpeechBubbles(page, emptyList())
        assertEquals("Expected multi-line dark bubble detection", 1, detections.size)
        assertEquals("bubble", detections.first().label)
        assertNotNull(detections.first().contourProfile)
    }

    @Test
    fun `detectDarkSpeechBubbles rejects black hair, suit blobs, panel borders, and giant dark panels`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)
        val page = createWhitePage(480, 720)
        val canvas = Canvas(page)

        val blackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }

        // 1. Solid black hair blob without white lettering
        canvas.drawOval(RectF(40f, 40f, 180f, 170f), blackFill)

        // 2. Dark suit/hair blob with a single specular white highlight streak
        canvas.drawRoundRect(RectF(260f, 40f, 420f, 190f), 20f, 20f, blackFill)
        val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(295f, 80f, 385f, 105f, highlight)

        // 3. Thin 2px panel frame (separate from giant panel)
        val borderPaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(RectF(30f, 195f, 450f, 240f), borderPaint)

        // 4. Giant dark panel background (> 60% page width & height)
        canvas.drawRect(RectF(20f, 255f, 460f, 705f), blackFill)

        val detections = detector.detectDarkSpeechBubbles(page, emptyList())
        assertTrue("Expected zero false positives on non-bubble dark regions, got ${detections.size}", detections.isEmpty())
    }

    @Test
    fun `detectDarkSpeechBubbles deduplicates against existing YOLO bubble detections but not text_free`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)
        val page = createWhitePage(480, 720)
        val canvas = Canvas(page)

        val blackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        drawOctagon(canvas, left = 160f, top = 240f, right = 320f, bottom = 360f, chamfer = 26f, paint = blackFill)

        val whiteText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("SO...", 240f, 310f, whiteText)

        // Already covered by an existing YOLO bubble detection -> must not duplicate
        val existingBubble = YoloBubbleDetector.YoloDetection(
            classId = 0,
            label = "bubble",
            confidence = 0.28f,
            box = RectF(160f / 480f, 240f / 720f, 320f / 480f, 360f / 720f)
        )
        val afterBubble = detector.detectDarkSpeechBubbles(page, listOf(existingBubble))
        assertTrue("Existing YOLO bubble should prevent duplicate detection", afterBubble.isEmpty())

        // Mislabelled text_free inside the dark bubble should NOT prevent adding the bubble container
        val existingTextFree = YoloBubbleDetector.YoloDetection(
            classId = 2,
            label = "text_free",
            confidence = 0.55f,
            box = RectF(195f / 480f, 280f / 720f, 285f / 480f, 325f / 720f)
        )
        val afterTextFree = detector.detectDarkSpeechBubbles(page, listOf(existingTextFree))
        assertEquals("Existing text_free should not suppress dark bubble detection", 1, afterTextFree.size)
        assertEquals("bubble", afterTextFree.first().label)
    }

    @Test
    fun `default detector model and confidence threshold are aligned for YOLO11N_SEG`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = MangaPreferences(context)
        assertEquals(DetectorModel.YOLO11N_SEG.id, prefs.detectorModelType.first())
        assertEquals(0.20f, prefs.confidenceThreshold.first(), 0.001f)

        prefs.setDetectorModelType(DetectorModel.RTDETR_V2.id)
        assertEquals(0.35f, prefs.confidenceThreshold.first(), 0.001f)

        prefs.setDetectorModelType(DetectorModel.YOLO11N_SEG.id)
        assertEquals(0.20f, prefs.confidenceThreshold.first(), 0.001f)
    }
}
