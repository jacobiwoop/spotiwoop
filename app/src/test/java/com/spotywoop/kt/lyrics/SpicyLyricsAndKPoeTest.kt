package com.spotywoop.kt.lyrics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitaires des analyseurs Spicy Lyrics et KPoe / LyricsPlus.
 */
class SpicyLyricsAndKPoeTest {

    @Test
    fun testParseSpicyLyricsSyllable() {
        val json = JSONObject("""
            {
              "Body": {
                "Type": "Syllable",
                "source": "apple_music",
                "SongWriters": ["Writer A", "Writer B"],
                "Content": [
                  {
                    "Type": "Vocal",
                    "OppositeAligned": false,
                    "Lead": {
                      "StartTime": 1.0,
                      "EndTime": 3.0,
                      "Syllables": [
                        { "Text": "Hel", "IsPartOfWord": true, "StartTime": 1.0, "EndTime": 1.5 },
                        { "Text": "lo", "IsPartOfWord": false, "StartTime": 1.5, "EndTime": 2.0 },
                        { "Text": "world", "IsPartOfWord": false, "StartTime": 2.1, "EndTime": 3.0 }
                      ]
                    },
                    "Background": [
                      {
                        "StartTime": 1.8,
                        "EndTime": 2.5,
                        "Syllables": [
                          { "Text": "echo", "IsPartOfWord": false, "StartTime": 1.8, "EndTime": 2.5 }
                        ]
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent())

        val result = LyricsParsers.parseSpicyLyrics(json)
        assertNotNull(result)
        assertEquals("Spicy Lyrics (Apple Music)", result!!.source)
        assertEquals("Writer A, Writer B", result.songwriters)

        val lines = result.lines
        assertEquals(1, lines.size)
        val l = lines[0]
        assertEquals(1000L, l.timestamp)
        assertEquals(3000L, l.endtime)
        assertTrue(l.isWordSynced == true)
        assertEquals("start", l.alignment)
        assertFalse(l.oppositeTurn)

        // Syllabes lead
        assertEquals(3, l.text.size)
        assertEquals("Hel", l.text[0].text)
        assertTrue(l.text[0].part)
        assertEquals(1000L, l.text[0].timestamp)
        assertEquals(1500L, l.text[0].endtime)

        assertEquals("lo ", l.text[1].text)
        assertFalse(l.text[1].part)

        assertEquals("world", l.text[2].text)
        assertFalse(l.text[2].part)

        // Chœur
        assertTrue(l.background)
        assertEquals(1, l.backgroundText.size)
        assertEquals("echo", l.backgroundText[0].text)
        assertEquals(1800L, l.backgroundText[0].timestamp)
        assertEquals(2500L, l.backgroundText[0].endtime)
    }

    @Test
    fun testConvertKPoeLyrics() {
        val json = JSONObject("""
            {
              "type": "Word",
              "metadata": {
                "source": "Apple Music",
                "agents": {
                  "v1": { "type": "person", "alias": "v1" },
                  "v2": { "type": "other", "alias": "v2" }
                }
              },
              "lyrics": [
                {
                  "time": 1000,
                  "duration": 2000,
                  "element": { "singer": "v1", "songPart": "Intro" },
                  "syllabus": [
                    { "text": "Hi ", "time": 1000, "duration": 500, "part": false },
                    { "text": "there", "time": 1500, "duration": 1500, "part": false }
                  ]
                },
                {
                  "time": 3500,
                  "duration": 2000,
                  "element": { "singer": "v2", "songPart": "Verse" },
                  "syllabus": [
                    { "text": "Hello", "time": 3500, "duration": 2000, "part": false }
                  ]
                }
              ]
            }
        """.trimIndent())

        val lines = LyricsParsers.convertKPoeLyrics(json)
        assertNotNull(lines)
        assertEquals(2, lines!!.size)

        val l1 = lines[0]
        assertEquals("Intro", l1.songPart)
        assertEquals(1000L, l1.timestamp)
        assertEquals(3000L, l1.endtime)
        assertEquals("start", l1.alignment)
        assertFalse(l1.oppositeTurn)
        assertEquals(2, l1.text.size)
        assertTrue(l1.isWordSynced == true)

        val l2 = lines[1]
        assertEquals("Verse", l2.songPart)
        assertEquals(3500L, l2.timestamp)
        assertEquals(5500L, l2.endtime)
        assertEquals("end", l2.alignment)
        assertTrue(l2.oppositeTurn)
    }
}
