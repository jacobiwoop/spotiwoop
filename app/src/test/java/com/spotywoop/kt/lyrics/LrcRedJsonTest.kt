package com.spotywoop.kt.lyrics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader

/**
 * Tests unitaires de l'analyse de lrcred-lastlast.song.json (Last Last, Burna Boy).
 * Sans aucun appel réseau.
 */
class LrcRedJsonTest {

    private fun loadResourceJson(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("lrcred-lastlast.song.json")
            ?: error("Ressource 'lrcred-lastlast.song.json' introuvable dans le classpath de test")
        val content = InputStreamReader(stream, Charsets.UTF_8).use { it.readText() }
        return JSONObject(content)
    }

    @Test
    fun testParseLrcRedJsonFromSongFile() {
        val songJson = loadResourceJson()

        val lines = LyricsParsers.parseLrcRedJson(songJson)

        // 1. Nombre de lignes : 61 lignes
        assertEquals(61, lines.size)

        // 2. Détection mot par mot
        assertTrue(LyricsProviders.hasWordSyncedLyrics(lines))
        assertEquals(true, lines[0].isWordSynced)

        // 3. Première ligne et premier mot « E » de 0 à 220 ms
        val firstLine = lines[0]
        assertEquals("Intro", firstLine.songPart)
        assertTrue(firstLine.text.isNotEmpty())

        val firstWord = firstLine.text[0]
        assertEquals("E ", firstWord.text)
        assertEquals(0L, firstWord.timestamp)
        assertEquals(220L, firstWord.endtime)
        assertFalse(firstWord.part) // E est suivi d'une espace donc part == false

        // 4. Auteurs (songwriters) dans le JSON
        val swArray = songJson.optJSONArray("songwriters")
        assertNotNull(swArray)
        val songwriters = (0 until swArray!!.length()).map { swArray.getString(it) }.joinToString(", ")
        assertTrue(songwriters.contains("Damini Ebunoluwa Ogulu"))
        assertTrue(songwriters.contains("Fred Jerkins"))
    }

    @Test
    fun testParseTTMLFromLrcRedSongSource() {
        val songJson = loadResourceJson()
        val ttmlSource = songJson.optString("source")
        assertTrue(ttmlSource.isNotBlank())

        val parseResult = LyricsParsers.parseTTML(ttmlSource)
        assertNotNull(parseResult)

        val lines = parseResult!!.lines
        // 1. Nombre de lignes : 61 lignes
        assertEquals(61, lines.size)

        // 2. Détection mot par mot
        assertTrue(LyricsProviders.hasWordSyncedLyrics(lines))
        assertEquals(true, lines[0].isWordSynced)

        // 3. Première ligne
        val firstLine = lines[0]
        assertEquals("Intro", firstLine.songPart)
        assertTrue(firstLine.text.isNotEmpty())

        val firstWord = firstLine.text[0]
        assertEquals("E ", firstWord.text)
        assertEquals(0L, firstWord.timestamp)
        assertEquals(220L, firstWord.endtime)

        // 4. Auteurs extraits du TTML
        val songwriters = parseResult.songwriters
        assertNotNull(songwriters)
        assertTrue(songwriters!!.contains("Damini Ebunoluwa Ogulu"))
        assertTrue(songwriters.contains("Fred Jerkins III"))
    }
}
