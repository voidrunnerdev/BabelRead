package com.example.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class ArchiveSafetyLimitsTest {

    @Test
    fun copyBounded_allowsPayloadWithinLimit() {
        val payload = ByteArray(1024) { (it % 127).toByte() }
        val output = ByteArrayOutputStream()
        val copied = LocalMangaImporter.copyBounded(
            input = ByteArrayInputStream(payload),
            output = output,
            maxBytes = 1024L
        )
        assertEquals(1024L, copied)
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun copyBounded_throwsWhenPayloadExceedsLimit() {
        val payload = ByteArray(2048) { 1 }
        val output = ByteArrayOutputStream()
        try {
            LocalMangaImporter.copyBounded(
                input = ByteArrayInputStream(payload),
                output = output,
                maxBytes = 1024L
            )
            fail("Expected IOException when exceeding maxBytes")
        } catch (_: IOException) {
            // Expected
        }
    }

    @Test
    fun boundedOutputStream_enforcesMaxBytes() {
        val delegate = ByteArrayOutputStream()
        val bounded = LocalMangaImporter.BoundedOutputStream(delegate, maxBytes = 10L)
        bounded.write(ByteArray(6) { 2 })
        bounded.write(ByteArray(4) { 3 })
        assertEquals(10L, bounded.bytesWritten)
        try {
            bounded.write(1)
            fail("Expected IOException on byte 11")
        } catch (_: IOException) {
            // Expected
        }
    }
}
