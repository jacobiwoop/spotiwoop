package com.spotywoop.kt.playback

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Kuwo depuis le téléphone, via oiapi.net (tenue par un tiers, repérée dans MusicSquare). Les liens sont en http. */
object KuwoSource {
    private const val BASE = "https://oiapi.net/api/Kuwo"
    private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"
    private const val MAX_CANDIDATES = 3
    private const val EMPTY_RETRY_MS = 1200L

    data class Quality(val bitrate: String, val format: String)
    data class Song(val id: String, val title: String, val artist: String, val durationSec: Int, val index: Int, val quality: String)

    private val http = OkHttpClient.Builder().addInterceptor(com.spotywoop.kt.data.AppLog.Http)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    fun resolve(artists: String?, title: String?, durationMs: Long?): ResolvedStream? {
        val wanted = SongMatch.seconds(durationMs)
        val term = SongMatch.searchTerm(artists, title)
        val candidates = search(term)
            .filter { SongMatch.sameRecording(it.title, it.artist, artists, title) }
            .filter { SongMatch.durationMatches(wanted, it.durationSec) }
        for (song in candidates.take(MAX_CANDIDATES)) {
            val data = get("msg" to term, "n" to song.index.toString(), "br" to song.quality).optJSONObject("data") ?: continue
            // Le lien se demande par position : si l'ordre a changé depuis la recherche, ce n'est plus le même titre.
            val url = data.optString("url")
            if (url.isEmpty() || data.optString("rid") != song.id) continue
            if (!SongMatch.durationMatches(wanted, data.optInt("time"))) continue
            val label = data.optString("format").uppercase() + data.optString("bitrate").takeIf { it.isNotEmpty() }?.let { " $it kbit/s" }.orEmpty()
            return ResolvedStream(url = url, source = "Kuwo (local)", quality = label)
        }
        return null
    }

    private fun search(term: String): List<Song> {
        val first = parseSearch(get("msg" to term, "page" to "1", "limit" to "10"))
        if (first.isNotEmpty()) return first
        Thread.sleep(EMPTY_RETRY_MS) // liste vide : l'API a pu limiter le débit
        return parseSearch(get("msg" to term, "page" to "1", "limit" to "10"))
    }

    private fun get(vararg params: Pair<String, String>): JSONObject {
        val url = BASE.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Kuwo : HTTP ${resp.code}")
            JSONObject(resp.body?.string().orEmpty())
        }
    }

    /** « data » n'est une liste que s'il y a des résultats. */
    internal fun parseSearch(res: JSONObject): List<Song> {
        val items: JSONArray = res.optJSONArray("data") ?: return emptyList()
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            val types = o.optJSONArray("types")
            val qualities = (0 until (types?.length() ?: 0)).map { t ->
                types!!.getJSONObject(t).let { Quality(it.optString("bitrate"), it.optString("format")) }
            }
            Song(o.optString("rid"), o.optString("song"), o.optString("singer"), o.optInt("time"), i + 1, bestQuality(qualities))
        }
    }

    /**
     * Valeur de br : la position, à partir de 1, du MP3 au meilleur débit dans la liste du titre, sinon de la première
     * qualité qui n'est pas du FLAC (40 Mo par titre). Elle change d'un titre à l'autre.
     */
    internal fun bestQuality(types: List<Quality>): String {
        val mp3 = types.withIndex().filter { it.value.format == "mp3" }.maxByOrNull { it.value.bitrate.toIntOrNull() ?: 0 }
        val index = mp3?.index ?: types.indexOfFirst { it.format != "flac" }
        return (index + 1).toString()
    }
}
