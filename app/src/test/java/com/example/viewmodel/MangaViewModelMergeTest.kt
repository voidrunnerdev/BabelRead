package com.example.viewmodel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.example.service.TranslationService
import com.example.util.BubbleLayoutGeometry
import com.example.util.ContourScanlineProfile
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
class MangaViewModelMergeTest {

    @Test
    fun `mergeSameBubbleFragments drops dwarf single-char ghost O while keeping dialogue`() {
        val balloon = Rect(20, 20, 220, 340)
        val mainDialogue = BubbleCandidate(
            targetBox = Rect(35, 45, 205, 165),
            text = "Hey, bilge adam!",
            textRect = Rect(35, 45, 205, 165),
            isBubble = true,
            isDark = false,
            bubbleBounds = balloon
        )
        val ghostO = BubbleCandidate(
            targetBox = Rect(95, 195, 125, 225),
            text = "O",
            textRect = Rect(95, 195, 125, 225),
            isBubble = true,
            isDark = false,
            bubbleBounds = balloon
        )

        val merged = BubbleCandidateMerger.mergeSameBubbleFragments(listOf(mainDialogue, ghostO))

        assertEquals(1, merged.size)
        assertEquals("Hey, bilge adam!", merged[0].text)
    }

    @Test
    fun `mergeSameBubbleFragments drops overlapping single-char ghost and keeps inline I`() {
        val balloon = Rect(0, 0, 240, 140)
        val inlineI = BubbleCandidate(
            targetBox = Rect(20, 40, 32, 70),
            text = "I",
            textRect = Rect(20, 40, 32, 70),
            isBubble = true,
            isDark = false,
            bubbleBounds = balloon
        )
        val restOfLine = BubbleCandidate(
            targetBox = Rect(40, 40, 210, 72),
            text = "think so too.",
            textRect = Rect(40, 40, 210, 72),
            isBubble = true,
            isDark = false,
            bubbleBounds = balloon
        )
        val overlappingO = BubbleCandidate(
            targetBox = Rect(80, 44, 102, 68),
            text = "O",
            textRect = Rect(80, 44, 102, 68),
            isBubble = true,
            isDark = false,
            bubbleBounds = balloon
        )

        val merged = BubbleCandidateMerger.mergeSameBubbleFragments(
            listOf(inlineI, restOfLine, overlappingO)
        )

        assertEquals(1, merged.size)
        assertEquals("I think so too.", merged[0].text)
    }

    @Test
    fun `mergeSameBubbleFragments preserves standalone single-char balloon`() {
        val balloon = Rect(10, 10, 90, 90)
        val singleCharOnly = BubbleCandidate(
            targetBox = Rect(35, 30, 65, 70),
            text = "I",
            textRect = Rect(35, 30, 65, 70),
            isBubble = true,
            isDark = false,
            bubbleBounds = balloon
        )

        val merged = BubbleCandidateMerger.mergeSameBubbleFragments(listOf(singleCharOnly))

        assertEquals(1, merged.size)
        assertEquals("I", merged[0].text)
    }

    @Test
    fun `mergeSameBubbleFragments joins normal-spaced fragments inside tall balloon`() {
        val tallBalloon = Rect(10, 10, 190, 310)
        val topPart = BubbleCandidate(
            targetBox = Rect(30, 30, 170, 95),
            text = "MENTO-",
            textRect = Rect(40, 40, 160, 72),
            isBubble = true,
            isDark = false,
            bubbleBounds = tallBalloon
        )
        val continuation = BubbleCandidate(
            targetBox = Rect(25, 110, 175, 220),
            text = "Hey, bilge!",
            textRect = Rect(35, 118, 165, 152),
            isBubble = true,
            isDark = false,
            bubbleBounds = tallBalloon
        )

        val merged = BubbleCandidateMerger.mergeSameBubbleFragments(listOf(topPart, continuation))

        assertEquals(1, merged.size)
        assertEquals("MENTOHey, bilge!", merged[0].text)
    }

    @Test
    fun `mergeSameBubbleFragments splits twin-lobe balloon bounds so texts never overlap`() {
        val twinBalloon = Rect(0, 0, 180, 400)
        val profile = ContourScanlineProfile(
            rowLeftFractions = FloatArray(20) { r -> if (r in 8..11) 0.25f else 0.08f },
            rowRightFractions = FloatArray(20) { r -> if (r in 8..11) 0.75f else 0.92f },
            opticalCenterX = 0.5f,
            opticalCenterY = 0.5f
        )
        val upperLobe = BubbleCandidate(
            targetBox = Rect(20, 40, 160, 360),
            text = "First lobe line",
            textRect = Rect(25, 35, 155, 70),
            isBubble = true,
            isDark = false,
            bubbleBounds = twinBalloon,
            contourProfile = profile
        )
        val lowerLobe = BubbleCandidate(
            targetBox = Rect(20, 40, 160, 360),
            text = "Second lobe line",
            textRect = Rect(25, 290, 155, 325),
            isBubble = true,
            isDark = false,
            bubbleBounds = twinBalloon,
            contourProfile = profile
        )

        val result = BubbleCandidateMerger.mergeSameBubbleFragments(listOf(upperLobe, lowerLobe))

        assertEquals(2, result.size)
        val first = result[0]
        val second = result[1]
        assertEquals("First lobe line", first.text)
        assertEquals("Second lobe line", second.text)
        assertFalse(
            "Split twin bubbleBounds must not intersect",
            Rect.intersects(first.bubbleBounds, second.bubbleBounds)
        )
        assertFalse(
            "Split twin targetBoxes must not intersect",
            Rect.intersects(first.targetBox, second.targetBox)
        )
        assertTrue(first.bubbleBounds.contains(first.textRect))
        assertTrue(second.bubbleBounds.contains(second.textRect))
        assertTrue(first.bubbleBounds.contains(first.targetBox))
        assertTrue(second.bubbleBounds.contains(second.targetBox))
        assertEquals((70 + 290) / 2, first.bubbleBounds.bottom)
        assertEquals(first.bubbleBounds.bottom, second.bubbleBounds.top)
        assertNotNull(first.contourProfile)
        assertNotNull(second.contourProfile)
        assertTrue(first.contourProfile!!.isUsable)
        assertTrue(second.contourProfile!!.isUsable)
    }

    @Test
    fun `ensureNonOverlappingBubbleOwners splits three lobes and horizontal twins cleanly`() {
        val tallBalloon = Rect(0, 0, 160, 480)
        val lobes = listOf(
            BubbleCandidate(Rect(tallBalloon), "Top", Rect(20, 20, 140, 50), true, false, tallBalloon),
            BubbleCandidate(Rect(tallBalloon), "Middle", Rect(20, 200, 140, 230), true, false, tallBalloon),
            BubbleCandidate(Rect(tallBalloon), "Bottom", Rect(20, 390, 140, 420), true, false, tallBalloon)
        )
        val splitThree = BubbleCandidateMerger.ensureNonOverlappingBubbleOwners(lobes)
        assertEquals(3, splitThree.size)
        assertFalse(Rect.intersects(splitThree[0].bubbleBounds, splitThree[1].bubbleBounds))
        assertFalse(Rect.intersects(splitThree[1].bubbleBounds, splitThree[2].bubbleBounds))
        assertFalse(Rect.intersects(splitThree[0].bubbleBounds, splitThree[2].bubbleBounds))

        val wideBalloon = Rect(0, 0, 420, 120)
        val horizontalTwins = listOf(
            BubbleCandidate(Rect(wideBalloon), "Left half", Rect(20, 35, 110, 75), true, false, wideBalloon),
            BubbleCandidate(Rect(wideBalloon), "Right half", Rect(290, 35, 390, 75), true, false, wideBalloon)
        )
        val splitHoriz = BubbleCandidateMerger.ensureNonOverlappingBubbleOwners(horizontalTwins)
        assertEquals(2, splitHoriz.size)
        assertFalse(Rect.intersects(splitHoriz[0].bubbleBounds, splitHoriz[1].bubbleBounds))
        assertFalse(Rect.intersects(splitHoriz[0].targetBox, splitHoriz[1].targetBox))
        assertEquals(200, splitHoriz[0].bubbleBounds.right)
        assertEquals(200, splitHoriz[1].bubbleBounds.left)
    }

    @Test
    fun `mergeOcrBlocksWithFallback skips duplicates and keeps missed regions`() {
        val primary = listOf(
            TranslationService.RectAndText(Rect(20, 20, 160, 60), "Already found")
        )
        val fallback = listOf(
            TranslationService.RectAndText(Rect(24, 22, 155, 58), "Already found"),
            TranslationService.RectAndText(Rect(300, 400, 460, 450), "WE MET...")
        )

        val merged = FallbackBubbleSupport.mergeOcrBlocksWithFallback(primary, fallback)

        assertEquals(2, merged.size)
        assertEquals("Already found", merged[0].text)
        assertEquals("WE MET...", merged[1].text)
    }

    @Test
    fun `dark and purple semi-transparent bubbles with white text are detected without runaway refinedBox`() {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val canvas = Canvas(bitmap)

        // Purple semi-transparent bubble with white text
        val purplePaint = Paint().apply { color = Color.rgb(167, 144, 200) }
        canvas.drawRect(40f, 40f, 240f, 140f, purplePaint)
        val whiteTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
        }
        canvas.drawText("I missed you", 65f, 100f, whiteTextPaint)

        // White bubble with dark text
        val blackTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
        }
        canvas.drawText("Normal line", 65f, 260f, blackTextPaint)

        val purpleTextRect = Rect(60, 72, 220, 112)
        val whiteTextRect = Rect(60, 232, 220, 272)

        assertTrue(FallbackBubbleSupport.isBubbleInteriorDark(bitmap, Rect(40, 40, 240, 140)))
        assertTrue(FallbackBubbleSupport.isColoredOrDarkWithLightText(bitmap, purpleTextRect))
        assertFalse(FallbackBubbleSupport.isColoredOrDarkWithLightText(bitmap, whiteTextRect))

        val runawayRefined = Rect(0, 0, 390, 380)
        val bounded = FallbackBubbleSupport.boundFallbackRefinedBox(
            bitmap = bitmap,
            textRect = purpleTextRect,
            rawRefined = runawayRefined,
            isDarkBubble = true
        )
        assertTrue(bounded.width() <= purpleTextRect.width() + 32)
        assertTrue(bounded.height() <= purpleTextRect.height() + 28)
        assertTrue(bounded.contains(purpleTextRect))

        bitmap.recycle()
    }

    @Test
    fun `shouldRunFallbackLineScan skips full-page DBNet when YOLO regions already yielded OCR blocks`() {
        assertFalse(
            FallbackBubbleSupport.shouldRunFallbackLineScan(
                isPpOcrAny = true,
                regionCount = 4,
                primaryBlockCount = 4
            )
        )
        assertTrue(
            FallbackBubbleSupport.shouldRunFallbackLineScan(
                isPpOcrAny = true,
                regionCount = 0,
                primaryBlockCount = 0
            )
        )
        assertTrue(
            FallbackBubbleSupport.shouldRunFallbackLineScan(
                isPpOcrAny = true,
                regionCount = 3,
                primaryBlockCount = 0
            )
        )
        assertFalse(
            FallbackBubbleSupport.shouldRunFallbackLineScan(
                isPpOcrAny = false,
                regionCount = 0,
                primaryBlockCount = 0
            )
        )
    }

    @Test
    fun `vertical text narration box without balloon keeps rectangular contour and never forces oval`() {
        val pageBitmap = Bitmap.createBitmap(300, 500, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val canvas = Canvas(pageBitmap)
        val borderPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val narrationBox = Rect(40, 40, 140, 320)
        canvas.drawRect(narrationBox, borderPaint)
        val textBounds = Rect(55, 65, 125, 295)

        // Real balloons list is empty (only "text" narration box was detected).
        val containingBalloon = FallbackBubbleSupport.selectContainingBalloon(
            textRect = textBounds,
            balloonBoxes = emptyList()
        )
        assertNull(containingBalloon)

        val contour = FallbackBubbleSupport.resolveNonBalloonContour(
            bitmap = pageBitmap,
            textBounds = textBounds,
            bubbleBounds = narrationBox,
            isDarkBubble = false,
            dialogueLabel = "text"
        )
        assertNotNull(contour)
        assertTrue(contour!!.isUsable)
        assertTrue("Narration text box without balloon must stay rectangular", contour.isRectangularLike)

        val rectLayout = BubbleLayoutGeometry.centeredLayoutBox(
            anchor = textBounds,
            bubble = narrationBox,
            isRectangular = true
        )
        assertNotNull(rectLayout)
        assertTrue(rectLayout!!.width() >= (narrationBox.width() * 0.85f).toInt())

        // When YOLO missed a dark/colored oval (dialogueLabel == null) and contour extraction returns null,
        // resolveNonBalloonContour must return null so ReaderScreen's oval fallback stays intact.
        val unborderedDarkOval = Bitmap.createBitmap(200, 140, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(32, 32, 36))
        }
        val fallbackContour = FallbackBubbleSupport.resolveNonBalloonContour(
            bitmap = unborderedDarkOval,
            textBounds = Rect(40, 35, 160, 105),
            bubbleBounds = Rect(20, 20, 180, 120),
            isDarkBubble = true,
            dialogueLabel = null
        )
        assertNull(fallbackContour)
        unborderedDarkOval.recycle()

        pageBitmap.recycle()
    }

    @Test
    fun `boundFallbackRefinedBox allows controlled 2_4x expansion inside uniform narrow dark pill`() {
        val bmp = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val canvas = Canvas(bmp)
        val darkPaint = Paint().apply { color = Color.rgb(20, 20, 24) }
        canvas.drawRect(70f, 30f, 118f, 230f, darkPaint) // 48 x 200 narrow dark pill
        val whiteTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f
        }
        canvas.drawText("HI", 84f, 130f, whiteTextPaint)

        val textRect = Rect(76, 85, 112, 165) // 36 x 80
        val pillRefined = Rect(72, 34, 116, 224) // 44 x 190 (1.22x wide, 2.375x tall <= 2.4x)
        val bounded = FallbackBubbleSupport.boundFallbackRefinedBox(
            bitmap = bmp,
            textRect = textRect,
            rawRefined = pillRefined,
            isDarkBubble = true
        )
        assertEquals(pillRefined, bounded)
        bmp.recycle()
    }

    @Test
    fun `sortCandidatesByPanelReadingOrder orders manga RTL panels and webtoon LTR panels`() {
        fun cand(label: String, rect: Rect) = BubbleCandidate(
            targetBox = rect,
            text = label,
            textRect = rect,
            isBubble = true,
            isDark = false,
            bubbleBounds = rect
        )

        // Tall right panel (P_R) + two stacked left panels (P_TL, P_BL)
        val pRight = Rect(520, 20, 960, 620)
        val pTopLeft = Rect(20, 20, 480, 300)
        val pBotLeft = Rect(20, 320, 480, 620)
        val panels = listOf(pTopLeft, pBotLeft, pRight)

        val cTopLeft = cand("TL", Rect(120, 80, 300, 180))
        val cBotLeft = cand("BL", Rect(120, 400, 300, 500))
        val cRightTop = cand("RT", Rect(740, 60, 900, 160))
        val cRightBot = cand("RB", Rect(560, 220, 720, 320))

        val mangaOrdered = FallbackBubbleSupport.sortCandidatesByPanelReadingOrder(
            candidates = listOf(cBotLeft, cRightBot, cTopLeft, cRightTop),
            pagePanels = panels,
            originLang = "Japonca"
        )
        assertEquals(listOf("RT", "RB", "TL", "BL"), mangaOrdered.map { it.text })

        // Webtoon / English LTR order: top-to-bottom, left-to-right
        val webtoonPanels = listOf(
            Rect(40, 500, 960, 900),
            Rect(40, 40, 480, 420),
            Rect(500, 40, 960, 420)
        )
        val w1 = cand("W1", Rect(100, 100, 300, 200))
        val w2 = cand("W2", Rect(580, 100, 780, 200))
        val w3 = cand("W3", Rect(200, 600, 420, 720))

        val webtoonOrdered = FallbackBubbleSupport.sortCandidatesByPanelReadingOrder(
            candidates = listOf(w3, w2, w1),
            pagePanels = webtoonPanels,
            originLang = "İngilizce"
        )
        assertEquals(listOf("W1", "W2", "W3"), webtoonOrdered.map { it.text })
    }
}
