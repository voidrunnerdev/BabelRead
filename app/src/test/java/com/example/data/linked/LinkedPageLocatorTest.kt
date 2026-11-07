package com.example.data.linked

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedPageLocatorTest {
    @Test
    fun documentLocatorRoundTripsSpecialCharacters() {
        val uri = "content://com.example.provider/tree/primary%3AManga%20Klas%C3%B6r%C3%BC/1.jpg"
        val encoded = LinkedPageLocator.document(uri)

        assertTrue(LinkedPageLocator.isEncodedLocator(encoded))
        assertEquals(LinkedPageLocator.Document(uri), LinkedPageLocator.decode(encoded))
    }

    @Test
    fun archiveLocatorRoundTripsPipeAndUnicode() {
        val archiveUri = "content://com.example.provider/document/manga%3Fcbz"
        val entryName = "Bölüm 1|sayfa 01.png"
        val encoded = LinkedPageLocator.archiveEntry(archiveUri, entryName)

        assertTrue(LinkedPageLocator.isEncodedLocator(encoded))
        assertEquals(
            LinkedPageLocator.ArchiveEntry(archiveUri, entryName),
            LinkedPageLocator.decode(encoded)
        )
    }

    @Test
    fun listEncodingKeepsOrderAndSkipsNonLocators() {
        val first = LinkedPageLocator.document("content://one/1.jpg")
        val second = LinkedPageLocator.archiveEntry("content://two/2.cbz", "2.jpg")
        val list = linkedListOf(first, "", "https://example.com/page.jpg", second)

        val encoded = LinkedPageLocator.encodeList(list)
        assertEquals(listOf(first, second), LinkedPageLocator.decodeList(encoded))
        assertTrue(LinkedPageLocator.decodeList(null).isEmpty())
        assertTrue(LinkedPageLocator.decodeList("").isEmpty())
    }

    @Test
    fun malformedLocatorsReturnNull() {
        assertNull(LinkedPageLocator.decode("doc|"))
        assertNull(LinkedPageLocator.decode("arc|only-one-part"))
        assertNull(LinkedPageLocator.decode("unknown|value"))
        assertFalse(LinkedPageLocator.isEncodedLocator("file:///tmp/page.jpg"))
    }

    private fun linkedListOf(vararg values: String): List<String> = values.toList()
}
