package com.spotywoop.kt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Texte rendu par le service Genius : entités HTML et vérification du morceau. */
class GeniusTextTest {

    @Test
    fun decodesHtmlEntities() {
        assertEquals("Then I'll be right there", LyricsProviders.decodeHtmlEntities("Then I&#x27;ll be right there"))
        assertEquals("Rock & \"roll\" é", LyricsProviders.decodeHtmlEntities("Rock &amp; &quot;roll&quot; &#233;"))
        assertEquals("&unknown; reste", LyricsProviders.decodeHtmlEntities("&unknown; reste"))
    }

    @Test
    fun rejectsAnotherArtistAndKeepsTheRightSong() {
        assertFalse(LyricsProviders.sameSong("Pailita", "Bendito (Soundtrack Baby Bandito: La Serie)", "Vano Baby, Bobo Wê", "BANDIT"))
        assertTrue(LyricsProviders.sameSong("Burna Boy", "Last Last", "Burna Boy", "Last Last (Remix)"))
        assertTrue(LyricsProviders.sameSong("Dave & Burna Boy", "Location", "Dave", "Location"))
    }
}
