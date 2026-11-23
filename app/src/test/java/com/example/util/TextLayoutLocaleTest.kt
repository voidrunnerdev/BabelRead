package com.example.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLayoutLocaleTest {

    @Test
    fun `target language overrides ui locale`() {
        val ui = Locale.forLanguageTag("tr")
        assertEquals(Locale.forLanguageTag("ja"), TextLayoutLocale.resolveLocale("ja", ui))
        assertEquals(Locale.forLanguageTag("ar"), TextLayoutLocale.resolveLocale("ar", ui))
    }

    @Test
    fun `unknown or automatic target falls back to ui locale`() {
        val ui = Locale.forLanguageTag("tr")
        assertEquals(ui, TextLayoutLocale.resolveLocale("zz", ui))
        assertEquals(ui, TextLayoutLocale.resolveLocale(null, ui))
        assertEquals(ui, TextLayoutLocale.resolveLocale("auto", ui))
        assertEquals(ui, TextLayoutLocale.resolveLocale("all", ui))
    }

    @Test
    fun `regional and legacy target tags resolve to a usable layout locale`() {
        val ui = Locale.forLanguageTag("tr")

        assertEquals(Locale.forLanguageTag("ar"), TextLayoutLocale.resolveLocale("ar-SA", ui))
        assertEquals(Locale.forLanguageTag("he"), TextLayoutLocale.resolveLocale("iw", ui))
        assertEquals(Locale.forLanguageTag("id"), TextLayoutLocale.resolveLocale("in-ID", ui))
        assertEquals(Locale.forLanguageTag("yi"), TextLayoutLocale.resolveLocale("ji", ui))
        assertEquals(Locale.forLanguageTag("ur"), TextLayoutLocale.resolveLocale("ur-PK", ui))
    }

    @Test
    fun `rtl targets use rtl direction only for rtl text`() {
        assertEquals(TextLayoutLocale.Direction.RTL, TextLayoutLocale.directionFor("ar", "مرحبا بالعالم"))
        assertEquals(TextLayoutLocale.Direction.RTL, TextLayoutLocale.directionFor("fa", "سلام دنیا"))
        assertEquals(TextLayoutLocale.Direction.RTL, TextLayoutLocale.directionFor("he", "שלום עולם"))
        assertEquals(TextLayoutLocale.Direction.RTL, TextLayoutLocale.directionFor("ur", "سلام دنیا"))
        assertEquals(TextLayoutLocale.Direction.RTL, TextLayoutLocale.directionFor("ar", "Hello مرحبا"))
        assertEquals(TextLayoutLocale.Direction.LTR, TextLayoutLocale.directionFor("ar", "Hello 123"))
        assertEquals(TextLayoutLocale.Direction.LTR, TextLayoutLocale.directionFor("tr", "مرحبا"))
    }

    @Test
    fun `rtl script detection keeps latin and cjk text unaffected`() {
        assertTrue(TextLayoutLocale.containsRtlCharacter("مرحبا"))
        assertFalse(TextLayoutLocale.containsRtlCharacter("Merhaba 123"))
        assertFalse(TextLayoutLocale.containsRtlCharacter("こんにちは世界"))
        assertFalse(TextLayoutLocale.isRtlLanguage("ja"))
        assertTrue(TextLayoutLocale.isRtlLanguage("ar-SA"))
        assertTrue(TextLayoutLocale.isRtlLanguage("iw"))
        assertTrue(TextLayoutLocale.isRtlLanguage("ur-PK"))
    }
}
