package com.example.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleTextScriptPolicyTest {

    @Test
    fun `rtl and mixed rtl text stay on the static layout path`() {
        assertFalse(BubbleTextScriptPolicy.isSafe("ar", "مرحبا بالعالم"))
        assertFalse(BubbleTextScriptPolicy.isSafe("fa", "سلام دنیا"))
        assertFalse(BubbleTextScriptPolicy.isSafe("tr", "Hello مرحبا"))
        assertFalse(BubbleTextScriptPolicy.isSafe("he", "שלום"))
    }

    @Test
    fun `complex scripts stay on the static layout path`() {
        assertFalse(BubbleTextScriptPolicy.isSafe("hi", "नमस्ते दुनिया"))
        assertFalse(BubbleTextScriptPolicy.isSafe("th", "สวัสดีชาวโลก"))
        assertFalse(BubbleTextScriptPolicy.isSafe("en", "नमस्ते"))
    }

    @Test
    fun `latin cyrillic greek and cjk text are safe for line drawing`() {
        assertTrue(BubbleTextScriptPolicy.isSafe("tr", "Merhaba dünya"))
        assertTrue(BubbleTextScriptPolicy.isSafe("vi", "Tiếng Việt"))
        assertTrue(BubbleTextScriptPolicy.isSafe("ru", "Привет мир"))
        assertTrue(BubbleTextScriptPolicy.isSafe("el", "Γειά σου κόσμε"))
        assertTrue(BubbleTextScriptPolicy.isSafe("ja", "こんにちは世界"))
        assertTrue(BubbleTextScriptPolicy.isSafe("zh", "你好世界"))
        assertTrue(BubbleTextScriptPolicy.isSafe("ko", "안녕하세요"))
    }

    @Test
    fun `combining marks and supplementary glyphs stay on the static layout path`() {
        assertFalse(BubbleTextScriptPolicy.isSafe("en", "e\u0301"))
        assertFalse(BubbleTextScriptPolicy.isSafe("en", "hello 😀"))
    }
}
