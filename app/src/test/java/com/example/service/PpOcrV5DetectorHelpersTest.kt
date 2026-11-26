package com.example.service

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PpOcrV5DetectorHelpersTest {

    @Test
    fun `scaled size keeps stride and avoids upscaling`() {
        val medium = PpOcrV5DetPreprocess.scaledSize(800, 600, 960)
        assertEquals(800, medium[0])
        assertEquals(608, medium[1])

        val small = PpOcrV5DetPreprocess.scaledSize(160, 80, 960)
        assertEquals(160, small[0])
        assertEquals(96, small[1])

        val large = PpOcrV5DetPreprocess.scaledSize(1920, 1080, 960)
        assertEquals(960, large[0])
        assertEquals(544, large[1])
    }

    @Test
    fun `tensor uses bgr mean and std normalization`() {
        val pixels = intArrayOf(
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0xFFFFFFFF.toInt()
        )
        val tensor = PpOcrV5DetPreprocess.toTensor(pixels, 2, 2, 2, 2)
        assertEquals(12, tensor.size)
        val plane = 4

        assertClose((0f - 0.485f) / 0.229f, tensor[0])
        assertClose((0f - 0.456f) / 0.224f, tensor[plane])
        assertClose((1f - 0.406f) / 0.225f, tensor[plane * 2])
        assertClose((1f - 0.485f) / 0.229f, tensor[2])
        assertClose((0f - 0.406f) / 0.225f, tensor[plane * 2 + 2])
    }

    @Test
    fun `detect boxes filters confidence and unclips`() {
        val width = 100
        val height = 60
        val values = FloatArray(width * height)
        for (y in 10 until 20) {
            for (x in 10 until 40) values[y * width + x] = 0.9f
        }
        for (y in 35 until 50) {
            for (x in 55 until 95) values[y * width + x] = 0.95f
        }

        val boxes = PpOcrV5DetPostProcess.detectBoxes(values, width, height)
        assertEquals(2, boxes.size)
        val first = boxes.first { it.top < 15f }
        val second = boxes.first { it.top > 15f }
        assertTrue(first.left <= 10f && first.right >= 39f)
        assertTrue(second.left <= 55f && second.right >= 94f)
        assertEquals(0.9f, first.score, 0.001f)
        assertEquals(0.95f, second.score, 0.001f)
    }

    @Test
    fun `detect boxes converts logits to probabilities`() {
        val width = 40
        val height = 40
        val values = FloatArray(width * height) { -12f }
        for (y in 8 until 20) {
            for (x in 8 until 32) values[y * width + x] = 8f
        }

        val boxes = PpOcrV5DetPostProcess.detectBoxes(values, width, height)
        assertEquals(1, boxes.size)
        assertTrue(boxes[0].score > 0.99f)
        assertTrue(boxes[0].left < 8f && boxes[0].right > 31f)
    }

    @Test
    fun `scaledSize preserves horizontal aspect on wide small-font line crops`() {
        val wideLine = PpOcrV5DetPreprocess.scaledSize(140, 20, limitSide = 960, minSide = 480)
        assertTrue("Width should be >= 480 for small line crop", wideLine[0] >= 480)
        assertTrue("Width must remain multiple of 32", wideLine[0] % 32 == 0)
        assertTrue("Height must remain multiple of 32", wideLine[1] % 32 == 0)
        assertTrue("Horizontal aspect must be preserved", wideLine[0] > wideLine[1] * 4)
    }

    @Test
    fun `scaledSize and detectBoxes preserve stacked lines in narrow vertical strip`() {
        val stripSize = PpOcrV5DetPreprocess.scaledSize(48, 180, limitSide = 960, minSide = 480)
        assertTrue("Narrow strip should upscale cleanly", stripSize[1] >= 480)
        assertEquals(0, stripSize[0] % 32)
        assertEquals(0, stripSize[1] % 32)

        val w = 32
        val h = 120
        val prob = FloatArray(w * h)
        for (y in 12 until 24) for (x in 6 until 26) prob[y * w + x] = 0.92f
        for (y in 70 until 82) for (x in 6 until 26) prob[y * w + x] = 0.90f
        val boxes = PpOcrV5DetPostProcess.detectBoxes(prob, w, h)
        assertEquals(2, boxes.size)
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue("expected=$expected actual=$actual", abs(expected - actual) < 1e-5f)
    }
}
