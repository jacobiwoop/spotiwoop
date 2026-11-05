package com.spotywoop.kt.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsDiskFormatTest {

    private val line = LyricsLine(
        text = listOf(Syllable("Bon", part = true, timestamp = 1000, endtime = 1200), Syllable("jour", part = false, timestamp = 1200, endtime = 1500, lineSynced = true)),
        background = true,
        backgroundText = listOf(Syllable("oh", part = false, timestamp = 1500, endtime = 1600)),
        oppositeTurn = true,
        timestamp = 1000,
        endtime = 1600,
        isWordSynced = true,
        alignment = "end",
        songPart = "Refrain",
        translation = "Hello",
        agentId = "v1",
    )

    private val full = LyricsSourceResult(
        lines = listOf(line),
        source = "Spicy Lyrics",
        originalTTML = "<tt/>",
        songwriters = "A, B",
        byModel = true,
        isrc = "US123",
        metadata = SongMetadata("Titre", "Artiste", null, 180000, null),
        contributors = listOf(LyricsContributor("moi", roles = listOf("sync"))),
    )

    private val bare = LyricsSourceResult(lines = listOf(LyricsLine(text = emptyList(), timestamp = 0, endtime = 0)), source = "LRCLIB")

    @Test
    fun toutRevientALIdentique() {
        val state = LyricsState(
            lyrics = bare.lines,
            currentSource = bare,
            availableSources = listOf(full, bare),
            currentSourceIndex = 1,
            hasFetchedAllProviders = true,
            songwriters = "A, B",
        )
        assertEquals(state, LyricsDiskFormat.decode(LyricsDiskFormat.encode(state)))
    }

    @Test
    fun contenuInexploitableIgnore() {
        assertNull(LyricsDiskFormat.decode("pas du json"))
        assertNull(LyricsDiskFormat.decode(LyricsDiskFormat.encode(LyricsState())))
    }
}
