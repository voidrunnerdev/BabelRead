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
    fun `hasDarkInterior accepts purple pastel bubble with white text and rejects gray hair shadow texture`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)
        val page = createWhitePage(480, 720)
        val canvas = Canvas(page)

        // 1. Purple pastel bubble (RGB [167, 144, 200], luma ~157) with white text
        val purpleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(167, 144, 200)
            style = Paint.Style.FILL
        }
        val purpleRect = RectF(120f, 120f, 340f, 250f)
        canvas.drawOval(purpleRect, purpleFill)
        val whiteText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("I MISSED", 230f, 178f, whiteText)
        canvas.drawText("YOU...", 230f, 214f, whiteText)

        // 2. Gray hair / shadow texture without bright white text peaks
        val hairBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(112, 112, 118)
            style = Paint.Style.FILL
        }
        val hairRect = RectF(120f, 360f, 340f, 490f)
        canvas.drawRect(hairRect, hairBg)
        val hairHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(156, 156, 162)
            strokeWidth = 5f
            style = Paint.Style.STROKE
        }
        for (i in 0..8) {
            val x = 135f + i * 22f
            canvas.drawLine(x, 370f, x + 12f, 480f, hairHighlight)
        }

        val purpleNorm = RectF(120f / 480f, 120f / 720f, 340f / 480f, 250f / 720f)
        val hairNorm = RectF(120f / 480f, 360f / 720f, 340f / 480f, 490f / 720f)

        assertTrue(
            "Purple pastel bubble with white text must pass hasDarkInterior",
            detector.hasDarkInterior(page, purpleNorm)
        )
        assertFalse(
            "Gray hair/shadow texture without white text peaks must be rejected",
            detector.hasDarkInterior(page, hairNorm)
        )

        val candidates = listOf(
            YoloBubbleDetector.YoloDetection(classId = 0, label = "bubble", confidence = 0.68f, box = purpleNorm),
            YoloBubbleDetector.YoloDetection(classId = 0, label = "bubble", confidence = 0.80f, box = hairNorm)
        )
        val rescued = detector.filterInvertedBubbleDetections(page, candidates, emptyList())
        assertEquals(1, rescued.size)
        assertEquals(purpleNorm, rescued.first().box)
    }

    @Test
    fun `default detector model and confidence threshold are aligned at 0_25f for YOLO11S_SEG`() = runBlocking {
        assertEquals(0.25f, DetectorModel.YOLO11S_SEG.defaultConfidence, 0.001f)
        assertEquals(0.25f, YoloBubbleDetector.DEFAULT_CONF_THRESHOLD, 0.001f)
        assertEquals(DetectorModel.YOLO11S_SEG, DetectorModel.fromId("yolo11n_seg"))
        assertEquals(DetectorModel.YOLO11S_SEG, DetectorModel.fromId(null))
        assertEquals(DetectorModel.RTDETR_V2, DetectorModel.fromId("rtdetr_v2"))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = MangaPreferences(context)
        assertEquals(DetectorModel.YOLO11S_SEG.id, prefs.detectorModelType.first())
        assertEquals(0.25f, prefs.confidenceThreshold.first(), 0.001f)

        prefs.setDetectorModelType(DetectorModel.RTDETR_V2.id)
        assertEquals(0.35f, prefs.confidenceThreshold.first(), 0.001f)

        // Legacy yolo11n_seg value redirects cleanly to YOLO11S_SEG
        prefs.setDetectorModelType("yolo11n_seg")
        assertEquals(DetectorModel.YOLO11S_SEG.id, prefs.detectorModelType.first())
        assertEquals(0.25f, prefs.confidenceThreshold.first(), 0.001f)
    }

    @Test
    fun `computeDynamicRectInputSize and metadata parsers preserve aspect ratio on stride 32`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)

        assertEquals(1024 to 1024, detector.parseImgszFromMetadata("[1024, 1024]"))
        assertEquals(1024 to 1024, detector.parseImgszFromMetadata("1024"))
        assertEquals(32, detector.parseStrideFromMetadata("32"))

        val vertical = detector.computeDynamicRectInputSize(1000, 1400, maxLongSide = 1024, stride = 32)
        assertEquals(736 to 1024, vertical)
        assertEquals(0, vertical.first % 32)
        assertEquals(0, vertical.second % 32)

        val spread = detector.computeDynamicRectInputSize(2400, 1600, maxLongSide = 1024, stride = 32)
        assertEquals(1024 to 704, spread)
        assertEquals(0, spread.first % 32)
        assertEquals(0, spread.second % 32)

        val ceiledPortrait = detector.computeDynamicRectInputSize(800, 1400, maxLongSide = 1024, stride = 32)
        assertEquals(608 to 1024, ceiledPortrait)
        assertEquals(0, ceiledPortrait.first % 32)
        assertEquals(0, ceiledPortrait.second % 32)

        val extremeStrip = detector.computeDynamicRectInputSize(40, 4000, maxLongSide = 1024, stride = 32)
        assertEquals(32 to 1024, extremeStrip)
    }

    @Test
    fun `extractSegmentationContourProfile uses bilinear interpolation for narrow vertical pills`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)

        val inputW = 736
        val inputH = 1024
        val letterbox = detector.computeLetterboxTransform(1000, 1400, inputW, inputH)
        val protoW = inputW / 4 // 184
        val protoH = inputH / 4 // 256
        val protoData = FloatArray(protoW * protoH) { -4f }

        // Narrow vertical pill: ~6 proto pixels wide (x=50..55), ~32 proto pixels tall (y=80..111)
        for (py in 80 until 112) {
            for (px in 51..54) {
                protoData[py * protoW + px] = 4f
            }
        }

        val normBox = RectF(
            (50f * 4f - letterbox.padLeft) / letterbox.contentW,
            (80f * 4f - letterbox.padTop) / letterbox.contentH,
            (56f * 4f - letterbox.padLeft) / letterbox.contentW,
            (112f * 4f - letterbox.padTop) / letterbox.contentH
        )
        val profile = detector.extractSegmentationContourProfile(
            normBox = normBox,
            letterbox = letterbox,
            protoData = protoData,
            protoW = protoW,
            protoH = protoH,
            maskCoeffs = floatArrayOf(1f),
            numMaskCoeffs = 1
        )
        assertNotNull("Narrow vertical pill (subW=6 >= 4) must produce bilinear contour profile", profile)
        assertTrue("Expected usable bilinear contour profile", profile!!.isUsable)
        assertTrue("Expected >= 8 upsampled rows", profile.rowCount >= 8)
        val midRow = profile.rowCount / 2
        assertTrue(profile.rowLeftFractions[midRow] < 0.35f)
        assertTrue(profile.rowRightFractions[midRow] > 0.65f)
    }

    @Test
    fun `extractSegmentationContourProfile aligns accurately on non-square dynamic rect proto masks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)

        val (inputW, inputH) = detector.computeDynamicRectInputSize(1000, 1400, maxLongSide = 1024, stride = 32)
        assertEquals(736, inputW)
        assertEquals(1024, inputH)

        val letterbox = detector.computeLetterboxTransform(1000, 1400, inputW, inputH)
        assertEquals(731, letterbox.contentW)
        assertEquals(1024, letterbox.contentH)
        assertEquals(2, letterbox.padLeft)
        assertEquals(0, letterbox.padTop)

        val protoW = inputW / 4 // 184
        val protoH = inputH / 4 // 256
        val protoData = FloatArray(protoW * protoH) { -4f }

        // Normalized page box [0.25..0.75, 0.25..0.50] -> model x ~[184.75..550.25] (proto ~[46.2..137.6]), y [256..512] (proto [64..128])
        for (py in 64 until 128) {
            for (px in 55 until 129) {
                protoData[py * protoW + px] = 4f
            }
        }

        val normBox = RectF(0.25f, 0.25f, 0.75f, 0.50f)
        val profile = detector.extractSegmentationContourProfile(
            normBox = normBox,
            letterbox = letterbox,
            protoData = protoData,
            protoW = protoW,
            protoH = protoH,
            maskCoeffs = floatArrayOf(1f),
            numMaskCoeffs = 1
        )
        assertNotNull("Expected non-null contour profile on dynamic rect proto", profile)
        assertTrue("Expected usable contour profile", profile!!.isUsable)

        val midRow = profile.rowCount / 2
        assertEquals(0.10f, profile.rowLeftFractions[midRow], 0.03f)
        assertEquals(0.90f, profile.rowRightFractions[midRow], 0.03f)
        assertEquals(0.50f, profile.opticalCenterX, 0.05f)
        assertEquals(0.50f, profile.opticalCenterY, 0.05f)
    }

    @Test
    fun `parseYoloSegmentationTensors handles 4-class and 1-class outputs with panel filtering and class-aware NMS`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)
        detector.dynamicClassNames = mapOf(0 to "bubble", 1 to "panel", 2 to "sfx", 3 to "text")

        val inputW = 736
        val inputH = 1024
        val letterbox = detector.computeLetterboxTransform(1000, 1400, inputW, inputH)
        val protoW = inputW / 4
        val protoH = inputH / 4
        val numProtos = 32
        val protoData = FloatArray(numProtos * protoH * protoW) { 4f }
        val protoShape = longArrayOf(1, numProtos.toLong(), protoH.toLong(), protoW.toLong())

        // 4-class output0: [1, 40, anchors] (4 box + 4 class + 32 mask coeffs)
        val anchors = 50
        val numFeatures4 = 40
        val detData4 = FloatArray(numFeatures4 * anchors)

        fun setCand(
            data: FloatArray,
            numAnchors: Int,
            idx: Int,
            cx: Float,
            cy: Float,
            w: Float,
            h: Float,
            scores: FloatArray,
            coeff0: Float = 1f
        ) {
            data[0 * numAnchors + idx] = cx
            data[1 * numAnchors + idx] = cy
            data[2 * numAnchors + idx] = w
            data[3 * numAnchors + idx] = h
            for (c in scores.indices) {
                data[(4 + c) * numAnchors + idx] = scores[c]
            }
            data[(4 + scores.size) * numAnchors + idx] = coeff0
        }

        // 0: bubble
        setCand(detData4, anchors, 0, 200f, 200f, 160f, 120f, floatArrayOf(0.90f, 0.01f, 0.02f, 0.03f))
        // 1: panel (must be filtered out)
        setCand(detData4, anchors, 1, 368f, 512f, 700f, 980f, floatArrayOf(0.01f, 0.96f, 0.01f, 0.01f))
        // 2: sfx overlapping bubble 0 (must NOT be suppressed by bubble and must NOT extract contour)
        setCand(detData4, anchors, 2, 205f, 205f, 150f, 110f, floatArrayOf(0.02f, 0.01f, 0.82f, 0.03f))
        // 3: text (must survive with contour)
        setCand(detData4, anchors, 3, 520f, 300f, 180f, 120f, floatArrayOf(0.04f, 0.02f, 0.01f, 0.85f))
        // 4 & 5: stacked snowman bubbles (IoU ~0.47, deltaY = 50 >= 0.28 * 140) -> both must survive
        setCand(detData4, anchors, 4, 400f, 650f, 200f, 140f, floatArrayOf(0.88f, 0.01f, 0.01f, 0.01f))
        setCand(detData4, anchors, 5, 400f, 700f, 200f, 140f, floatArrayOf(0.76f, 0.01f, 0.01f, 0.01f))
        // 6: duplicate of bubble 0 -> must be suppressed
        setCand(detData4, anchors, 6, 204f, 202f, 158f, 118f, floatArrayOf(0.65f, 0.01f, 0.01f, 0.01f))

        val dets4 = detector.parseYoloSegmentationTensors(
            detData = detData4,
            detShape = longArrayOf(1, numFeatures4.toLong(), anchors.toLong()),
            protoData = protoData,
            protoShape = protoShape,
            inputW = inputW,
            inputH = inputH,
            letterbox = letterbox,
            confThreshold = 0.25f
        )

        assertTrue("Panel detections must be filtered out", dets4.none { it.label == "panel" })
        assertEquals(3, dets4.count { it.label == "bubble" })
        assertEquals(1, dets4.count { it.label == "sfx" })
        assertEquals(1, dets4.count { it.label == "text" })
        assertTrue(
            "Bubble and text must produce contour profiles",
            dets4.filter { it.label in setOf("bubble", "text") }.all { it.contourProfile != null }
        )
        assertTrue(
            "SFX must skip contour extraction",
            dets4.first { it.label == "sfx" }.contourProfile == null
        )

        // 1-class backward compatibility: [1, 37, anchors] (4 box + 1 class + 32 mask coeffs)
        val numFeatures1 = 37
        val detData1 = FloatArray(numFeatures1 * anchors)
        setCand(detData1, anchors, 0, 300f, 400f, 180f, 140f, floatArrayOf(0.84f))
        val dets1 = detector.parseYoloSegmentationTensors(
            detData = detData1,
            detShape = longArrayOf(1, numFeatures1.toLong(), anchors.toLong()),
            protoData = protoData,
            protoShape = protoShape,
            inputW = inputW,
            inputH = inputH,
            letterbox = letterbox,
            confThreshold = 0.25f
        )
        assertEquals(1, dets1.size)
        assertEquals("bubble", dets1.first().label)
        assertNotNull(dets1.first().contourProfile)
    }

    @Test
    fun `shouldRunInvertedYoloPass skips normal white manga pages and enables dark or colored bubbles`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)

        // 1. Plain white page
        val plainWhite = createWhitePage(480, 720)
        assertFalse("Plain white page must skip inverted YOLO pass", detector.shouldRunInvertedYoloPass(plainWhite))

        // 2. Normal B&W manga page: black-outlined white bubble + black text + solid dark hair block
        val normalManga = createWhitePage(480, 720)
        val normalCanvas = Canvas(normalManga)
        val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        // Solid black hair/jacket region (~18% of page, no white text inside)
        normalCanvas.drawRect(RectF(0f, 0f, 480f, 130f), blackPaint)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        normalCanvas.drawOval(RectF(120f, 340f, 360f, 480f), outline)
        val blackText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        normalCanvas.drawText("HELLO WORLD", 240f, 418f, blackText)
        assertFalse(
            "Normal B&W manga page with black hair and black text must skip inverted pass",
            detector.shouldRunInvertedYoloPass(normalManga)
        )

        // 3. Dark bubble with white text
        val darkBubblePage = createWhitePage(480, 720)
        val darkCanvas = Canvas(darkBubblePage)
        drawOctagon(darkCanvas, 150f, 200f, 330f, 330f, 24f, blackPaint)
        val whiteText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        darkCanvas.drawText("SO...", 240f, 272f, whiteText)
        assertTrue(
            "Page with dark bubble and white text must enable inverted pass",
            detector.shouldRunInvertedYoloPass(darkBubblePage)
        )

        // 4. Purple pastel bubble with white text
        val purpleBubblePage = createWhitePage(480, 720)
        val purpleCanvas = Canvas(purpleBubblePage)
        val purplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(167, 144, 200)
            style = Paint.Style.FILL
        }
        purpleCanvas.drawOval(RectF(110f, 120f, 350f, 260f), purplePaint)
        purpleCanvas.drawText("I MISSED", 230f, 182f, whiteText)
        purpleCanvas.drawText("YOU...", 230f, 218f, whiteText)
        assertTrue(
            "Page with purple translucent bubble and white text must enable inverted pass",
            detector.shouldRunInvertedYoloPass(purpleBubblePage)
        )

        // 5. filterInvertedBubbleDetections rescues dark "text" boxes while rejecting white "text" boxes
        val darkTextPage = createWhitePage(480, 720)
        val darkTextCanvas = Canvas(darkTextPage)
        darkTextCanvas.drawRect(RectF(100f, 140f, 360f, 260f), blackPaint)
        darkTextCanvas.drawText("NARRATION", 230f, 208f, whiteText)
        darkTextCanvas.drawText("WHITE", 230f, 450f, blackText)

        val darkTextNorm = RectF(100f / 480f, 140f / 720f, 360f / 480f, 260f / 720f)
        val whiteTextNorm = RectF(100f / 480f, 400f / 720f, 360f / 480f, 490f / 720f)
        val rescuedTexts = detector.filterInvertedBubbleDetections(
            darkTextPage,
            listOf(
                YoloBubbleDetector.YoloDetection(classId = 3, label = "text", confidence = 0.78f, box = darkTextNorm),
                YoloBubbleDetector.YoloDetection(classId = 3, label = "text", confidence = 0.90f, box = whiteTextNorm)
            ),
            emptyList()
        )
        assertEquals(1, rescuedTexts.size)
        assertEquals("text", rescuedTexts.first().label)
        assertEquals(darkTextNorm, rescuedTexts.first().box)

        // 6. Single narrow dark vertical pill on an otherwise white page must trigger inverted pass
        val narrowPillPage = createWhitePage(800, 1200)
        val narrowCanvas = Canvas(narrowPillPage)
        narrowCanvas.drawRoundRect(RectF(720f, 380f, 754f, 620f), 16f, 16f, blackPaint)
        val smallWhiteText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        for (i in 0 until 6) {
            narrowCanvas.drawText("A", 737f, 420f + i * 32f, smallWhiteText)
        }
        assertTrue(
            "Single narrow dark pill with white text must trigger inverted pass",
            detector.shouldRunInvertedYoloPass(narrowPillPage)
        )
    }

    @Test
    fun `rescueTextBearingPanels promotes uniform rectangular text boxes while preserving real frame panels`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)
        val page = createWhitePage(600, 900)
        val canvas = Canvas(page)

        // 1. Small white rectangular bubble mislabeled as panel, containing a text box
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 22f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRect(RectF(60f, 80f, 270f, 210f), borderPaint)
        canvas.drawText("INSIDE", 165f, 150f, textPaint)

        // 2. Large scenic manga frame panel with non-uniform artwork (must remain in remainingPanels)
        val artPaint = Paint().apply { style = Paint.Style.FILL }
        for (row in 0 until 8) {
            artPaint.color = if (row % 2 == 0) Color.rgb(40, 40, 40) else Color.rgb(200, 200, 200)
            canvas.drawRect(RectF(40f, 300f + row * 60f, 560f, 360f + row * 60f), artPaint)
        }

        val smallPanelBox = RectF(60f / 600f, 80f / 900f, 270f / 600f, 210f / 900f)
        val smallTextBox = RectF(90f / 600f, 110f / 900f, 240f / 600f, 180f / 900f)
        val largeFrameBox = RectF(40f / 600f, 300f / 900f, 560f / 600f, 780f / 900f)

        val dummyProfile = com.example.util.ContourScanlineProfile(
            FloatArray(10) { 0.05f },
            FloatArray(10) { 0.95f },
            0.5f,
            0.5f
        )
        val textDet = YoloBubbleDetector.YoloDetection(3, "text", 0.85f, smallTextBox, dummyProfile)
        val smallPanel = YoloBubbleDetector.YoloDetection(1, "panel", 0.72f, smallPanelBox, dummyProfile)
        val largePanel = YoloBubbleDetector.YoloDetection(1, "panel", 0.93f, largeFrameBox, null)

        val (rescued, remainingPanels) = detector.rescueTextBearingPanels(
            bitmap = page,
            detections = listOf(textDet),
            panels = listOf(smallPanel, largePanel)
        )

        assertTrue(
            "Uniform small text-bearing panel must be promoted to bubble with contour intact",
            rescued.any { it.label == "bubble" && it.box == smallPanelBox && it.contourProfile == dummyProfile }
        )
        assertEquals(1, remainingPanels.size)
        assertEquals(largeFrameBox, remainingPanels.first().box)
    }

    @Test
    fun `fuseDetectionsWbf fuses overlapping bubbles preserving contour and maps missed RT-DETR detections`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = YoloBubbleDetector(context)

        val yoloContour = com.example.util.ContourScanlineProfile(
            FloatArray(10) { 0.1f },
            FloatArray(10) { 0.9f },
            0.5f,
            0.5f
        )
        val yoloBubble = YoloBubbleDetector.YoloDetection(
            classId = 0,
            label = "bubble",
            confidence = 0.80f,
            box = RectF(0.10f, 0.10f, 0.30f, 0.30f),
            contourProfile = yoloContour
        )
        val orphanText = YoloBubbleDetector.YoloDetection(
            classId = 3,
            label = "text",
            confidence = 0.75f,
            box = RectF(0.55f, 0.55f, 0.75f, 0.75f),
            contourProfile = null
        )
        assertTrue(detector.hasOrphanTextDetection(listOf(yoloBubble, orphanText)))

        val rtMatchingBubble = YoloBubbleDetector.YoloDetection(
            classId = 0,
            label = "bubble",
            confidence = 0.60f,
            box = RectF(0.11f, 0.11f, 0.31f, 0.31f)
        )
        val rtInnerTextBubble = YoloBubbleDetector.YoloDetection(
            classId = 1,
            label = "text_bubble",
            confidence = 0.70f,
            box = RectF(0.14f, 0.14f, 0.26f, 0.26f)
        )
        val rtMissedBubble = YoloBubbleDetector.YoloDetection(
            classId = 0,
            label = "bubble",
            confidence = 0.68f,
            box = RectF(0.52f, 0.52f, 0.78f, 0.78f)
        )
        val rtTextFree = YoloBubbleDetector.YoloDetection(
            classId = 2,
            label = "text_free",
            confidence = 0.66f,
            box = RectF(0.80f, 0.10f, 0.92f, 0.22f)
        )

        val fused = detector.fuseDetectionsWbf(
            yoloDetections = listOf(yoloBubble, orphanText),
            rtDetrDetections = listOf(rtMatchingBubble, rtInnerTextBubble, rtMissedBubble, rtTextFree)
        )

        assertEquals(2, fused.count { it.label == "bubble" })
        val fusedPrimary = fused.first { it.contourProfile != null }
        assertEquals(yoloContour, fusedPrimary.contourProfile)
        // Confidence-weighted average of 0.10 (w=0.8) and 0.11 (w=0.6) -> ~0.1043
        assertEquals(0.1043f, fusedPrimary.box.left, 0.002f)
        assertTrue("Inner text_bubble already covered by fused bubble must not duplicate", fused.none { it.label == "text_bubble" })
        assertEquals(1, fused.count { it.label == "sfx" })
        assertEquals(1, fused.count { it.label == "text" })

        val output = BubbleDetectionOutput(
            bubbles = listOf(BubbleBox(id = 1, bounds = fusedPrimary.box, confidence = 0.8f, label = "bubble")),
            panels = listOf(RectF(0.05f, 0.05f, 0.95f, 0.95f))
        )
        assertEquals(1, output.bubbles.size)
        assertEquals(1, output.panels.size)
    }
}
