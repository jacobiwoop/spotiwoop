package com.spotywoop.kt.playback

import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Audiomack depuis le téléphone, avec les appels signés du lecteur web (audiomack/client.go du serveur).
 * Les droits dépendent du pays : un titre refusé au serveur peut passer ici, et inversement.
 */
object AudiomackSource {
    private const val BASE = "https://api.audiomack.com/v1"
    private const val CONSUMER_KEY = "audiomack-web"
    private const val CONSUMER_SECRET = "bd8a07e9f23fbe9d808646b730f89b8e"
    private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"
    private const val MAX_CANDIDATES = 3

    data class Song(val id: Long, val title: String, val artist: String, val durationSec: Int)

    private val http = OkHttpClient.Builder().addInterceptor(com.spotywoop.kt.data.AppLog.Http)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    /** @param trackId « am:<id> » pour un titre venu d'Audiomack : lu directement, sans recherche. */
    fun resolve(artists: String?, title: String?, durationMs: Long?, trackId: String? = null): ResolvedStream? {
        trackId?.takeIf { it.startsWith("am:") }?.removePrefix("am:")?.toLongOrNull()?.let { id ->
            runCatching { streamUrl(id) }.getOrNull()?.let { return stream(it) }
        }
        val wanted = SongMatch.seconds(durationMs)
        val query = listOfNotNull(artists, title).joinToString(" ").trim()
        if (query.isEmpty()) return null
        val body = get("/livesearch", mapOf("q" to query, "show" to "music", "sort" to "popular", "limit" to "10", "page" to "1"))
        val candidates = parseSearch(body)
            .filter { SongMatch.sameRecording(it.title, it.artist, artists, title) }
            .filter { SongMatch.durationMatches(wanted, it.durationSec) }
        for (song in candidates.take(MAX_CANDIDATES)) {
            // Refus du pays ou abonnement : on passe au suivant.
            val url = runCatching { streamUrl(song.id) }.getOrNull() ?: continue
            return stream(url)
        }
        return null
    }

    private fun stream(url: String) = ResolvedStream(url = url, source = "Audiomack (local)", quality = "M4A jusqu'à 256 kbit/s")

    private fun streamUrl(id: Long): String? {
        val body = get("/music/play/$id", mapOf("environment" to "desktop-web", "hq" to "true", "section" to "Search"))
        return JSONObject(body).optString("signedUrl").takeIf { it.isNotEmpty() }
    }

    /** Les titres que la recherche annonce déjà refusés (pays, abonnement) sont retirés. */
    internal fun parseSearch(body: String): List<Song> {
        val items = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optString("type") == "song" && !it.optBoolean("geo_restricted") && it.optString("premium_user_only") != "yes" }
            .map { Song(it.optLong("id"), it.optString("title"), it.optString("artist"), it.optInt("duration")) }
    }

    private fun get(path: String, params: Map<String, String>): String {
        val endpoint = BASE + path
        val signed = params + mapOf(
            "oauth_consumer_key" to CONSUMER_KEY,
            "oauth_nonce" to UUID.randomUUID().toString().replace("-", ""),
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "oauth_version" to "1.0",
        )
        val query = (signed + ("oauth_signature" to signature(endpoint, signed))).entries
            .joinToString("&") { "${percentEncode(it.key)}=${percentEncode(it.value)}" }
        val request = Request.Builder().url("$endpoint?$query").header("User-Agent", USER_AGENT)
            .header("Referer", "https://audiomack.com/").header("Accept", "application/json").build()
        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Audiomack : HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    internal fun signature(endpoint: String, params: Map<String, String>): String {
        val pairs = params.map { "${percentEncode(it.key)}=${percentEncode(it.value)}" }.sorted().joinToString("&")
        val base = "GET&${percentEncode(endpoint)}&${percentEncode(pairs)}"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec("${percentEncode(CONSUMER_SECRET)}&".toByteArray(), "HmacSHA1"))
        return mac.doFinal(base.toByteArray()).toByteString().base64()
    }

    internal fun percentEncode(s: String): String = buildString {
        for (byte in s.toByteArray()) {
            val c = byte.toInt() and 0xff
            if (c.toChar().isLetterOrDigit() && c < 128 || c.toChar() in "-._~") append(c.toChar()) else append("%%%02X".format(c))
        }
    }
}
