package com.spotywoop.kt.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun formatDisplayRespectsAllModes() {
        val original = "ブルーバード"

        // Option B (Défaut) : Romaji (Original)
        assertEquals("Burubado (ブルーバード)", AppSettings.formatDisplay(original, TransliterationMode.ROMAJI_FIRST))

        // Option A : Original (Romaji)
        assertEquals("ブルーバード (Burubado)", AppSettings.formatDisplay(original, TransliterationMode.ORIGINAL_FIRST))

        // Option C : Romaji seul
        assertEquals("Burubado", AppSettings.formatDisplay(original, TransliterationMode.ROMAJI_ONLY))

        // OFF : Original seul
        assertEquals("ブルーバード", AppSettings.formatDisplay(original, TransliterationMode.OFF))
    }

    @Test
    fun formatDisplayLeavesLatinTextUnchanged() {
        val english = "Blinding Lights"
        assertEquals(english, AppSettings.formatDisplay(english, TransliterationMode.ROMAJI_FIRST))
        assertEquals(english, AppSettings.formatDisplay(english, TransliterationMode.ORIGINAL_FIRST))
        assertEquals(english, AppSettings.formatDisplay(english, TransliterationMode.ROMAJI_ONLY))

        val french = "Alors On Danse"
        assertEquals(french, AppSettings.formatDisplay(french, TransliterationMode.ROMAJI_FIRST))
    }

    @Test
    fun formatDisplayHandlesJapaneseArtists() {
        val artist = "いきものがかり"
        assertEquals("Ikimonogakari (いきものがかり)", AppSettings.formatDisplay(artist, TransliterationMode.ROMAJI_FIRST))
        assertEquals("Ikimonogakari", AppSettings.formatDisplay(artist, TransliterationMode.ROMAJI_ONLY))
    }
}
