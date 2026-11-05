package com.spotywoop.kt.playback

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * NetEase depuis le téléphone, via l'API Meting de api.qijieya.cn (tenue par un tiers, repérée dans MusicSquare).
 * Le lien Meting redirige vers le MP3 sur music.126.net ; la recherche ne donne pas la durée, on la lit dans le fichier.
 */
object NetEaseSource {
    private const val BASE = "https://api.qijieya.cn/meting/"
    private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"
    private const val MAX_CANDIDATES = 3
    private const val EMPTY_RETRY_MS = 1200L

    data class Song(val title: String, val artist: String, val link: String)

    private val http = OkHttpClient.Builder().addInterceptor(com.spotywoop.kt.data.AppLog.Http)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val noFollow = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun resolve(artists: String?, title: String?, durationMs: Long?): ResolvedStream? {
        val wanted = SongMatch.seconds(durationMs)
        val candidates = search(SongMatch.searchTerm(artists, title))
            .filter { SongMatch.sameRecording(it.title, it.artist, artists, title) }
        for (song in candidates.take(MAX_CANDIDATES)) {
            val file = fileUrl(song.link) ?: continue
            if (SongMatch.durationMatches(wanted, duration(file))) {
                return ResolvedStream(url = file, source = "NetEase (local)", quality = "MP3")
            }
        }
        return null
    }

    /** L'API rend une liste vide quand on l'appelle trop vite : ça ne prouve pas que le titre manque. */
    private fun search(term: String): List<Song> {
        val first = searchOnce(term)
        if (first.isNotEmpty()) return first
        Thread.sleep(EMPTY_RETRY_MS)
        return searchOnce(term)
    }

    private fun searchOnce(term: String): List<Song> {
        val url = BASE.toHttpUrl().newBuilder().addQueryParameter("type", "search").addQueryParameter("limit", "10")
            .addQueryParameter("server", "netease").addQueryParameter("id", term).build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("NetEase : HTTP ${resp.code}")
            parseSearch(resp.body?.string().orEmpty())
        }
    }

    internal fun parseSearch(body: String): List<Song> {
        val items = JSONArray(body)
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            Song(o.optString("name"), o.optString("artist"), o.optString("url"))
        }
    }

    private fun fileUrl(link: String): String? {
        val request = Request.Builder().url(link).header("User-Agent", USER_AGENT).build()
        return noFollow.newCall(request).execute().use { resp ->
            resp.header("Location")?.takeIf { resp.code / 100 == 3 }?.replaceFirst("http://", "https://")
        }
    }

    private fun duration(url: String): Int = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Range", "bytes=0-8191").build()
        http.newCall(request).execute().use { resp ->
            val size = resp.header("Content-Range")?.substringAfterLast("/")?.toLongOrNull() ?: return 0
            mp3Duration(resp.body?.bytes() ?: return 0, size)
        }
    }.getOrDefault(0)

    /** Durée par l'en-tête Xing/Info, sinon par le débit de la première trame et la taille du fichier ; 0 si inconnue. */
    internal fun mp3Duration(b: ByteArray, size: Long): Int {
        fun u(i: Int) = b[i].toInt() and 0xff
        var off = 0
        if (b.size >= 10 && String(b, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            off = 10 + ((u(6) and 0x7f) shl 21 or ((u(7) and 0x7f) shl 14) or ((u(8) and 0x7f) shl 7) or (u(9) and 0x7f))
        }
        val audio = (size - off).coerceAtLeast(0)
        while (off + 4 <= b.size && !(u(off) == 0xff && (u(off + 1) and 0xe0) == 0xe0)) off++
        if (off + 4 > b.size) return 0
        if ((u(off + 1) shr 3) and 0x03 != 3 || (u(off + 1) shr 1) and 0x03 != 1) return 0 // MPEG-1 Layer III seulement
        val rate = intArrayOf(44100, 48000, 32000).getOrNull((u(off + 2) shr 2) and 0x03) ?: return 0
        val frame = b.copyOfRange(off, minOf(off + 200, b.size))
        val text = String(frame, Charsets.ISO_8859_1)
        for (tag in listOf("Xing", "Info")) {
            val i = text.indexOf(tag)
            if (i < 0 || i + 12 > frame.size) continue
            fun f(k: Int) = frame[i + k].toLong() and 0xff
            if ((f(7) and 1L) == 0L) break
            return (((f(8) shl 24) or (f(9) shl 16) or (f(10) shl 8) or f(11)) * 1152 / rate).toInt()
        }
        val kbps = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)[u(off + 2) shr 4]
        return if (kbps == 0) 0 else (audio * 8 / (kbps * 1000L)).toInt()
    }
}
