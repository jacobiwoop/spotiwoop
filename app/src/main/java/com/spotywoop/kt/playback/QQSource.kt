package com.spotywoop.kt.playback

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * QQ Music depuis le téléphone, via tang.api.s01s.cn (tenue par un tiers, repérée dans MusicSquare).
 * Les titres payants n'ont aucun lien ; les autres sont en http.
 */
object QQSource {
    private const val BASE = "https://tang.api.s01s.cn/music_open_api.php"
    private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"
    private const val MAX_CANDIDATES = 3
    private const val EMPTY_RETRY_MS = 1200L

    data class Song(val mid: String, val title: String, val artist: String)

    private val http = OkHttpClient.Builder().addInterceptor(com.spotywoop.kt.data.AppLog.Http)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    fun resolve(artists: String?, title: String?, durationMs: Long?): ResolvedStream? {
        val wanted = SongMatch.seconds(durationMs)
        val term = SongMatch.searchTerm(artists, title)
        val candidates = search(term).filter { SongMatch.sameRecording(it.title, it.artist, artists, title) }
        for (song in candidates.take(MAX_CANDIDATES)) {
            val detail = JSONObject(get("msg" to term, "type" to "json", "mid" to song.mid))
            val url = bestLink(detail) ?: continue
            if (SongMatch.durationMatches(wanted, detail.optInt("song_play_time"))) {
                return ResolvedStream(url = url, source = "QQ (local)", quality = "M4A")
            }
        }
        return null
    }

    private fun search(term: String): List<Song> {
        val first = parseSearch(get("msg" to term, "type" to "json"))
        if (first.isNotEmpty()) return first
        Thread.sleep(EMPTY_RETRY_MS) // liste vide : l'API a pu limiter le débit
        return parseSearch(get("msg" to term, "type" to "json"))
    }

    private fun get(vararg params: Pair<String, String>): String {
        val url = BASE.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("QQ : HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    internal fun parseSearch(body: String): List<Song> {
        val items = JSONArray(body)
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            Song(o.optString("song_mid"), o.optString("song_title"), o.optString("singer_name"))
        }
    }

    internal fun bestLink(detail: JSONObject): String? =
        listOf("song_play_url_hq", "song_play_url_standard", "song_play_url")
            .map { detail.optString(it) }.firstOrNull { it.isNotEmpty() }
}
