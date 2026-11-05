package com.spotywoop.kt.playback

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSourcesTest {
    @Test
    fun sameRecordingKeepsTheSongAndRejectsNeighbours() {
        for ((t, a) in listOf("Last Last" to "Burna Boy", "Last Last (Explicit)" to "Burna Boy")) {
            assertTrue("$t refusé", SongMatch.sameRecording(t, a, "Burna Boy", "Last Last"))
        }
        for ((t, a) in listOf("Last Last Mashup" to "Burna Boy", "Last Last (Remix Zouk)" to "Burna Boy", "Last Last" to "Raybekah", "Last Christmas" to "Burna Boy", "Last Last" to "")) {
            assertFalse("$t / $a accepté", SongMatch.sameRecording(t, a, "Burna Boy", "Last Last"))
        }
    }

    @Test
    fun searchTermDropsGuestsAndBrackets() {
        assertEquals("Rema Calm Down", SongMatch.searchTerm("Rema, Selena Gomez", "Calm Down (with Selena Gomez)"))
    }

    @Test
    fun kuwoQualityFollowsEachSongsList() {
        fun q(vararg t: Pair<String, String>) = t.map { KuwoSource.Quality(it.first, it.second) }
        assertEquals("4", KuwoSource.bestQuality(q("2000" to "flac", "192" to "ogg", "100" to "ogg", "320" to "mp3", "128" to "mp3")))
        assertEquals("5", KuwoSource.bestQuality(q("2000" to "flac", "300" to "ogg", "192" to "ogg", "100" to "ogg", "320" to "mp3")))
        assertEquals("2", KuwoSource.bestQuality(q("2000" to "flac", "192" to "ogg")))
    }

    @Test
    fun kuwoSearchWithoutListIsEmpty() {
        assertTrue(KuwoSource.parseSearch(JSONObject("""{"code":0,"data":"rien"}""")).isEmpty())
    }

    @Test
    fun qqPaidSongHasNoLink() {
        assertNull(QQSource.bestLink(JSONObject("""{"song_play_time":195,"song_play_url":"","song_play_url_hq":""}""")))
        assertEquals("http://x/hq.m4a", QQSource.bestLink(JSONObject("""{"song_play_url_hq":"http://x/hq.m4a","song_play_url":"http://x/a.m4a"}""")))
    }

    @Test
    fun audiomackSkipsSongsAnnouncedAsRefused() {
        val body = """{"results":[{"type":"song","id":1,"title":"A","artist":"B","duration":"158"},
            {"type":"song","id":2,"title":"A","artist":"B","geo_restricted":true},
            {"type":"song","id":3,"title":"A","artist":"B","premium_user_only":"yes"},{"type":"album","id":4}]}"""
        assertEquals(listOf(AudiomackSource.Song(1, "A", "B", 158)), AudiomackSource.parseSearch(body))
    }

    @Test
    fun waptrickParsesSearchAndBestFile() {
        val body = """<li class="new_list"><a onclick="window.open('/fr/download/a?type=FT&amp;id=7')"><span class="uptext underline"> Caf&#233; &amp; Co </span></a><a class="more underline">Raybekah</a><a class="more underline">Kholi</a></li>"""
        assertEquals(listOf(WaptrickSource.Song("Café & Co", "Raybekah, Kholi", "https://waptrick.com/fr/download/a?type=FT&id=7")), WaptrickSource.parseSearch(body))
        val page = """x floc=%2Fload-file%2FFT%2F1%2Fbest%2FA.mp3' y floc=%2Fload-file%2FFT%2F1%2Flow%2FA.mp3'"""
        assertEquals("/load-file/FT/1/best/A.mp3", WaptrickSource.bestFile(page))
    }

    @Test
    fun mp3DurationFromBitrateAndFromXing() {
        val frame128 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0)
        for (read in listOf(NetEaseSource::mp3Duration, WaptrickSource::mp3Duration)) {
            assertEquals(171, read(frame128, 2738512))
            val xing = frame128 + ByteArray(32) + "Xing".toByteArray() + byteArrayOf(0, 0, 0, 1, 0, 0, 0x1C, 0x20)
            assertEquals(188, read(xing, 999))
            assertEquals(0, read("pas un mp3".toByteArray(), 999))
        }
    }
}
