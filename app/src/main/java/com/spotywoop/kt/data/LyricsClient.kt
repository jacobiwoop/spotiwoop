package com.spotywoop.kt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class LyricLine(
    val timeMs: Long,
    val text: String,
)

data class LyricsResult(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
)

/**
 * Client pour récupérer et parser les paroles via LRCLIB (directement sur le téléphone).
 */
object LyricsClient {

    private val http = OkHttpClient.Builder().addInterceptor(com.spotywoop.kt.data.AppLog.Http).callTimeout(10, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, LyricsResult?>()

    private val LRC_REGEX = Regex("""\[(\d+):(\d+(?:\.\d+)?)\](.*)""")

    suspend fun fetchLyrics(artist: String, title: String, album: String? = null, durationSec: Long = 0): LyricsResult? =
        withContext(Dispatchers.IO) {
            val cacheKey = "$artist - $title"
            if (cache.containsKey(cacheKey)) return@withContext cache[cacheKey]
            val started = System.currentTimeMillis()

            // 0. API du site : une requête, réponse souvent déjà en cache. Si elle ne répond pas, on
            // retombe sur LRCLIB en direct (jusqu'à 4 appels, longs quand LRCLIB rame).
            val viaApi = runCatching {
                kotlinx.coroutines.withTimeout(8_000) { SpotiwoopApi.lyrics(artist, title, album, durationSec) }
            }
            if (viaApi.isSuccess) {
                val r = viaApi.getOrNull()
                if (r != null) cache[cacheKey] = r
                android.util.Log.i("Lyrics", "API '$artist - $title' (${durationSec}s) : ${r?.lines?.size ?: "aucune"} en ${System.currentTimeMillis() - started} ms")
                return@withContext r
            }
            android.util.Log.w("Lyrics", "API indisponible (${viaApi.exceptionOrNull()?.message}), LRCLIB direct")

            // Nettoyage intelligent du titre
            // « Titre - Artiste » (Audiomack) comme « Artiste - Titre » (YouTube) : on écarte la partie
            // qui nomme un artiste, puis on garde la première restante (« - Radio Edit » tombe aussi).
            val bare = title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()
            val artistNames = artist.split(Regex(""",|&| x | feat\.? """, RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
            val parts = bare.split(Regex("""\s+[-–—]\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
            val cleanTitle = parts.firstOrNull { p -> artistNames.none { it.equals(p, ignoreCase = true) } }
                ?: parts.firstOrNull() ?: bare

            // 1. Essai avec paramètres précis
            var result = queryLrclib(artist, cleanTitle, album, durationSec)

            // 2. Fallback avec titre et artiste seuls si 404
            if (result == null && (!album.isNullOrBlank() || durationSec > 0)) {
                result = queryLrclib(artist, cleanTitle, null, 0)
            }

            // 3. Fallback recherche floue (indispensable pour les titres YouTube)
            if (result == null) {
                result = searchLrclib("$artist $cleanTitle", durationSec)
            }

            // 4. Dernier recours avec le titre brut
            if (result == null && cleanTitle != title) {
                result = searchLrclib(title, durationSec)
            }

            if (result != null) {
                cache[cacheKey] = result
            }
            android.util.Log.i("Lyrics", "'$artist - $cleanTitle' (${durationSec}s) : ${if (result == null) "aucune" else "${result.lines.size} lignes"} en ${System.currentTimeMillis() - started} ms")
            result
        }

    private fun queryLrclib(artist: String, title: String, album: String?, durationSec: Long): LyricsResult? {
        val urlBuilder = StringBuilder("https://lrclib.net/api/get")
            .append("?artist_name=").append(urlEncode(artist))
            .append("&track_name=").append(urlEncode(title))

        if (!album.isNullOrBlank()) urlBuilder.append("&album_name=").append(urlEncode(album))
        if (durationSec > 0) urlBuilder.append("&duration=").append(durationSec)

        val req = Request.Builder()
            .url(urlBuilder.toString())
            .header("User-Agent", "Spotywoop/1.0 (Android)")
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return null
                if (resp.code != 200) return null

                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)

                val synced = json.optString("syncedLyrics")
                if (synced.isNotBlank()) {
                    val parsed = parseLrc(synced)
                    if (parsed.isNotEmpty()) return LyricsResult(parsed, isSynced = true)
                }

                val plain = json.optString("plainLyrics")
                if (plain.isNotBlank()) {
                    val lines = plain.lines().map { LyricLine(0L, it) }
                    return LyricsResult(lines, isSynced = false)
                }

                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Recherche floue. Avec une durée connue, seules les versions à ±5 s comptent : sans ce garde-fou,
     * le premier résultat venu (souvent une autre chanson) s'affichait.
     */
    private fun searchLrclib(query: String, durationSec: Long = 0): LyricsResult? {
        val url = "https://lrclib.net/api/search?q=" + urlEncode(query)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Spotywoop/1.0 (Android)")
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                if (resp.code != 200) return null
                val body = resp.body?.string() ?: return null
                val array = org.json.JSONArray(body)
                if (array.length() == 0) return null

                // Trouver en priorité un résultat avec syncedLyrics
                val candidates = (0 until array.length()).map { array.getJSONObject(it) }
                    .filter { durationSec <= 0 || kotlin.math.abs(it.optDouble("duration", 0.0) - durationSec) <= 5 }
                    .take(6)
                for (obj in candidates) {
                    val synced = obj.optString("syncedLyrics")
                    if (synced.isNotBlank()) {
                        val parsed = parseLrc(synced)
                        if (parsed.isNotEmpty()) return LyricsResult(parsed, isSynced = true)
                    }
                }

                // Fallback avec plainLyrics
                for (obj in candidates) {
                    val plain = obj.optString("plainLyrics")
                    if (plain.isNotBlank()) {
                        val lines = plain.lines().map { LyricLine(0L, it) }
                        return LyricsResult(lines, isSynced = false)
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLrc(lrcText: String): List<LyricLine> {
        val list = mutableListOf<LyricLine>()
        for (line in lrcText.lines()) {
            val match = LRC_REGEX.find(line.trim()) ?: continue
            val min = match.groupValues[1].toLongOrNull() ?: continue
            val sec = match.groupValues[2].toDoubleOrNull() ?: continue
            val text = match.groupValues[3].trim()

            val timeMs = (min * 60_000L) + (sec * 1000).toLong()
            list.add(LyricLine(timeMs, text))
        }
        return list.sortedBy { it.timeMs }
    }

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}

