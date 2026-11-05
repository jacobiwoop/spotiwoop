package com.spotywoop.kt.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitaires des utilitaires de GoogleLyricsService (sans appel réseau).
 */
class GoogleLyricsServiceTest {

    @Test
    fun testIsPurelyLatinScript() {
        assertTrue(GoogleLyricsService.isPurelyLatinScript("Hello world!"))
        assertTrue(GoogleLyricsService.isPurelyLatinScript("Café, crème brûlée & naïve 123?"))
        assertTrue(GoogleLyricsService.isPurelyLatinScript("Curaçao, Ångström, español"))

        // Caractères cyrilliques, chinois, japonais, coréens, arabes
        assertFalse(GoogleLyricsService.isPurelyLatinScript("Привет мир"))
        assertFalse(GoogleLyricsService.isPurelyLatinScript("你好世界"))
        assertFalse(GoogleLyricsService.isPurelyLatinScript("こんにちは"))
        assertFalse(GoogleLyricsService.isPurelyLatinScript("안녕하세요"))
        assertFalse(GoogleLyricsService.isPurelyLatinScript("مرحبا بالعالم"))
    }
}
