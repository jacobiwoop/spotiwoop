package com.spotywoop.kt.playback

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Waptrick depuis le téléphone : recherche, page du titre, puis /load-file/… qui redirige vers le fichier (http, adresse IP).
 * La recherche ne donne pas la durée : on la lit dans le fichier. Il faut un User-Agent de navigateur complet.
 */
object WaptrickSource {
    private const val BASE = "https://waptrick.com"
    private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"
    private const val MAX_CANDIDATES = 3

    private val NAME_RE = Regex("""class="uptext underline">\s*([^<]*?)\s*</span>""")
    private val ARTIST_RE = Regex("""class="more underline">([^<]*)</a>""")
    private val PAGE_RE = Regex("""window\.open\('([^']*/download/[^']*\?type=FT&amp;id=\d+)'\)""")
    private val FLOC_RE = Regex("""floc=([^'&"]+)""")
    private val ENTITY_RE = Regex("""&#(x?)([0-9a-fA-F]+);""")

    data class Song(val title: String, val artist: String, val page: String)

    private val http = OkHttpClient.Builder().addInterceptor(com.spotywoop.kt.data.AppLog.Http)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun resolve(artists: String?, title: String?, durationMs: Long?): ResolvedStream? {
        val wanted = SongMatch.seconds(durationMs)
        val url = "$BASE/fr/_common/search/search.jsp".toHttpUrl().newBuilder()
            .addQueryParameter("c", SongMatch.searchTerm(artists, title)).addQueryParameter("type", "full_mp3").build().toString()
        val candidates = parseSearch(page(url)).filter { SongMatch.sameRecording(it.title, it.artist, artists, title) }
        for (song in candidates.take(MAX_CANDIDATES)) {
            val path = bestFile(page(song.page)) ?: continue
            val file = redirect(BASE + path) ?: continue
            if (SongMatch.durationMatches(wanted, duration(file))) {
                return ResolvedStream(url = file, source = "Waptrick (local)", quality = "MP3")
            }
        }
        return null
    }

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", USER_AGENT)

    private fun page(url: String): String = http.newCall(request(url).build()).execute().use { resp ->
        if (resp.code != 200) throw IOException("Waptrick : HTTP ${resp.code}")
        resp.body?.string().orEmpty()
    }

    private fun redirect(url: String): String? = http.newCall(request(url).build()).execute().use { resp ->
        resp.header("Location")?.takeIf { resp.code / 100 == 3 }
    }

    internal fun parseSearch(body: String): List<Song> =
        body.split("""<li class="new_list">""").drop(1).mapNotNull { item ->
            val name = NAME_RE.find(item)?.groupValues?.get(1) ?: return@mapNotNull null
            val page = PAGE_RE.find(item)?.groupValues?.get(1)?.let(::unescape) ?: return@mapNotNull null
            val artists = ARTIST_RE.findAll(item).map { unescape(it.groupValues[1].trim()) }.joinToString(", ")
            Song(unescape(name), artists, if (page.startsWith("http")) page else BASE + page)
        }

    /** Les liens de la page vont de la meilleure qualité à la plus basse. */
    internal fun bestFile(body: String): String? =
        FLOC_RE.findAll(body).map { URLDecoder.decode(it.groupValues[1], "UTF-8") }.firstOrNull { it.startsWith("/load-file/") }

    internal fun unescape(s: String): String =
        ENTITY_RE.replace(s) { m -> String(Character.toChars(m.groupValues[2].toInt(if (m.groupValues[1].isEmpty()) 10 else 16))) }
            .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun duration(url: String): Int = runCatching {
        http.newCall(request(url).header("Range", "bytes=0-8191").build()).execute().use { resp ->
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
