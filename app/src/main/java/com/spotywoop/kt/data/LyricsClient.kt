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

    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, LyricsResult?>()

    private val LRC_REGEX = Regex("""\[(\d+):(\d+(?:\.\d+)?)\](.*)""")

    suspend fun fetchLyrics(artist: String, title: String, album: String? = null, durationSec: Long = 0): LyricsResult? =
        withContext(Dispatchers.IO) {
            val cacheKey = "$artist - $title"
            if (cache.containsKey(cacheKey)) return@withContext cache[cacheKey]

            // Nettoyage intelligent du titre
            var cleanTitle = title.replace(Regex("""\(.*?\)|\[.*?\]"""), "").trim()
            if (cleanTitle.contains("-") && (cleanTitle.startsWith(artist, ignoreCase = true) || (artist.length > 2 && cleanTitle.contains(artist, ignoreCase = true)))) {
                cleanTitle = cleanTitle.substringAfter("-").trim()
            } else if (cleanTitle.contains("-")) {
                cleanTitle = cleanTitle.substringBefore("-").trim()
            }

            // 1. Essai avec paramètres précis
            var result = queryLrclib(artist, cleanTitle, album, durationSec)

            // 2. Fallback avec titre et artiste seuls si 404
            if (result == null && (!album.isNullOrBlank() || durationSec > 0)) {
                result = queryLrclib(artist, cleanTitle, null, 0)
            }

            // 3. Fallback recherche floue (indispensable pour les titres YouTube)
            if (result == null) {
                result = searchLrclib("$artist $cleanTitle")
            }

            // 4. Dernier recours avec le titre brut
            if (result == null && cleanTitle != title) {
                result = searchLrclib(title)
            }

            if (result != null) {
                cache[cacheKey] = result
            }
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

    private fun searchLrclib(query: String): LyricsResult? {
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
                val limit = minOf(array.length(), 6)
                for (i in 0 until limit) {
                    val obj = array.getJSONObject(i)
                    val synced = obj.optString("syncedLyrics")
                    if (synced.isNotBlank()) {
                        val parsed = parseLrc(synced)
                        if (parsed.isNotEmpty()) return LyricsResult(parsed, isSynced = true)
                    }
                }

                // Fallback avec plainLyrics
                for (i in 0 until limit) {
                    val obj = array.getJSONObject(i)
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

